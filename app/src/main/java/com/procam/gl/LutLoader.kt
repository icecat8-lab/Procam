package com.procam.gl

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import java.io.InputStream
import kotlin.math.roundToInt

object LutLoader {

    data class Lut3D(
        val size: Int,
        val data: FloatArray
    )

    fun loadFromAssets(context: Context, path: String): Lut3D? =
        runCatching { context.assets.open(path).use(::parseCube) }.getOrNull()

    private fun parseCube(input: InputStream): Lut3D? {
        var size = 0
        val values = ArrayList<Float>()

        input.bufferedReader().useLines { lines ->
            lines.forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach

                val parts = line.split(Regex("\\s+"))
                when {
                    line.startsWith("TITLE", ignoreCase = true) ||
                        line.startsWith("DOMAIN_MIN", ignoreCase = true) ||
                        line.startsWith("DOMAIN_MAX", ignoreCase = true) -> Unit

                    line.startsWith("LUT_3D_SIZE", ignoreCase = true) -> {
                        if (parts.size >= 2) size = parts[1].toIntOrNull() ?: 0
                    }

                    parts.size == 3 -> {
                        val r = parts[0].toFloatOrNull()
                        val g = parts[1].toFloatOrNull()
                        val b = parts[2].toFloatOrNull()
                        if (r != null && g != null && b != null) {
                            values += r
                            values += g
                            values += b
                        }
                    }
                }
            }
        }

        if (size < 2) return null
        val expected = size * size * size * 3
        if (values.size != expected) return null

        return Lut3D(size, values.toFloatArray())
    }

    fun uploadToTexture(lut: Lut3D): Int {
        require(lut.size >= 2)
        require(lut.data.size == lut.size * lut.size * lut.size * 3)

        val width = lut.size * lut.size
        val height = lut.size
        val pixels = IntArray(width * height)

        var i = 0
        for (b in 0 until lut.size) {
            for (g in 0 until lut.size) {
                for (r in 0 until lut.size) {
                    val rr = (lut.data[i] * 255f).roundToInt().coerceIn(0, 255)
                    val gg = (lut.data[i + 1] * 255f).roundToInt().coerceIn(0, 255)
                    val bb = (lut.data[i + 2] * 255f).roundToInt().coerceIn(0, 255)
                    pixels[g * width + b * lut.size + r] =
                        (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                    i += 3
                }
            }
        }

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        val texId = texIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()

        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return texId
    }
}
