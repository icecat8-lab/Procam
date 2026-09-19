package com.procam.gl

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import java.io.InputStream

object LutLoader {

    data class Lut3D(
        val size: Int,
        val data: FloatArray
    )

    fun loadFromAssets(context: Context, path: String): Lut3D? {
        return try {
            context.assets.open(path).use { parseCube(it) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseCube(input: InputStream): Lut3D? {
        var size = 0
        val values = ArrayList<Float>(4096 * 3)
        input.bufferedReader().useLines { lines ->
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                if (trimmed.startsWith("TITLE")) continue
                if (trimmed.startsWith("DOMAIN_MIN")) continue
                if (trimmed.startsWith("DOMAIN_MAX")) continue
                if (trimmed.startsWith("LUT_3D_SIZE")) {
                    size = trimmed.split("\\s+".toRegex())[1].toInt()
                    continue
                }
                val parts = trimmed.split("\\s+".toRegex())
                if (parts.size == 3) {
                    values.add(parts[0].toFloat())
                    values.add(parts[1].toFloat())
                    values.add(parts[2].toFloat())
                }
            }
        }
        if (size <= 0) return null
        return Lut3D(size, values.toFloatArray())
    }

    fun uploadToTexture(lut: Lut3D): Int {
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        val texId = texIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)

        val width = lut.size * lut.size
        val height = lut.size
        val pixels = IntArray(width * height)

        var i = 0
        for (b in 0 until lut.size) {
            for (g in 0 until lut.size) {
                for (r in 0 until lut.size) {
                    val rr = (lut.data[i] * 255f).toInt().coerceIn(0, 255)
                    val gg = (lut.data[i + 1] * 255f).toInt().coerceIn(0, 255)
                    val bb = (lut.data[i + 2] * 255f).toInt().coerceIn(0, 255)
                    pixels[g * width + b * lut.size + r] =
                        (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                    i += 3
                }
            }
        }

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()

        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        return texId
    }
}
