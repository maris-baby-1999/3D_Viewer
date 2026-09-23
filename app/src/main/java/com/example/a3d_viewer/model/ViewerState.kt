package com.example.a3d_viewer.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.example.a3d_viewer.assets.BundledModelAsset
import com.example.a3d_viewer.assets.PartLabel
import java.util.UUID

enum class ContainerInteractionMode {
    /** 1-finger drag moves container; 2-finger pinch resizes container. */
    Normal,

    /** 1-finger rotates model; 2-finger pinch zooms model. */
    Interaction,
}

/**
 * One live model instance on the shared 3D canvas.
 * Container frame is in screen/Compose space; 3D transform is applied inside the single Scene.
 */
class ActiveModelInstance(
    val id: String = UUID.randomUUID().toString(),
    val asset: BundledModelAsset,
    val labels: List<PartLabel>,
    initialCenter: Offset,
    initialSizePx: Float,
) {
    var center by mutableStateOf(initialCenter)
    var sizePx by mutableFloatStateOf(initialSizePx)

    /** Smallest allowed frame — cannot pinch smaller than the size at load. */
    val minSizePx: Float = initialSizePx.coerceAtLeast(MIN_CONTAINER_PX)

    /** Model-local yaw/pitch used in Interaction mode (degrees). */
    var modelYawDeg by mutableFloatStateOf(0f)
    var modelPitchDeg by mutableFloatStateOf(0f)

    /** Extra uniform scale applied to the 3D model itself (pinch-zoom in Interaction mode). */
    var modelZoom by mutableFloatStateOf(1f)

    var labelsVisible by mutableStateOf(false)
    var interactionMode by mutableStateOf(ContainerInteractionMode.Normal)

    fun toggleLabels() {
        labelsVisible = !labelsVisible
    }

    fun toggleInteractionMode() {
        interactionMode = when (interactionMode) {
            ContainerInteractionMode.Normal -> ContainerInteractionMode.Interaction
            ContainerInteractionMode.Interaction -> ContainerInteractionMode.Normal
        }
    }

    fun moveBy(delta: Offset, viewport: IntSize) {
        val x = (center.x + delta.x).coerceIn(0f, viewport.width.toFloat())
        val y = (center.y + delta.y).coerceIn(0f, viewport.height.toFloat())
        center = Offset(x, y)
    }

    fun resizeBy(zoom: Float, viewport: IntSize) {
        if (zoom == 1f) return
        val maxPx = minOf(viewport.width, viewport.height).toFloat() * 0.95f
        sizePx = (sizePx * zoom).coerceIn(minSizePx, maxPx.coerceAtLeast(minSizePx))
    }

    companion object {
        const val MIN_CONTAINER_PX = 140f
    }
}

/**
 * Compose-facing state holder for the multi-model viewer.
 * Step 1–2: asset listing + add/remove. Gestures / projection wired in later steps.
 */
class ViewerState {
    val activeModels = mutableStateListOf<ActiveModelInstance>()

    var availableAssets by mutableStateOf<List<BundledModelAsset>>(emptyList())
        private set

    var addMenuExpanded by mutableStateOf(false)
    var focusedId by mutableStateOf<String?>(null)

    fun loadAvailableAssets(assets: List<BundledModelAsset>) {
        availableAssets = assets
    }

    fun focus(id: String) {
        focusedId = id
    }

    fun addModel(
        asset: BundledModelAsset,
        labels: List<PartLabel>,
        viewport: IntSize,
    ) {
        val index = activeModels.size
        val cell = layoutSlot(index, viewport)
        val created = ActiveModelInstance(
            asset = asset,
            labels = labels,
            initialCenter = cell.center,
            initialSizePx = cell.sizePx,
        )
        activeModels += created
        focusedId = created.id
        addMenuExpanded = false
    }

    fun removeModel(id: String) {
        activeModels.removeAll { it.id == id }
        if (focusedId == id) {
            focusedId = activeModels.lastOrNull()?.id
        }
    }

    private data class Slot(val center: Offset, val sizePx: Float)

    private fun layoutSlot(index: Int, viewport: IntSize): Slot {
        val w = viewport.width.coerceAtLeast(1).toFloat()
        val h = viewport.height.coerceAtLeast(1).toFloat()
        val cols = 2
        val rows = 3
        val col = index % cols
        val row = (index / cols) % rows
        val cellW = w / cols
        val cellH = h / rows
        val size = minOf(cellW, cellH) * 0.72f
        return Slot(
            center = Offset(cellW * (col + 0.5f), cellH * (row + 0.5f)),
            sizePx = size,
        )
    }
}
