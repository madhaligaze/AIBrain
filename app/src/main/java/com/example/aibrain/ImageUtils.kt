package com.example.aibrain

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.util.Base64
import java.io.ByteArrayOutputStream

object ImageUtils {
    // Copy YUV_420_888 planes on the calling thread (fast), then convert to NV21/JPEG off the main thread.
    data class Yuv420Copy(
        val width: Int,
        val height: Int,
        val y: ByteArray,
        val u: ByteArray,
        val v: ByteArray,
        val yRowStride: Int,
        val yPixelStride: Int,
        val uRowStride: Int,
        val uPixelStride: Int,
        val vRowStride: Int,
        val vPixelStride: Int,
    )

    data class Nv21Frame(
        val data: ByteArray,
        val width: Int,
        val height: Int
    )

    fun imageToBase64(image: Image): String = convertYuvToJpegBase64(image)

    fun copyYuv420(image: Image): Yuv420Copy {
        require(image.format == ImageFormat.YUV_420_888) { "Expected YUV_420_888 image" }

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer.duplicate()
        val uBuf = uPlane.buffer.duplicate()
        val vBuf = vPlane.buffer.duplicate()

        val yBytes = ByteArray(yBuf.remaining()).also { yBuf.get(it) }
        val uBytes = ByteArray(uBuf.remaining()).also { uBuf.get(it) }
        val vBytes = ByteArray(vBuf.remaining()).also { vBuf.get(it) }

        return Yuv420Copy(
            width = image.width,
            height = image.height,
            y = yBytes,
            u = uBytes,
            v = vBytes,
            yRowStride = yPlane.rowStride,
            yPixelStride = yPlane.pixelStride,
            uRowStride = uPlane.rowStride,
            uPixelStride = uPlane.pixelStride,
            vRowStride = vPlane.rowStride,
            vPixelStride = vPlane.pixelStride,
        )
    }

    fun yuvCopyToNv21(copy: Yuv420Copy, swapUv: Boolean = false): Nv21Frame {
        val width = copy.width
        val height = copy.height
        val out = ByteArray(width * height + (width * height / 2))

        var dst = 0
        if (copy.yPixelStride == 1 && copy.yRowStride == width) {
            val n = width * height
            System.arraycopy(copy.y, 0, out, 0, n)
            dst = n
        } else {
            for (row in 0 until height) {
                val rowOffset = row * copy.yRowStride
                for (col in 0 until width) {
                    out[dst++] = copy.y[rowOffset + col * copy.yPixelStride]
                }
            }
        }

        val uvHeight = height / 2
        val uvWidth = width / 2
        for (row in 0 until uvHeight) {
            val uRowOffset = row * copy.uRowStride
            val vRowOffset = row * copy.vRowStride
            for (col in 0 until uvWidth) {
                val u = copy.u[uRowOffset + col * copy.uPixelStride]
                val v = copy.v[vRowOffset + col * copy.vPixelStride]
                if (swapUv) {
                    out[dst++] = u
                    out[dst++] = v
                } else {
                    out[dst++] = v
                    out[dst++] = u
                }
            }
        }

        return Nv21Frame(data = out, width = width, height = height)
    }

    fun yuv420ToNv21(image: Image, swapUv: Boolean = false): Nv21Frame {
        require(image.format == ImageFormat.YUV_420_888) { "Expected YUV_420_888 image" }

        // Backward compatible fast path: convert directly.
        // Prefer copyYuv420 + yuvCopyToNv21 when you want to move heavy work off the main thread.
        return yuvCopyToNv21(copyYuv420(image), swapUv)
    }

    fun copyToNv21(image: Image): Nv21Frame = yuv420ToNv21(image)

    fun nv21ToJpegBase64(nv21: ByteArray, width: Int, height: Int, quality: Int = 75): String {
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), quality.coerceIn(1, 100), out)

        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun convertYuvToJpegBase64(image: Image): String {
        val frame = yuv420ToNv21(image)
        return nv21ToJpegBase64(frame.data, frame.width, frame.height, 75)
    }
}
