package com.example.a3d_viewer.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.example.a3d_viewer.assets.BundledModelAsset
import com.example.a3d_viewer.assets.PartLabel
import java.util.UUID

enum class ContainerInteractionMode {
    Normal,

    Interaction,
}

class ActiveModelInstance(
    val id: String = UUID.randomUUID().toString(),
    val asset: BundledModelAsset,
    val labels: List<PartLabel>,
    initialCenter: Offset,
    initialSizePx: Float,
) {
    var center by mutableStateOf(initialCenter)
    var sizePx by mutableFloatStateOf(initialSizePx)

    val minSizePx: Float = initialSizePx.coerceAtLeast(MIN_CONTAINER_PX)

    var modelYawDeg by mutableFloatStateOf(0f)
    var modelPitchDeg by mutableFloatStateOf(0f)

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

