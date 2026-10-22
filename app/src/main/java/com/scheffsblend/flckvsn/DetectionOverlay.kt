package com.scheffsblend.flckvsn

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.min

private val BOX_COLORS = listOf(
    Color(0xFF00FF66), Color(0xFF39FF14), Color(0xFF00E5FF),
    Color(0xFFFFEA00), Color(0xFFFF4081), Color(0xFF18FFFF),
    Color(0xFFFF6E40), Color(0xFFE040FB),
)

private fun colorFor(label: String): Color =
    BOX_COLORS[(label.hashCode().and(0x7FFFFFFF)) % BOX_COLORS.size]

/**
 * Draws detection boxes over the camera preview in Matrix HUD styling.
 */
@Composable
fun DetectionOverlay(
    detections: List<Detection>,
    frameWidth: Int,
    frameHeight: Int,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (frameWidth == 0 || frameHeight == 0) return@Canvas

        val scale = min(size.width / frameWidth, size.height / frameHeight)
        val drawnW = frameWidth * scale
        val drawnH = frameHeight * scale
        val offsetX = (size.width - drawnW) / 2f
        val offsetY = (size.height - drawnH) / 2f

        for (d in detections) {
            val left = offsetX + d.left * drawnW
            val top = offsetY + d.top * drawnH
            val right = offsetX + d.right * drawnW
            val bottom = offsetY + d.bottom * drawnH
            val color = colorFor(d.label)

            // Draw bounding box
            drawRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(width = 4f),
            )

            // Draw tag banner
            val label = "${d.label.uppercase()} ${(d.score * 100).toInt()}%"
            drawContext.canvas.nativeCanvas.apply {
                val paint = android.graphics.Paint().apply {
                    this.color = android.graphics.Color.BLACK
                    textSize = 30f
                    isAntiAlias = true
                    typeface = android.graphics.Typeface.MONOSPACE
                    isFakeBoldText = true
                }
                val bgPaint = android.graphics.Paint().apply {
                    this.color = (color.copy(alpha = 0.85f)).toArgb()
                }
                val textWidth = paint.measureText(label)
                drawRect(
                    left, (top - 36f).coerceAtLeast(0f),
                    left + textWidth + 12f, (top - 36f).coerceAtLeast(0f) + 36f,
                    bgPaint,
                )
                drawText(label, left + 6f, (top - 36f).coerceAtLeast(0f) + 26f, paint)
            }
        }
    }
}
