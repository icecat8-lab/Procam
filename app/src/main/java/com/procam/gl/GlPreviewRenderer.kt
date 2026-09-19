package com.procam.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Camera2 SurfaceTexture -> OpenGL preview renderer.
 *
 * Orientation follows Android Camera2's documented relative-rotation formula:
 * sensorOrientation - displayRotation * sign, where sign=1 for front and -1 for back.
 */
class GlPreviewRenderer(
    private val onSurfaceReady: (Surface) -> Unit,
    private val onSurfaceDetached: () -> Unit = {},
    private val bufferWidth: Int,
    private val bufferHeight: Int,
    private val sensorOrientation: Int = 0,
    private val isFrontCamera: Boolean = false
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    companion object {
        private const val VERTEX_SHADER = """#version 300 es
in vec4 aPosition;
in vec2 aTexCoord;
uniform mat4 uTexMatrix;
uniform vec2 uScale;
uniform float uRotation;
uniform float uMirror;
out vec2 vTexCoord;

vec2 rotateUv(vec2 uv, float degrees) {
    if (degrees < 45.0 || degrees >= 315.0) return uv;
    if (degrees < 135.0) return vec2(1.0 - uv.y, uv.x);
    if (degrees < 225.0) return vec2(1.0 - uv.x, 1.0 - uv.y);
    return vec2(uv.y, 1.0 - uv.x);
}

void main() {
    gl_Position = vec4(aPosition.x * uScale.x, aPosition.y * uScale.y, aPosition.z, aPosition.w);
    vec2 uv = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
    uv = rotateUv(uv, uRotation);
    if (uMirror > 0.5) uv.x = 1.0 - uv.x;
    vTexCoord = uv;
}
"""

        private const val FRAGMENT_SHADER = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
in vec2 vTexCoord;
uniform samplerExternalOES uTexture;
uniform sampler2D uLut;
uniform float uLutEnabled;
uniform float uLutSize;
uniform float uBrightness;
uniform float uContrast;
uniform float uSaturation;
uniform float uTemperature;
out vec4 fragColor;

vec3 sampleLut(float r, float g, float bIndex) {
    float x = (bIndex * uLutSize + r * (uLutSize - 1.0) + 0.5) / (uLutSize * uLutSize);
    float y = (g * (uLutSize - 1.0) + 0.5) / uLutSize;
    return texture(uLut, vec2(x, y)).rgb;
}

vec3 applyLut(vec3 c) {
    float blue = c.b * (uLutSize - 1.0);
    float b0 = floor(blue);
    float b1 = min(b0 + 1.0, uLutSize - 1.0);
    float bf = fract(blue);
    return mix(sampleLut(c.r, c.g, b0), sampleLut(c.r, c.g, b1), bf);
}

void main() {
    vec3 color = texture(uTexture, vTexCoord).rgb;
    color = (color - 0.5) * uContrast + 0.5 + uBrightness;
    float lum = dot(color, vec3(0.2126, 0.7152, 0.0722));
    color = mix(vec3(lum), color, uSaturation);
    color.r += uTemperature * 0.05;
    color.b -= uTemperature * 0.05;
    if (uLutEnabled > 0.5 && uLutSize > 0.0) color = applyLut(clamp(color, 0.0, 1.0));
    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
"""
        private const val FLOAT_SIZE = 4
    }

    var lutEnabled = false
    var lutTextureId = 0
    var lutSize = 0f
    var brightness = 0f
    var contrast = 1f
    var saturation = 1f
    var temperature = 0f

    @Volatile private var displayRotationDegrees = 0
    @Volatile private var viewWidth = 0
    @Volatile private var viewHeight = 0
    @Volatile private var released = false

    private var glSurfaceView: GLSurfaceView? = null
    private var program = 0
    private var externalTextureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private var aPosition = -1
    private var aTexCoord = -1
    private var uTexMatrix = -1
    private var uTexture = -1
    private var uLut = -1
    private var uLutEnabled = -1
    private var uLutSize = -1
    private var uBrightness = -1
    private var uContrast = -1
    private var uSaturation = -1
    private var uTemperature = -1
    private var uScale = -1
    private var uRotation = -1
    private var uMirror = -1
    private var scaleX = 1f
    private var scaleY = 1f
    private val texMatrix = FloatArray(16)

    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * FLOAT_SIZE)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 0f, 1f, -1f, 1f, 0f, 1f, 1f, 1f, 0f, 1f))
            position(0)
        }

    private val texCoords: FloatBuffer = ByteBuffer.allocateDirect(4 * 2 * FLOAT_SIZE)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
            position(0)
        }

    fun bindGlSurfaceView(view: GLSurfaceView) {
        glSurfaceView = view
        view.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY)
    }

    fun setDisplayRotationDegrees(degrees: Int) {
        displayRotationDegrees = ((degrees % 360) + 360) % 360
        recalculateScale()
    }

    private fun previewRotationDegrees(): Int {
        val sign = if (isFrontCamera) 1 else -1
        return ((sensorOrientation - displayRotationDegrees * sign) % 360 + 360) % 360
    }

    private fun recalculateScale() {
        val w = viewWidth
        val h = viewHeight
        if (w <= 0 || h <= 0 || bufferWidth <= 0 || bufferHeight <= 0) return
        val rotation = previewRotationDegrees()
        val rotated = rotation == 90 || rotation == 270
        val cameraAspect = if (rotated) bufferHeight.toFloat() / bufferWidth else bufferWidth.toFloat() / bufferHeight
        val viewAspect = w.toFloat() / h.toFloat()
        if (viewAspect > cameraAspect) {
            scaleX = cameraAspect / viewAspect
            scaleY = 1f
        } else {
            scaleX = 1f
            scaleY = viewAspect / cameraAspect
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        val view = glSurfaceView ?: return
        val rotation = when (view.display?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        if (rotation != displayRotationDegrees) displayRotationDegrees = rotation
        view.requestRender()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // GLSurfaceView may recreate the EGL context while keeping the same Renderer.
        // The old SurfaceTexture/Surface belongs to the old GL context and must not be reused.
        if (surfaceTexture != null || cameraSurface != null || externalTextureId != 0 || program != 0) {
            releaseGlResources()
        }
        // Tell Camera2 that the previous producer surface is no longer valid before
        // publishing the new Surface created for this EGL context.
        onSurfaceDetached()
        released = false
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (program == 0) return

        aPosition = GLES30.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES30.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES30.glGetUniformLocation(program, "uTexMatrix")
        uTexture = GLES30.glGetUniformLocation(program, "uTexture")
        uLut = GLES30.glGetUniformLocation(program, "uLut")
        uLutEnabled = GLES30.glGetUniformLocation(program, "uLutEnabled")
        uLutSize = GLES30.glGetUniformLocation(program, "uLutSize")
        uBrightness = GLES30.glGetUniformLocation(program, "uBrightness")
        uContrast = GLES30.glGetUniformLocation(program, "uContrast")
        uSaturation = GLES30.glGetUniformLocation(program, "uSaturation")
        uTemperature = GLES30.glGetUniformLocation(program, "uTemperature")
        uScale = GLES30.glGetUniformLocation(program, "uScale")
        uRotation = GLES30.glGetUniformLocation(program, "uRotation")
        uMirror = GLES30.glGetUniformLocation(program, "uMirror")

        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        externalTextureId = ids[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(externalTextureId)
        st.setDefaultBufferSize(bufferWidth, bufferHeight)
        st.setOnFrameAvailableListener(this)
        surfaceTexture = st
        val surface = Surface(st)
        cameraSurface = surface
        recalculateScale()
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        onSurfaceReady(surface)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        GLES30.glViewport(0, 0, width, height)
        recalculateScale()
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
        } catch (_: RuntimeException) {
            return
        }

        val view = glSurfaceView
        if (view != null) {
            val rotation = when (view.display?.rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            if (rotation != displayRotationDegrees) {
                displayRotationDegrees = rotation
                recalculateScale()
            }
        }

        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId)
        GLES30.glUniform1i(uTexture, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lutTextureId)
        GLES30.glUniform1i(uLut, 1)
        GLES30.glUniform1f(uLutEnabled, if (lutEnabled && lutTextureId != 0) 1f else 0f)
        GLES30.glUniform1f(uLutSize, if (lutSize > 0f) lutSize else 1f)
        GLES30.glUniform1f(uBrightness, brightness)
        GLES30.glUniform1f(uContrast, contrast)
        GLES30.glUniform1f(uSaturation, saturation)
        GLES30.glUniform1f(uTemperature, temperature)
        GLES30.glUniform2f(uScale, scaleX, scaleY)
        GLES30.glUniform1f(uRotation, previewRotationDegrees().toFloat())
        GLES30.glUniform1f(uMirror, if (isFrontCamera) 1f else 0f)
        GLES30.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)

        GLES30.glEnableVertexAttribArray(aPosition)
        GLES30.glVertexAttribPointer(aPosition, 4, GLES30.GL_FLOAT, false, 0, vertices)
        GLES30.glEnableVertexAttribArray(aTexCoord)
        GLES30.glVertexAttribPointer(aTexCoord, 2, GLES30.GL_FLOAT, false, 0, texCoords)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(aPosition)
        GLES30.glDisableVertexAttribArray(aTexCoord)
    }

    /** Must be called on the GL thread. */
    private fun releaseGlResources() {
        if (released) return
        released = true
        try { surfaceTexture?.setOnFrameAvailableListener(null) } catch (_: Throwable) {}
        try { surfaceTexture?.release() } catch (_: Throwable) {}
        surfaceTexture = null
        try { cameraSurface?.release() } catch (_: Throwable) {}
        cameraSurface = null
        if (externalTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(externalTextureId), 0)
            externalTextureId = 0
        }
        if (program != 0) {
            GLES30.glDeleteProgram(program)
            program = 0
        }
    }

    fun release() {
        val view = glSurfaceView
        if (view != null) {
            try { view.queueEvent { releaseGlResources() } } catch (_: Throwable) {}
        }
        glSurfaceView = null
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = loadShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        if (vertex == 0) return 0
        val fragment = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        if (fragment == 0) {
            GLES30.glDeleteShader(vertex)
            return 0
        }
        val programId = GLES30.glCreateProgram()
        GLES30.glAttachShader(programId, vertex)
        GLES30.glAttachShader(programId, fragment)
        GLES30.glLinkProgram(programId)
        val status = IntArray(1)
        GLES30.glGetProgramiv(programId, GLES30.GL_LINK_STATUS, status, 0)
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        if (status[0] == 0) {
            GLES30.glDeleteProgram(programId)
            return 0
        }
        return programId
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }
}
