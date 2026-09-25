package com.leneo.ipdevices.util

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import java.io.ByteArrayOutputStream

object JpegEncoder {
    fun imageToJpeg(image: Image, quality: Int = 70): ByteArray? {
        return when (image.format) {
            ImageFormat.JPEG -> planeToBytes(image)
            ImageFormat.YUV_420_888 -> {
                val nv21 = yuv420888ToNv21(image) ?: return null
                nv21ToJpeg(nv21, image.width, image.height, quality)
            }
            else -> null
        }
    }

    fun nv21ToJpeg(nv21: ByteArray, width: Int, height: Int, quality: Int = 70): ByteArray? {
        return try {
            val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            if (!yuv.compressToJpeg(Rect(0, 0, width, height), quality.coerceIn(20, 100), out)) {
                return null
            }
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    fun extractJpeg(data: ByteArray, length: Int = data.size): ByteArray? {
        val n = length.coerceAtMost(data.size)
        if (n < 4 || data[0] != 0xFF.toByte() || data[1] != 0xD8.toByte()) return null
        var end = -1
        for (i in 2 until n - 1) {
            if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD9.toByte()) {
                end = i + 2
            }
        }
        if (end < 4) return null
        return data.copyOf(end)
    }

    fun rgbaToJpeg(rgba: ByteArray, width: Int, height: Int, quality: Int = 70): ByteArray? {
        return try {
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(rgba))
            val out = ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality.coerceIn(20, 100), out)
            bitmap.recycle()
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    private fun planeToBytes(image: Image): ByteArray {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }

    fun yuv420888ToNv21(image: Image): ByteArray? {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val ySize = width * height
        val nv21 = ByteArray(ySize + ySize / 2)

        copyPlane(yPlane.buffer, yPlane.rowStride, yPlane.pixelStride, width, height, nv21, 0)

        val chromaHeight = height / 2
        val chromaWidth = width / 2
        val vBuffer = vPlane.buffer
        val uBuffer = uPlane.buffer
        val vRowStride = vPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uPixelStride = uPlane.pixelStride

        var offset = ySize
        for (row in 0 until chromaHeight) {
            val vRowStart = row * vRowStride
            val uRowStart = row * uRowStride
            for (col in 0 until chromaWidth) {
                val vIndex = vRowStart + col * vPixelStride
                val uIndex = uRowStart + col * uPixelStride
                if (vIndex >= vBuffer.capacity() || uIndex >= uBuffer.capacity() || offset + 1 >= nv21.size) {
                    return nv21
                }
                nv21[offset++] = vBuffer.get(vIndex)
                nv21[offset++] = uBuffer.get(uIndex)
            }
        }
        return nv21
    }

    private fun copyPlane(
        buffer: java.nio.ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        out: ByteArray,
        offset: Int,
    ) {
        buffer.rewind()
        if (pixelStride == 1 && rowStride == width) {
            buffer.get(out, offset, width * height)
            return
        }
        var pos = offset
        for (row in 0 until height) {
            val rowStart = row * rowStride
            if (pixelStride == 1) {
                buffer.position(rowStart)
                buffer.get(out, pos, width)
                pos += width
            } else {
                for (col in 0 until width) {
                    out[pos++] = buffer.get(rowStart + col * pixelStride)
                }
            }
        }
    }
}
