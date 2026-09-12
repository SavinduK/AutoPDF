package com.example.cv

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.sqrt

object ImageProcessor {

    /**
     * Computes a 64-bit Difference Hash (dHash) for perceptual image similarity comparison.
     * Scale to 9x8, convert to grayscale, check horizontal gradient.
     */
    fun calculateDHash(bitmap: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        var hash = 0L
        val pixels = IntArray(9 * 8)
        scaled.getPixels(pixels, 0, 9, 0, 0, 9, 8)
        if (scaled != bitmap && !scaled.isRecycled) {
            scaled.recycle()
        }

        var bitIndex = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val leftPixel = pixels[y * 9 + x]
                val rightPixel = pixels[y * 9 + x + 1]

                // Fast perceptual luminance: 0.299 R + 0.587 G + 0.114 B
                val leftLuma = (Color.red(leftPixel) * 299 + Color.green(leftPixel) * 587 + Color.blue(leftPixel) * 114) / 1000
                val rightLuma = (Color.red(rightPixel) * 299 + Color.green(rightPixel) * 587 + Color.blue(rightPixel) * 114) / 1000

                if (leftLuma > rightLuma) {
                    hash = hash or (1L shl bitIndex)
                }
                bitIndex++
            }
        }
        return hash
    }

    /**
     * Calculates the Hamming distance between two 64-bit perceptual hashes.
     * Distance represents the number of differing bits (0 = identical, <= 8 = near identical).
     */
    fun hammingDistance(hash1: Long, hash2: Long): Int {
        return java.lang.Long.bitCount(hash1 xor hash2)
    }

    /**
     * Checks if two hashes are considered duplicates within the given threshold (default 8 bits out of 64).
     */
    fun areDuplicates(hash1: Long, hash2: Long, threshold: Int = 8): Boolean {
        return hammingDistance(hash1, hash2) <= threshold
    }

    /**
     * Calculates the Laplacian variance to measure sharpness/focus of an image.
     * Higher variance indicates sharper edges, higher focus, and less blur.
     */
    fun calculateLaplacianVariance(bitmap: Bitmap): Double {
        val targetWidth = 320
        val targetHeight = if (bitmap.width > 0) (bitmap.height * targetWidth) / bitmap.width else 240
        val safeHeight = targetHeight.coerceIn(100, 320)

        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, safeHeight, true)
        val w = scaled.width
        val h = scaled.height

        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled != bitmap && !scaled.isRecycled) {
            scaled.recycle()
        }

        // Grayscale 1D array
        val gray = DoubleArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            gray[i] = (Color.red(p) * 0.299 + Color.green(p) * 0.587 + Color.blue(p) * 0.114)
        }

        // 3x3 Laplacian convolution
        // Kernel:
        // [ 0,  1,  0]
        // [ 1, -4,  1]
        // [ 0,  1,  0]
        var sum = 0.0
        var count = 0
        val laplacianValues = DoubleArray((w - 2) * (h - 2))

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val center = gray[y * w + x]
                val up = gray[(y - 1) * w + x]
                val down = gray[(y + 1) * w + x]
                val left = gray[y * w + (x - 1)]
                val right = gray[y * w + (x + 1)]

                val lap = (up + down + left + right) - (4.0 * center)
                laplacianValues[count] = lap
                sum += lap
                count++
            }
        }

        if (count == 0) return 0.0

        val mean = sum / count
        var varianceSum = 0.0
        for (i in 0 until count) {
            val diff = laplacianValues[i] - mean
            varianceSum += diff * diff
        }

        return varianceSum / count
    }
}
