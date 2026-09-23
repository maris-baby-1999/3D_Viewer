package com.example.a3d_viewer.scene

import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.example.a3d_viewer.model.ActiveModelInstance
import com.example.a3d_viewer.projection.LabelProjector
import com.google.android.filament.Engine
import io.github.sceneview.SceneView
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.model.Model
import io.github.sceneview.node.CameraNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.tan

/**
 * View-scoped Filament adapter. Not a ViewModel — Engine / ModelNode must die
 * with the Surface, not survive configuration changes.
 */
class SceneController {
    val labelProjector = LabelProjector()

    var alive: Boolean = true

    private val nodeById = mutableMapOf<String, ModelNode>()
    private val modelTemplates = mutableMapOf<String, Model>()
    private val templateRefs = mutableMapOf<String, Int>()
    private val unusedByPath = mutableMapOf<String, ArrayDeque<ModelNode>>()
    private val appliedXform = mutableMapOf<String, AppliedXform>()
    private val pathById = mutableMapOf<String, String>()
    private val labelEntitiesById = mutableMapOf<String, IntArray>()

    fun syncNodes(
        activeModels: List<ActiveModelInstance>,
        childNodes: SnapshotStateList<Node>,
        modelLoader: ModelLoader,
        viewport: IntSize,
    ) {
        val activeIds = activeModels.map { it.id }.toSet()

        val removed = nodeById.keys.filter { it !in activeIds }
        removed.forEach { id ->
            val node = nodeById.remove(id)
            appliedXform.remove(id)
            labelEntitiesById.remove(id)
            val path = pathById.remove(id)
            if (node != null) {
                // Detach only. node.destroy() frees the glTF root entity while the
                // FilamentAsset still owns it; destroyModel() then double-frees → SIGSEGV.
                node.isVisible = false
                childNodes.remove(node)
                if (path != null) {
                    unusedByPath.getOrPut(path) { ArrayDeque() }.addLast(node)
                }
            }
        }

        activeModels.forEach { instance ->
            if (nodeById.containsKey(instance.id)) return@forEach
            val path = instance.asset.assetPath
            val recycled = unusedByPath[path]?.pollFirst()
            val node = if (recycled != null) {
                recycled.isVisible = true
                recycled
            } else {
                val template = modelTemplates.getOrPut(path) { modelLoader.createModel(path) }
                val alreadyUsingDefault = (templateRefs[path] ?: 0) > 0
                val modelInstance = if (alreadyUsingDefault) {
                    modelLoader.createInstance(template) ?: modelLoader.createModelInstance(path)
                } else {
                    template.instance
                }
                ModelNode(
                    modelInstance = modelInstance,
                    autoAnimate = false,
                    scaleToUnits = containerWorldUnits(instance, viewport),
                ).apply {
                    isShadowCaster = false
                    isShadowReceiver = false
                    collisionShape = null
                }.also {
                    templateRefs[path] = (templateRefs[path] ?: 0) + 1
                }
            }
            appliedXform[instance.id] = AppliedXform()
            labelEntitiesById[instance.id] =
                labelProjector.bindLiveEntities(node, instance.labels)
            pathById[instance.id] = path
            nodeById[instance.id] = node
            if (node !in childNodes) childNodes += node
        }
    }

    fun onFrame(
        activeModels: List<ActiveModelInstance>,
        cameraNode: CameraNode,
        engine: Engine,
        viewport: IntSize,
    ) {
        if (!alive) return

        activeModels.forEach { instance ->
            val node = nodeById[instance.id] ?: return@forEach
            val prev = appliedXform.getOrPut(instance.id) { AppliedXform() }
            val units = containerWorldUnits(instance, viewport)
            val sizeDirty = prev.units.isNaN() || abs(prev.units - units) > 1e-3f
            val posDirty = prev.cx.isNaN() ||
                abs(prev.cx - instance.center.x) > 0.25f ||
                abs(prev.cy - instance.center.y) > 0.25f
            val rotDirty = prev.yaw.isNaN() ||
                abs(prev.yaw - instance.modelYawDeg) > 0.05f ||
                abs(prev.pitch - instance.modelPitchDeg) > 0.05f
            if (!sizeDirty && !posDirty && !rotDirty) return@forEach

            if (sizeDirty) {
                // Re-normalize from the asset bbox — never overwrite with a raw Scale.
                node.scaleToUnitCube(units)
                prev.units = units
            }
            if (posDirty || sizeDirty) {
                val worldPos = screenToWorldApprox(instance.center, viewport)
                node.position = Position(
                    x = worldPos.x - node.center.x * node.scale.x,
                    y = worldPos.y - node.center.y * node.scale.y,
                    z = worldPos.z,
                )
                prev.cx = instance.center.x
                prev.cy = instance.center.y
            }
            if (rotDirty) {
                node.rotation = Rotation(
                    x = instance.modelPitchDeg,
                    y = instance.modelYawDeg,
                    z = 0f,
                )
                prev.yaw = instance.modelYawDeg
                prev.pitch = instance.modelPitchDeg
            }
        }

        var anyLabels = false
        activeModels.forEach { instance ->
            if (instance.labelsVisible && instance.labels.isNotEmpty()) {
                anyLabels = true
            }
        }
        if (anyLabels) {
            labelProjector.beginFrame(
                camera = cameraNode.camera,
                widthPx = viewport.width,
                heightPx = viewport.height,
            )
            activeModels.forEach { instance ->
                if (!instance.labelsVisible || instance.labels.isEmpty()) return@forEach
                val node = nodeById[instance.id] ?: return@forEach
                labelProjector.project(
                    engine,
                    node,
                    instance.labels,
                    labelEntitiesById[instance.id],
                )
            }
            labelProjector.publish()
        } else if (labelProjector.count != 0) {
            labelProjector.clear()
        }
    }

    fun releaseMaps() {
        alive = false
        nodeById.clear()
        unusedByPath.clear()
        appliedXform.clear()
        pathById.clear()
        labelEntitiesById.clear()
        modelTemplates.clear()
        templateRefs.clear()
        labelProjector.clear()
    }

    fun detachScene(sceneView: SceneView?, childNodes: SnapshotStateList<Node>) {
        alive = false
        sceneView?.childNodes = emptyList()
        childNodes.clear()
    }

    companion object {
        /** Camera distance from the placement plane (z = 0). Must match rememberCameraNode. */
        const val CAMERA_DISTANCE = 4.0f
    }
}

/** Last Filament transform written for a container — skip idle onFrame writes. */
private class AppliedXform {
    var units = Float.NaN
    var cx = Float.NaN
    var cy = Float.NaN
    var yaw = Float.NaN
    var pitch = Float.NaN
}

private const val CAMERA_VERTICAL_FOV_DEG = 45f

private data class ViewFrustum(
    val halfWidth: Float,
    val halfHeight: Float,
)

private fun viewFrustum(viewport: IntSize): ViewFrustum {
    val halfFov = Math.toRadians(CAMERA_VERTICAL_FOV_DEG * 0.5).toFloat()
    val halfH = SceneController.CAMERA_DISTANCE * tan(halfFov)
    val aspect = viewport.width.toFloat() / viewport.height.coerceAtLeast(1).toFloat()
    return ViewFrustum(halfWidth = halfH * aspect, halfHeight = halfH)
}

private fun containerWorldUnits(instance: ActiveModelInstance, viewport: IntSize): Float {
    val frustum = viewFrustum(viewport)
    val screenFraction = instance.sizePx / viewport.height.coerceAtLeast(1).toFloat()
    val fill = screenFraction * (2f * frustum.halfHeight) * 0.9f * instance.modelZoom
    val bias = displayScaleBias(instance.asset.fileName)
    return (fill * bias).coerceIn(0.4f, 6f)
}

private fun displayScaleBias(fileName: String): Float {
    return when {
        fileName.contains("solar", ignoreCase = true) -> 2.8f
        else -> 1f
    }
}

private fun screenToWorldApprox(center: Offset, viewport: IntSize): Position {
    val frustum = viewFrustum(viewport)
    val nx = (center.x / viewport.width.coerceAtLeast(1)) * 2f - 1f
    val ny = 1f - (center.y / viewport.height.coerceAtLeast(1)) * 2f
    return Position(
        x = nx * frustum.halfWidth,
        y = ny * frustum.halfHeight,
        z = 0f,
    )
}
