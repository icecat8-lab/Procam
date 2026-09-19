package com.procam.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class GlPreviewRenderer(
    private val onSurfaceReady: (Surface) -> Unit,
    private val bufferWidth: Int,
    private val bufferHeight: Int
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            uniform vec2 uScale;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition.x * uScale.x, aPosition.y * uScale.y, aPosition.z, aPosition.w);
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            uniform float uLutEnabled;
            uniform sampler2D uLut;
            uniform float uLutSize;
            uniform float uBrightness;
            uniform float uContrast;
            uniform float uSaturation;
            uniform float uTemperature;

            vec3 sampleLut(float r, float g, float b) {
                float x = (b * uLutSize + r * (uLutSize - 1.0) + 0.5) / (uLutSize * uLutSize);
                float y = (g * (uLutSize - 1.0) + 0.5) / uLutSize;
                return texture2D(uLut, vec2(x, y)).rgb;
            }

            vec3 applyLut(vec3 c) {
                float blue = c.b * (uLutSize - 1.0);
                float b0 = floor(blue);
                float b1 = min(b0 + 1.0, uLutSize - 1.0);
                float bf = fract(blue);
                vec3 c0 = sampleLut(c.r, c.g, b0);
                vec3 c1 = sampleLut(c.r, c.g, b1);
                return mix(c0, c1, bf);
            }

            void main() {
                vec3 color = texture2D(uTexture, vTexCoord).rgb;
                color = (color - 0.5) * uContrast + 0.5 + uBrightness;
                float lum = dot(color, vec3(0.2126, 0.7152, 0.0722));
                color = mix(vec3(lum), color, uSaturation);
                color.r += uTemperature * 0.05;
                color.b -= uTemperature * 0.05;
                if (uLutEnabled > 0.5 && uLutSize > 0.0) {
                    color = applyLut(clamp(color, 0.0, 1.0));
                }
                gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
            }
        """

        private const val FLOAT_SIZE = 4
    }

    var lutEnabled: Boolean = false
    var lutTextureId: Int = 0
    var lutSize: Float = 0f
    var brightness: Float = 0f
    var contrast: Float = 1f
    var saturation: Float = 1f
    var temperature: Float = 0f

    @Volatile private var scaleX: Float = 1f
    @Volatile private var scaleY: Float = 1f

    private var glSurfaceView: GLSurfaceView? = null

    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexMatrix = 0
    private var uTexture = 0
    private var uLutEnabled = 0
    private var uLut = 0
    private var uLutSize = 0
    private var uBrightness = 0
    private var uContrast = 0
    private var uSaturation = 0
    private var uTemperature = 0
    private var uScale = 0

    private var externalTextureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null

    private val vertices: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 4 * FLOAT_SIZE)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(floatArrayOf(
                -1f, -1f, 0f, 1f,
                 1f, -1f, 0f, 1f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 0f, 1f
            ))
            position(0)
        }

    private val texCoords: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 2 * FLOAT_SIZE)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(floatArrayOf(
                0f, 0f,
                1f, 0f,
                0f, 1f,
                1f, 1f
            ))
            position(0)
        }

    private val texMatrix = FloatArray(16)

    fun bindGlSurfaceView(view: GLSurfaceView) {
        glSurfaceView = view
        view.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY)
    }

    fun setViewSize(viewWidth: Int, viewHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        val camAspect = bufferWidth.toFloat() / bufferHeight.toFloat()
        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
        if (camAspect > viewAspect) {
            scaleX = viewAspect / camAspect
            scaleY = 1f
        } else {
            scaleX = 1f
            scaleY = camAspect / viewAspect
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        glSurfaceView?.requestRender()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uTexture = GLES20.glGetUniformLocation(program, "uTexture")
        uLutEnabled = GLES20.glGetUniformLocation(program, "uLutEnabled")
        uLut = GLES20.glGetUniformLocation(program, "uLut")
        uLutSize = GLES20.glGetUniformLocation(program, "uLutSize")
        uBrightness = GLES20.glGetUniformLocation(program, "uBrightness")
        uContrast = GLES20.glGetUniformLocation(program, "uContrast")
        uSaturation = GLES20.glGetUniformLocation(program, "uSaturation")
        uTemperature = GLES20.glGetUniformLocation(program, "uTemperature")
        uScale = GLES20.glGetUniformLocation(program, "uScale")

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        externalTextureId = texIds[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(externalTextureId)
        st.setDefaultBufferSize(bufferWidth, bufferHeight)
        st.setOnFrameAvailableListener(this)
        surfaceTexture = st
        val surf = Surface(st)
        cameraSurface = surf

        GLES20.glClearColor(0f, 0f, 0f, 1f)

        onSurfaceReady(surf)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        setViewSize(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
        } catch (_: Throwable) {
            return
        }

        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId)
        GLES20.glUniform1i(uTexture, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId)
        GLES20.glUniform1i(uLut, 1)

        GLES20.glUniform1f(uLutEnabled, if (lutEnabled && lutTextureId != 0) 1f else 0f)
        GLES20.glUniform1f(uLutSize, if (lutSize > 0f) lutSize else 1f)
        GLES20.glUniform1f(uBrightness, brightness)
        GLES20.glUniform1f(uContrast, contrast)
        GLES20.glUniform1f(uSaturation, saturation)
        GLES20.glUniform1f(uTemperature, temperature)
        GLES20.glUniform2f(uScale, scaleX, scaleY)

        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)

        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 4, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoords)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
    }

    fun release() {
        try { surfaceTexture?.setOnFrameAvailableListener(null) } catch (_: Throwable) {}
        try { surfaceTexture?.release() } catch (_: Throwable) {}
        surfaceTexture = null
        try { cameraSurface?.release() } catch (_: Throwable) {}
        cameraSurface = null
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        glSurfaceView = null
    }

    private fun createProgram(vs: String, fs: String): Int {
        val v = loadShader(GLES20.GL_VERTEX_SHADER, vs)
        val f = loadShader(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        return p
    }

    private fun loadShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }
}
