package com.scheffsblend.flckvsn

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.camera.core.ImageProxy
import kotlin.math.min
import kotlin.math.roundToInt

/** Result of a letterbox (aspect-preserving resize + pad) transform. */
data class LetterboxResult(
    val bitmap: Bitmap,
    val scale: Float,
    val padX: Int,
    val padY: Int,
)

object ImageUtils {

    /**
     * CameraX ImageAnalysis configured with OUTPUT_IMAGE_FORMAT_RGBA_8888
     * hands us a single-plane RGBA buffer directly -- no manual YUV
     * conversion needed.
     */
    fun imageProxyToBitmap(image: ImageProxy, rotationDegrees: Int): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888,
        )
        bitmap.copyPixelsFromBuffer(buffer)

        val cropped = if (rowPadding == 0) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        }

        if (rotationDegrees == 0) return cropped
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, true)
    }

    /** Direct (aspect-ignoring) stretch resize -- matches the SSD model's "squish" input mode. */
    fun squish(src: Bitmap, size: Int): Bitmap {
        return Bitmap.createScaledBitmap(src, size, size, true)
    }

    /**
     * Aspect-preserving resize + pad with gray (114,114,114) -- matches the
     * YOLO models' "maintainAspect" / fillColor:114 input mode (the
     * standard YOLOv5 letterbox preprocessing).
     */
    fun letterbox(src: Bitmap, size: Int): LetterboxResult {
        val scale = min(size.toFloat() / src.width, size.toFloat() / src.height)
        val newW = (src.width * scale).roundToInt()
        val newH = (src.height * scale).roundToInt()
        val resized = Bitmap.createScaledBitmap(src, newW, newH, true)

        val padX = (size - newW) / 2
        val padY = (size - newH) / 2

        val canvasBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        canvas.drawColor(Color.rgb(114, 114, 114))
        canvas.drawBitmap(resized, padX.toFloat(), padY.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))

        return LetterboxResult(canvasBitmap, scale, padX, padY)
    }

    /** Greedy per-class NMS. Boxes are (left, top, right, bottom), any consistent coordinate space. */
    fun nms(detections: List<Detection>, iouThreshold: Float): List<Detection> {
        val sorted = detections.sortedByDescending { it.score }
        val kept = mutableListOf<Detection>()
        for (d in sorted) {
            val suppressed = kept.any { it.label == d.label && iou(it, d) >= iouThreshold }
            if (!suppressed) kept.add(d)
        }
        return kept
    }

    private fun iou(a: Detection, b: Detection): Float {
        val ix0 = maxOf(a.left, b.left)
        val iy0 = maxOf(a.top, b.top)
        val ix1 = minOf(a.right, b.right)
        val iy1 = minOf(a.bottom, b.bottom)
        val iw = maxOf(0f, ix1 - ix0)
        val ih = maxOf(0f, iy1 - iy0)
        val inter = iw * ih
        val areaA = maxOf(0f, a.right - a.left) * maxOf(0f, a.bottom - a.top)
        val areaB = maxOf(0f, b.right - b.left) * maxOf(0f, b.bottom - b.top)
        val union = areaA + areaB - inter
        return if (union > 0f) inter / union else 0f
    }
}
