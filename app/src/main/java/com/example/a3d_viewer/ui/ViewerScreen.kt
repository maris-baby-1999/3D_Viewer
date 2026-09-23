package com.example.a3d_viewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.a3d_viewer.assets.BundledModelAsset
import com.example.a3d_viewer.assets.GlbLabelParser
import com.example.a3d_viewer.assets.ModelAssetCatalog
import com.example.a3d_viewer.assets.PartLabel
import com.example.a3d_viewer.model.ActiveModelInstance
import com.example.a3d_viewer.model.ContainerInteractionMode
import com.example.a3d_viewer.model.ViewerState
import com.example.a3d_viewer.projection.LabelProjector
import io.github.sceneview.Scene
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.model.Model
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import kotlin.math.abs
import kotlin.math.tan

/**
 * Single full-screen Filament viewport + lightweight Compose overlays for containers / Add Model.
 *
 * Critical: only ONE [Scene] is created. Each "container" is a 2D bounding box mapped to a
 * [ModelNode] transform inside that shared scene (Steps 3–4 will wire gestures & labels).
 */
@Composable
fun ViewerScreen(
    state: ViewerState = remember { ViewerState() },
) {
    val context = LocalContext.current
    val assetManager = context.assets
    val labelCache = remember { mutableMapOf<String, List<PartLabel>>() }
    val nodeById = remember { mutableMapOf<String, ModelNode>() }
    val modelTemplates = remember { mutableMapOf<String, Model>() }
    val templateRefs = remember { mutableMapOf<String, Int>() }
    val appliedXform = remember { mutableMapOf<String, AppliedXform>() }
    val pathById = remember { mutableMapOf<String, String>() }
    val labelEntitiesById = remember { mutableMapOf<String, IntArray>() }
    val labelProjector = remember { LabelProjector() }
    var fpsText by remember { mutableStateOf("") }
    val fpsFrames = remember { intArrayOf(0) }
    val fpsWindowStartNs = remember { longArrayOf(0L) }

    LaunchedEffect(Unit) {
        state.loadAvailableAssets(ModelAssetCatalog.listBundledModels(assetManager))
    }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val childNodes: SnapshotStateList<Node> = rememberNodes()
    val cameraNode = rememberCameraNode(engine) {
        position = Position(x = 0f, y = 0f, z = CAMERA_DISTANCE)
        lookAt(Position(x = 0f, y = 0f, z = 0f))
    }

    DisposableEffect(Unit) {
        onDispose {
            nodeById.values.forEach { it.destroy() }
            nodeById.clear()
            appliedXform.clear()
            pathById.clear()
            labelEntitiesById.clear()
            modelTemplates.values.forEach { runCatching { modelLoader.destroyModel(it) } }
            modelTemplates.clear()
            templateRefs.clear()
            labelProjector.clear()
            labelCache.clear()
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val viewport = IntSize(
            width = with(density) { maxWidth.roundToPx() },
            height = with(density) { maxHeight.roundToPx() },
        )

        // Keep Filament nodes in sync with Compose state (add / remove only).
        LaunchedEffect(state.activeModels.map { it.id }.toList()) {
            val activeIds = state.activeModels.map { it.id }.toSet()

            val removed = nodeById.keys.filter { it !in activeIds }
            removed.forEach { id ->
                nodeById.remove(id)?.let { node ->
                    childNodes.remove(node)
                    node.destroy()
                }
                appliedXform.remove(id)
                labelEntitiesById.remove(id)
                val path = pathById.remove(id) ?: return@forEach
                val refs = (templateRefs[path] ?: 1) - 1
                if (refs <= 0) {
                    templateRefs.remove(path)
                    modelTemplates.remove(path)?.let { runCatching { modelLoader.destroyModel(it) } }
                } else {
                    templateRefs[path] = refs
                }
            }

            state.activeModels.forEach { instance ->
                if (nodeById.containsKey(instance.id)) return@forEach
                val path = instance.asset.assetPath
                val template = modelTemplates.getOrPut(path) { modelLoader.createModel(path) }
                val alreadyUsingDefault = (templateRefs[path] ?: 0) > 0
                val modelInstance = if (alreadyUsingDefault) {
                    modelLoader.createInstance(template) ?: modelLoader.createModelInstance(path)
                } else {
                    template.instance
                }
                val units = containerWorldUnits(instance, viewport)
                val node = ModelNode(
                    modelInstance = modelInstance,
                    autoAnimate = false,
                    scaleToUnits = units,
                )
                node.isShadowCaster = false
                node.isShadowReceiver = false
                node.collisionShape = null
                appliedXform[instance.id] = AppliedXform()
                labelEntitiesById[instance.id] =
                    labelProjector.bindLiveEntities(node, instance.labels)
                pathById[instance.id] = path
                templateRefs[path] = (templateRefs[path] ?: 0) + 1
                nodeById[instance.id] = node
                childNodes += node
            }
        }

        Scene(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            isOpaque = true,
            childNodes = childNodes,
            cameraNode = cameraNode,
            cameraManipulator = null,
            onFrame = { frameTimeNanos ->
                if (fpsWindowStartNs[0] == 0L) fpsWindowStartNs[0] = frameTimeNanos
                fpsFrames[0] += 1
                if (frameTimeNanos - fpsWindowStartNs[0] >= 1_000_000_000L) {
                    fpsText = "${fpsFrames[0]} FPS"
                    fpsFrames[0] = 0
                    fpsWindowStartNs[0] = frameTimeNanos
                }

                state.activeModels.forEach { instance ->
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
                state.activeModels.forEach { instance ->
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
                    state.activeModels.forEach { instance ->
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
            },
        )

        PartLabelOverlay(
            projector = labelProjector,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(0.5f),
        )

        if (fpsText.isNotEmpty()) {
            Text(
                text = fpsText,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .zIndex(4f)
                    .padding(10.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }

        // ── Virtual container overlays (2D) ─────────────────────────────────
        state.activeModels.forEach { instance ->
            ModelContainerOverlay(
                instance = instance,
                viewport = viewport,
                focused = instance.id == state.focusedId,
                onFocus = { state.focus(instance.id) },
                onClose = { state.removeModel(instance.id) },
            )
        }

        // ── Add Model FAB + dropdown ────────────────────────────────────────
        AddModelFab(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
            state = state,
            onPick = { asset ->
                val labels = labelCache.getOrPut(asset.assetPath) {
                    GlbLabelParser.parseFromAsset(assetManager, asset.assetPath)
                }
                state.addModel(asset, labels, viewport)
            },
        )
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

/** Camera distance from the placement plane (z = 0). Must match [rememberCameraNode] setup. */
private const val CAMERA_DISTANCE = 4.0f

/**
 * Approximate vertical FOV used by Filament/SceneView's default lens (degrees).
 * Used to keep Compose container centers aligned with 3D placements.
 */
private const val CAMERA_VERTICAL_FOV_DEG = 45f

private data class ViewFrustum(
    val halfWidth: Float,
    val halfHeight: Float,
)

private fun viewFrustum(viewport: IntSize): ViewFrustum {
    val halfFov = Math.toRadians(CAMERA_VERTICAL_FOV_DEG * 0.5).toFloat()
    val halfH = CAMERA_DISTANCE * tan(halfFov)
    val aspect = viewport.width.toFloat() / viewport.height.coerceAtLeast(1).toFloat()
    return ViewFrustum(halfWidth = halfH * aspect, halfHeight = halfH)
}

/**
 * Desired world-space size for a model from its 2D container, matched to camera frustum
 * so the mesh roughly fills the Compose bounding box.
 */
private fun containerWorldUnits(instance: ActiveModelInstance, viewport: IntSize): Float {
    val frustum = viewFrustum(viewport)
    val screenFraction = instance.sizePx / viewport.height.coerceAtLeast(1).toFloat()
    val fill = screenFraction * (2f * frustum.halfHeight) * 0.9f * instance.modelZoom
    val bias = displayScaleBias(instance.asset.fileName)
    return (fill * bias).coerceIn(0.4f, 6f)
}

/** Extra scale for assets whose glTF AABB is much larger than the visible subject. */
private fun displayScaleBias(fileName: String): Float {
    return when {
        fileName.contains("solar", ignoreCase = true) -> 2.8f
        else -> 1f
    }
}

/**
 * Maps a Compose screen point to the z=0 plane using the same frustum as the camera,
 * so container centers and 3D models stay aligned.
 */
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

@Composable
private fun ModelContainerOverlay(
    instance: ActiveModelInstance,
    viewport: IntSize,
    focused: Boolean,
    onFocus: () -> Unit,
    onClose: () -> Unit,
) {
    val density = LocalDensity.current
    val sizeDp = with(density) { instance.sizePx.toDp() }
    val half = instance.sizePx / 2f
    val mode = instance.interactionMode
    val interacting = mode == ContainerInteractionMode.Interaction
    val button = 36.dp
    val buttonPx = with(density) { button.toPx() }
    val gapPx = with(density) { 8.dp.toPx() }
    val padPx = with(density) { 10.dp.toPx() }
    val barWidth = buttonPx * 3f + gapPx * 2f
    val maxLeft = (viewport.width - barWidth - padPx).coerceAtLeast(padPx)
    val barLeft = (instance.center.x - half + padPx).coerceIn(padPx, maxLeft)
    val barTop = (instance.center.y - half + padPx)
        .coerceIn(padPx, (viewport.height - buttonPx - padPx).coerceAtLeast(padPx))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(if (focused) 2f else 1f),
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    translationX = instance.center.x - half
                    translationY = instance.center.y - half
                }
                .size(sizeDp)
                .pointerInput(instance.id) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.any { it.changedToDown() }) {
                                onFocus()
                            }
                        }
                    }
                }
                .pointerInput(instance.id, mode, viewport) {
                    detectTransformGestures(
                        panZoomLock = false,
                    ) { _, pan, zoom, _ ->
                        val pinching = zoom != 1f
                        when (mode) {
                            ContainerInteractionMode.Normal -> {
                                if (pinching) {
                                    instance.resizeBy(zoom, viewport)
                                } else {
                                    instance.moveBy(pan, viewport)
                                }
                            }
                            ContainerInteractionMode.Interaction -> {
                                if (pinching) {
                                    // Zoom the 3D model and its frame together.
                                    instance.resizeBy(zoom, viewport)
                                } else {
                                    instance.modelYawDeg += pan.x * 0.35f
                                    instance.modelPitchDeg =
                                        (instance.modelPitchDeg - pan.y * 0.35f)
                                            .coerceIn(-80f, 80f)
                                }
                            }
                        }
                    }
                }
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = Color.White.copy(alpha = if (focused) 0.9f else 0.55f),
                    shape = RoundedCornerShape(8.dp),
                ),
        ) {
            Text(
                text = instance.asset.displayName,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        Row(
            modifier = Modifier
                .zIndex(3f)
                .graphicsLayer {
                    translationX = barLeft
                    translationY = barTop
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OverlayIconButton(
                label = "✕",
                color = Color(0xFFE53935),
                onClick = onClose,
            )
            OverlayIconButton(
                label = if (interacting) "3D" else "Mv",
                color = if (interacting) Color(0xFF1565C0) else Color(0xE6212121),
                onClick = {
                    onFocus()
                    instance.toggleInteractionMode()
                },
            )
            OverlayIconButton(
                label = "Lbl",
                color = if (instance.labelsVisible) Color(0xFF2E7D32) else Color(0xE6212121),
                onClick = {
                    onFocus()
                    instance.toggleLabels()
                },
            )
        }
    }
}

@Composable
private fun OverlayIconButton(
    label: String,
    color: Color,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = color,
        contentColor = Color.White,
        shadowElevation = 6.dp,
        modifier = Modifier.size(36.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = label, fontSize = 13.sp, color = Color.White)
        }
    }
}

@Composable
private fun AddModelFab(
    modifier: Modifier = Modifier,
    state: ViewerState,
    onPick: (BundledModelAsset) -> Unit,
) {
    Box(modifier = modifier) {
        FloatingActionButton(
            onClick = { state.addMenuExpanded = true },
            containerColor = MaterialTheme.colorScheme.primary,
        ) {
            Text(text = "+ Model", modifier = Modifier.padding(horizontal = 12.dp))
        }
        DropdownMenu(
            expanded = state.addMenuExpanded,
            onDismissRequest = { state.addMenuExpanded = false },
        ) {
            if (state.availableAssets.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No .glb assets found") },
                    onClick = { state.addMenuExpanded = false },
                )
            } else {
                state.availableAssets.forEach { asset ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(asset.displayName)
                                Text(
                                    text = asset.fileName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = { onPick(asset) },
                    )
                }
            }
        }
    }
}
