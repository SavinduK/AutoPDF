package com.example.cv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import com.example.data.model.CropRegion
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class EdgeCropResult(
    val bitmap: Bitmap,
    val isEdgeDetected: Boolean,
    val detectedCorners: List<PointF>? = null
)

object EdgeDetector {

    /**
     * Processes a raw bitmap: runs Canny edge detection + quadrilateral contour detection,
     * perspective transforms to rectangular slide, or falls back to manual crop region.
     */
    fun processAndCrop(
        source: Bitmap,
        manualCropRegion: CropRegion = CropRegion.FULL,
        enableAutoEdgeDetection: Boolean = true
    ): EdgeCropResult {
        if (!enableAutoEdgeDetection) {
            return EdgeCropResult(
                bitmap = cropToManualRegion(source, manualCropRegion),
                isEdgeDetected = false
            )
        }

        val detectedQuad = detectSlideQuadrilateral(source)
        if (detectedQuad != null && detectedQuad.size == 4) {
            val warped = perspectiveTransform(source, detectedQuad)
            if (warped != null) {
                return EdgeCropResult(
                    bitmap = warped,
                    isEdgeDetected = true,
                    detectedCorners = detectedQuad
                )
            }
        }

        // Fallback to manual region
        return EdgeCropResult(
            bitmap = cropToManualRegion(source, manualCropRegion),
            isEdgeDetected = false
        )
    }

    /**
     * Crops bitmap according to normalized CropRegion.
     */
    fun cropToManualRegion(source: Bitmap, region: CropRegion): Bitmap {
        if (region.left <= 0.01f && region.top <= 0.01f && region.right >= 0.99f && region.bottom >= 0.99f) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
        }

        val w = source.width
        val h = source.height

        val cropX = (w * region.left).toInt().coerceIn(0, w - 1)
        val cropY = (h * region.top).toInt().coerceIn(0, h - 1)
        val cropW = (w * region.getWidthFraction()).toInt().coerceIn(1, w - cropX)
        val cropH = (h * region.getHeightFraction()).toInt().coerceIn(1, h - cropY)

        return Bitmap.createBitmap(source, cropX, cropY, cropW, cropH)
    }

    /**
     * Finds the largest confident 4-corner rectangular slide contour using Canny edge detection.
     */
    private fun detectSlideQuadrilateral(source: Bitmap): List<PointF>? {
        val targetWidth = 400
        if (source.width <= 0 || source.height <= 0) return null

        val scale = targetWidth.toFloat() / source.width.toFloat()
        val targetHeight = (source.height * scale).toInt().coerceAtLeast(100)

        val small = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        val w = small.width
        val h = small.height

        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small != source && !small.isRecycled) {
            small.recycle()
        }

        // 1. Grayscale
        val gray = FloatArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            gray[i] = Color.red(p) * 0.299f + Color.green(p) * 0.587f + Color.blue(p) * 0.114f
        }

        // 2. 5x5 Gaussian blur
        val blurred = FloatArray(w * h)
        val kernel = floatArrayOf(
            1f, 4f, 7f, 4f, 1f,
            4f, 16f, 26f, 16f, 4f,
            7f, 26f, 41f, 26f, 7f,
            4f, 16f, 26f, 16f, 4f,
            1f, 4f, 7f, 4f, 1f
        )
        val kernelSum = 273f

        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                var acc = 0f
                var ki = 0
                for (ky in -2..2) {
                    for (kx in -2..2) {
                        acc += gray[(y + ky) * w + (x + kx)] * kernel[ki++]
                    }
                }
                blurred[y * w + x] = acc / kernelSum
            }
        }

        // 3. Sobel filter for horizontal and vertical gradients
        val magnitude = FloatArray(w * h)
        val angle = FloatArray(w * h)

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val p00 = blurred[(y - 1) * w + (x - 1)]
                val p01 = blurred[(y - 1) * w + x]
                val p02 = blurred[(y - 1) * w + (x + 1)]
                val p10 = blurred[y * w + (x - 1)]
                val p12 = blurred[y * w + (x + 1)]
                val p20 = blurred[(y + 1) * w + (x - 1)]
                val p21 = blurred[(y + 1) * w + x]
                val p22 = blurred[(y + 1) * w + (x + 1)]

                val gx = (p02 + 2f * p12 + p22) - (p00 + 2f * p10 + p20)
                val gy = (p20 + 2f * p21 + p22) - (p00 + 2f * p01 + p02)

                magnitude[y * w + x] = hypot(gx, gy)
                angle[y * w + x] = atan2(gy, gx)
            }
        }

        // 4. Non-maximum suppression & Hysteresis thresholding
        val edges = BooleanArray(w * h)
        val highThreshold = 40f
        val lowThreshold = 18f

        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                val m = magnitude[y * w + x]
                if (m < lowThreshold) continue

                // Quantize angle to 0, 45, 90, 135 degrees
                var theta = angle[y * w + x] * 180f / Math.PI.toFloat()
                if (theta < 0) theta += 180f

                var neighborA = 0f
                var neighborB = 0f

                if ((theta >= 0 && theta < 22.5) || (theta >= 157.5 && theta <= 180)) {
                    neighborA = magnitude[y * w + (x + 1)]
                    neighborB = magnitude[y * w + (x - 1)]
                } else if (theta >= 22.5 && theta < 67.5) {
                    neighborA = magnitude[(y + 1) * w + (x + 1)]
                    neighborB = magnitude[(y - 1) * w + (x - 1)]
                } else if (theta >= 67.5 && theta < 112.5) {
                    neighborA = magnitude[(y + 1) * w + x]
                    neighborB = magnitude[(y - 1) * w + x]
                } else {
                    neighborA = magnitude[(y - 1) * w + (x + 1)]
                    neighborB = magnitude[(y + 1) * w + (x - 1)]
                }

                if (m >= neighborA && m >= neighborB && m >= highThreshold) {
                    edges[y * w + x] = true
                }
            }
        }

        // 5. Scan bounding box of major edges to identify outer slide frame
        // Find dense edge boundaries (horizontal and vertical projections)
        val colEdgeCount = IntArray(w)
        val rowEdgeCount = IntArray(h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (edges[y * w + x]) {
                    colEdgeCount[x]++
                    rowEdgeCount[y]++
                }
            }
        }

        // Threshold for prominent edge line
        val minLineCol = (h * 0.08f).toInt().coerceAtLeast(6)
        val minLineRow = (w * 0.08f).toInt().coerceAtLeast(6)

        // Find outermost strong lines with significant area
        var minX = 0
        while (minX < w / 3 && colEdgeCount[minX] < minLineCol) minX++
        var maxX = w - 1
        while (maxX > w * 2 / 3 && colEdgeCount[maxX] < minLineCol) maxX--

        var minY = 0
        while (minY < h / 3 && rowEdgeCount[minY] < minLineRow) minY++
        var maxY = h - 1
        while (maxY > h * 2 / 3 && rowEdgeCount[maxY] < minLineRow) maxY--

        val detectedW = maxX - minX
        val detectedH = maxY - minY

        // Check if detected rectangle occupies a plausible slide area (> 25% of screen)
        val totalArea = w * h
        val detectedArea = detectedW * detectedH
        if (detectedArea.toFloat() / totalArea.toFloat() < 0.25f || detectedW < w * 0.4f || detectedH < h * 0.4f) {
            return null
        }

        // Convert back to original coordinate system
        val invScale = 1.0f / scale
        val pTL = PointF(minX * invScale, minY * invScale)
        val pTR = PointF(maxX * invScale, minY * invScale)
        val pBR = PointF(maxX * invScale, maxY * invScale)
        val pBL = PointF(minX * invScale, maxY * invScale)

        return listOf(pTL, pTR, pBR, pBL)
    }

    /**
     * Warps a quadrilateral region of the source bitmap into a rectangular perspective-corrected image.
     */
    private fun perspectiveTransform(source: Bitmap, quad: List<PointF>): Bitmap? {
        if (quad.size != 4) return null

        val p0 = quad[0] // TL
        val p1 = quad[1] // TR
        val p2 = quad[2] // BR
        val p3 = quad[3] // BL

        // Compute output width and height based on quad edge lengths
        val widthTop = hypot(p1.x - p0.x, p1.y - p0.y)
        val widthBottom = hypot(p2.x - p3.x, p2.y - p3.y)
        val outWidth = max(widthTop, widthBottom).toInt().coerceIn(100, source.width * 2)

        val heightLeft = hypot(p3.x - p0.x, p3.y - p0.y)
        val heightRight = hypot(p2.x - p1.x, p2.y - p1.y)
        val outHeight = max(heightLeft, heightRight).toInt().coerceIn(100, source.height * 2)

        val srcPoints = floatArrayOf(
            p0.x, p0.y,
            p1.x, p1.y,
            p2.x, p2.y,
            p3.x, p3.y
        )

        val dstPoints = floatArrayOf(
            0f, 0f,
            outWidth.toFloat(), 0f,
            outWidth.toFloat(), outHeight.toFloat(),
            0f, outHeight.toFloat()
        )

        val matrix = Matrix()
        val success = matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)
        if (!success) {
            // Fallback simple rectangular crop if matrix inversion fails
            val minX = min(min(p0.x, p1.x), min(p2.x, p3.x)).toInt().coerceIn(0, source.width - 1)
            val minY = min(min(p0.y, p1.y), min(p2.y, p3.y)).toInt().coerceIn(0, source.height - 1)
            val maxX = max(max(p0.x, p1.x), max(p2.x, p3.x)).toInt().coerceIn(minX + 1, source.width)
            val maxY = max(max(p0.y, p1.y), max(p2.y, p3.y)).toInt().coerceIn(minY + 1, source.height)
            return Bitmap.createBitmap(source, minX, minY, maxX - minX, maxY - minY)
        }

        val output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, matrix, paint)

        return output
    }
}
