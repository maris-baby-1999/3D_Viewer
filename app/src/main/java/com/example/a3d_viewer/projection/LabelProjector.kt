package com.example.a3d_viewer.projection

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.example.a3d_viewer.assets.PartLabel
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import io.github.sceneview.node.ModelNode
import kotlin.math.abs

/**
 * Projects part-label anchors to screen pixels each frame.
 *
 * Prefers the live Filament entity world translation (Empty / mesh node from the
 * 3D library). Falls back to baked model-space xyz × the model-root matrix.
 *
 * All matrices / vectors are pre-allocated. [beginFrame] / [project] write into
 * [screenX]/[screenY] and bump [revision] so Compose can redraw without allocating
 * Vector3 / Matrix objects in the render loop.
 */
class LabelProjector(
    capacity: Int = 256,
) {
    val texts = arrayOfNulls<String>(capacity)
    val screenX = FloatArray(capacity)
    val screenY = FloatArray(capacity)
    val visible = BooleanArray(capacity)
    var count: Int = 0
        private set

    /** Bumped after each projection pass that should trigger a Canvas redraw. */
    var revision by mutableIntStateOf(0)
        private set

    private val view = DoubleArray(16)
    private val proj = DoubleArray(16)
    private val viewProj = DoubleArray(16)
    private val world = FloatArray(16)
    private val clip = DoubleArray(4)

    private var viewportW = 1f
    private var viewportH = 1f
    private val lastX = FloatArray(capacity)
    private val lastY = FloatArray(capacity)
    private var lastCount = -1

    fun beginFrame(camera: Camera, widthPx: Int, heightPx: Int) {
        camera.getViewMatrix(view)
        camera.getProjectionMatrix(proj)
        multiplyMat4(proj, view, viewProj)
        viewportW = widthPx.coerceAtLeast(1).toFloat()
        viewportH = heightPx.coerceAtLeast(1).toFloat()
        count = 0
    }

    /**
     * Resolve each parsed label to a live Filament entity on [node]
     * (name first, then extras text). `0` means "use baked fallback".
     */
    fun bindLiveEntities(node: ModelNode, labels: List<PartLabel>): IntArray {
        val out = IntArray(labels.size)
        val model = node.model
        val instanceEntities = node.modelInstance.entities
        var i = 0
        while (i < labels.size) {
            val label = labels[i]
            var entity = 0
            val name = label.nodeName
            if (!name.isNullOrBlank()) {
                entity = model.getFirstEntityByName(name)
            }
            if (entity == 0) {
                val text = label.text
                for (candidate in instanceEntities) {
                    val extras = model.getExtras(candidate) ?: continue
                    if (extras.contains(text)) {
                        entity = candidate
                        break
                    }
                }
            }
            out[i] = entity
            i++
        }
        return out
    }

    fun project(
        engine: Engine,
        node: ModelNode,
        labels: List<PartLabel>,
        liveEntities: IntArray? = null,
    ) {
        val tm = engine.transformManager
        val rootInstance = tm.getInstance(node.entity)
        if (rootInstance == 0) return

        val n = labels.size
        var i = 0
        while (i < n && count < texts.size) {
            val label = labels[i]
            val live = if (liveEntities != null && i < liveEntities.size) liveEntities[i] else 0
            val liveInst = if (live != 0) tm.getInstance(live) else 0

            val wx: Double
            val wy: Double
            val wz: Double
            if (liveInst != 0) {
                tm.getWorldTransform(liveInst, world)
                wx = world[12].toDouble()
                wy = world[13].toDouble()
                wz = world[14].toDouble()
            } else {
                tm.getWorldTransform(rootInstance, world)
                val lp = label.localPosition
                wx = (world[0] * lp[0] + world[4] * lp[1] + world[8] * lp[2] + world[12]).toDouble()
                wy = (world[1] * lp[0] + world[5] * lp[1] + world[9] * lp[2] + world[13]).toDouble()
                wz = (world[2] * lp[0] + world[6] * lp[1] + world[10] * lp[2] + world[14]).toDouble()
            }

            // World → clip.
            clip[0] = viewProj[0] * wx + viewProj[4] * wy + viewProj[8] * wz + viewProj[12]
            clip[1] = viewProj[1] * wx + viewProj[5] * wy + viewProj[9] * wz + viewProj[13]
            clip[2] = viewProj[2] * wx + viewProj[6] * wy + viewProj[10] * wz + viewProj[14]
            clip[3] = viewProj[3] * wx + viewProj[7] * wy + viewProj[11] * wz + viewProj[15]

            val w = clip[3]
            val idx = count
            texts[idx] = label.text
            if (w > 1e-4) {
                val ndcX = (clip[0] / w).toFloat()
                val ndcY = (clip[1] / w).toFloat()
                val inFront = clip[2] / w <= 1.0
                screenX[idx] = (ndcX * 0.5f + 0.5f) * viewportW
                screenY[idx] = (1f - (ndcY * 0.5f + 0.5f)) * viewportH
                visible[idx] = inFront &&
                    screenX[idx] > -80f && screenX[idx] < viewportW + 80f &&
                    screenY[idx] > -80f && screenY[idx] < viewportH + 80f
            } else {
                visible[idx] = false
            }
            count = idx + 1
            i++
        }
    }

    fun publish() {
        if (count == lastCount) {
            var i = 0
            var moved = false
            while (i < count) {
                if (abs(screenX[i] - lastX[i]) > 0.5f || abs(screenY[i] - lastY[i]) > 0.5f) {
                    moved = true
                    break
                }
                i++
            }
            if (!moved) return
        }
        var i = 0
        while (i < count) {
            lastX[i] = screenX[i]
            lastY[i] = screenY[i]
            i++
        }
        lastCount = count
        revision++
    }

    fun clear() {
        count = 0
        lastCount = 0
        revision++
    }

    /** Column-major 4x4: out = a * b */
    private fun multiplyMat4(a: DoubleArray, b: DoubleArray, out: DoubleArray) {
        var col = 0
        while (col < 4) {
            val b0 = b[col * 4]
            val b1 = b[col * 4 + 1]
            val b2 = b[col * 4 + 2]
            val b3 = b[col * 4 + 3]
            var row = 0
            while (row < 4) {
                out[col * 4 + row] =
                    a[row] * b0 + a[4 + row] * b1 + a[8 + row] * b2 + a[12 + row] * b3
                row++
            }
            col++
        }
    }
}
