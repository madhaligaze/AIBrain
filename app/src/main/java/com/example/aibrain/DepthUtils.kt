package com.example.aibrain

import android.graphics.ImageFormat
import android.media.Image
import android.util.Base64
import com.google.ar.core.Frame

object DepthUtils {
    const val DEPTH_FORMAT = "DEPTH16_MM_U16LE"
    const val DEPTH_INVALID_VALUE = 0

    data class DepthFrame(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val scaleMPerUnit: Float = 0.001f,
        val isRaw: Boolean,
        val format: String = DEPTH_FORMAT,
        val invalidValue: Int = DEPTH_INVALID_VALUE,
    )

    data class AcquiredDepthImage(
        val image: Image,
        val isRaw: Boolean,
    )

    fun tryAcquireDepth16(frame: Frame): AcquiredDepthImage? {
        val raw = runCatching { frame.acquireRawDepthImage16Bits() }.getOrNull()
        if (raw != null) return AcquiredDepthImage(raw, isRaw = true)

        val smoothed = runCatching { frame.acquireDepthImage16Bits() }.getOrNull()
        return smoothed?.let { AcquiredDepthImage(it, isRaw = false) }
    }

    fun copyDepth16(image: Image, isRaw: Boolean): DepthFrame {
        require(image.format == ImageFormat.DEPTH16) { "Expected DEPTH16 image, got format=${image.format}" }
        require(image.planes.isNotEmpty()) { "DEPTH16 image has no planes" }
        val plane = image.planes[0]
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val src = plane.buffer.duplicate()
        val base = src.position()
        val limit = src.limit()
        if (pixelStride < 2) {
            throw IllegalStateException("Invalid DEPTH16 pixelStride=$pixelStride")
        }
        if (rowStride < width * pixelStride) {
            throw IllegalStateException("Invalid DEPTH16 strides: rowStride=$rowStride, width=$width, pixelStride=$pixelStride")
        }
        val out = ByteArray(width * height * 2)

        if (pixelStride == 2) {
            var dst = 0
            val rowBytes = width * 2
            for (y in 0 until height) {
                val rowStart = base + y * rowStride
                if (rowStart < 0 || rowStart + rowBytes > limit) {
                    throw IllegalStateException("DEPTH16 row out of bounds: rowStart=$rowStart rowBytes=$rowBytes limit=$limit")
                }
                src.position(rowStart)
                src.get(out, dst, rowBytes)
                dst += rowBytes
            }
        } else {
            var dst = 0
            for (y in 0 until height) {
                val rowBase = y * rowStride
                for (x in 0 until width) {
                    val idx = base + rowBase + x * pixelStride
                    if (idx < 0 || idx + 2 > limit) {
                        throw IllegalStateException("DEPTH16 index out of bounds: idx=$idx limit=$limit")
                    }
                    out[dst] = src.get(idx)
                    out[dst + 1] = src.get(idx + 1)
                    dst += 2
                }
            }
        }

        return DepthFrame(bytes = out, width = width, height = height, isRaw = isRaw)
    }

    fun depthBytesToBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
