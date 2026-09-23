package com.example.a3d_viewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.example.a3d_viewer.projection.LabelProjector

/**
 * Lightweight 2D overlay: part-name chips + connector lines glued to projected 3D anchors.
 * Reads pre-allocated buffers from [LabelProjector]; does not allocate in the draw loop
 * beyond a single reused Android [android.graphics.Paint].
 */
@Composable
fun PartLabelOverlay(
    projector: LabelProjector,
    modifier: Modifier = Modifier,
) {
    val revision = projector.revision
    val paint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 28f
            color = android.graphics.Color.WHITE
        }
    }
    val padX = 10f
    val padY = 6f
    val chipH = 28f + padY * 2f

    Canvas(modifier = modifier.fillMaxSize()) {
        // Read revision so Compose invalidates when projection updates.
        @Suppress("UNUSED_VARIABLE")
        val frame = revision
        val count = projector.count
        var i = 0
        while (i < count) {
            if (!projector.visible[i]) {
                i++
                continue
            }
            val text = projector.texts[i]
            if (text == null) {
                i++
                continue
            }
            val ax = projector.screenX[i]
            val ay = projector.screenY[i]
            val textW = paint.measureText(text)
            val chipW = textW + padX * 2f
            val side = if (i % 2 == 0) 1f else -1f
            val lx = ax + side * 52f - if (side < 0f) chipW else 0f
            val ly = ay - 36f - (i % 3) * 6f
            val lineEnd = Offset(
                x = if (side > 0f) lx else lx + chipW,
                y = ly + chipH * 0.5f,
            )

            drawLine(
                color = Color(0xFFF6C445),
                start = Offset(ax, ay),
                end = lineEnd,
                strokeWidth = 1.5.dp.toPx(),
            )
            drawCircle(
                color = Color(0xFFF6C445),
                radius = 3.5.dp.toPx(),
                center = Offset(ax, ay),
            )
            drawRoundRect(
                color = Color(0xCC111111),
                topLeft = Offset(lx, ly),
                size = Size(chipW, chipH),
                cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
            )
            drawRoundRect(
                color = Color(0x66FFFFFF),
                topLeft = Offset(lx, ly),
                size = Size(chipW, chipH),
                cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                style = Stroke(width = 1.dp.toPx()),
            )
            drawContext.canvas.nativeCanvas.drawText(
                text,
                lx + padX,
                ly + padY + paint.textSize * 0.85f,
                paint,
            )
            i++
        }
    }
}
