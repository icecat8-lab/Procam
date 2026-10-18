package com.procamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

class CameraController(
    context: Context,
    private val textureView: TextureView
) {
    private val context: Context = context.applicationContext
    private val cameraManager: CameraManager =
        this.context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var previewSurface: Surface? = null
    private var imageReader: ImageReader? = null
    private var rawImageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    @Volatile private var backgroundHandler: Handler? = null
    private val mainUIHandler = Handler(Looper.getMainLooper())

    private val builderLock = Any()

    private val saveExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ProCamSaver").apply { isDaemon = true }
    }

    @Volatile var isIsoAuto = true
    @Volatile var currentIso = 100
    @Volatile var isSpeedAuto = true
    @Volatile var currentExposureTime = 1_000_000_000L / 60
    @Volatile var isAfAuto = true
    @Volatile var currentFocusDistance = 0f
    @Volatile var currentWb = CaptureRequest.CONTROL_AWB_MODE_AUTO
    @Volatile var currentEv = 0
    @Volatile var isAELocked = false
    @Volatile var isFlashOn = false
    @Volatile var rawEnabled = true

    var minIso = 50
    var maxIso = 3200
    var minExp = 1_000_000L
    var maxExp = 1_000_000_000L
    var maxFocus = 0f
    var minEv = -3
    var maxEv = 3
    var evStep = 1f
    var isManualSupported = false
    var flashAvailable = false

    var supportedWbModes = intArrayOf(CaptureRequest.CONTROL_AWB_MODE_AUTO)
    private val wbLabelsMap = mapOf(
        CaptureRequest.CONTROL_AWB_MODE_AUTO to "AUTO",
        CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "Cloud",
        CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT to "Sun",
        CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT to "Fluor",
        CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT to "Incan",
        CaptureRequest.CONTROL_AWB_MODE_SHADE to "Shade",
        CaptureRequest.CONTROL_AWB_MODE_TWILIGHT to "Twilgt",
        CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT to "Warm"
    )

    var is16by9 = false
    var onImageSaved: ((Uri) -> Unit)? = null
    var onCameraStateUpdated: ((Float, Int, Long) -> Unit)? = null

    private var sensorArraySize: Rect? = null
    private var maxAfRegions = 0
    private var maxAeRegions = 0
    private var cameraId: String? = null
    private var sensorOrientation = 90
    private var previewWidth = 1440
    private var previewHeight = 1080
    private var captureWidth = 1440
    private var captureHeight = 1080
    private var cfaArrangement = 0
    private var sensorWhiteLevel = 1023
    private var staticBlackLevel = 64f
    private var afModes = intArrayOf()
    private var aeModes = intArrayOf()
    private var aeLockAvailable = false
    private var awbLockAvailable = false
    private var rawStreamActive = false
    private var rawRetryAttempted = false

    @Volatile private var wantOpen = false
    @Volatile private var opening = false
    @Volatile private var reopenAfterClose = false
    private val openGeneration = AtomicInteger(0)

    @Volatile private var cachedFocusDist = 0f
    @Volatile private var cachedIso = 0
    @Volatile private var cachedExpTime = 0L
    @Volatile private var lastUiUpdateMs = 0L
    private val pendingUiUpdate = AtomicBoolean(false)
    private val uiUpdateRunnable = Runnable {
        pendingUiUpdate.set(false)
        onCameraStateUpdated?.invoke(cachedFocusDist, cachedIso, cachedExpTime)
    }

    @Volatile private var tapFocusActive = false
    private val revertTapFocus = Runnable {
        if (!isAELocked) {
            tapFocusActive = false
            applySettings()
        }
    }

    private enum class StillState { IDLE, WAITING_PRECAPTURE, WAITING_NON_PRECAPTURE }

    private val stillLock = Any()
    private var stillState = StillState.IDLE
    private var pendingStillRequest: CaptureRequest? = null
    private val precaptureTimeout = Runnable { fireStillCapture() }

    private class ShotMeta(
        val iso: Int,
        val exposureNs: Long,
        val aperture: Float,
        val focalLength: Float,
        val blackLevel: Float,
        val rGain: Float,
        val gGain: Float,
        val bGain: Float,
        val ccm: FloatArray
    )

    private class PendingShot(val expectRaw: Boolean, val jpegOrientation: Int) {
        var jpeg: ByteArray? = null
        var rawImage: Image? = null
        var meta: ShotMeta? = null
    }

    private val shotLock = Any()
    private var pendingShot: PendingShot? = null
    private val busy = AtomicBoolean(false)
    private val shotWatchdog = Runnable {
        failShot(R.string.toast_capture_failed)
        restorePreviewAfterShot()
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { cachedFocusDist = it }
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { cachedIso = it }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { cachedExpTime = it }

            handlePrecaptureProgress(result)

            val now = SystemClock.elapsedRealtime()
            if (now - lastUiUpdateMs >= 100 && !pendingUiUpdate.getAndSet(true)) {
                lastUiUpdateMs = now
                mainUIHandler.post(uiUpdateRunnable)
            }
        }
    }

    fun startBackgroundThread() {
        if (backgroundThread != null) return
        val thread = HandlerThread("CameraBackground")
        thread.start()
        backgroundThread = thread
        backgroundHandler = Handler(thread.looper)
    }

    fun stopBackgroundThread() {
        val thread = backgroundThread ?: return
        backgroundThread = null
        backgroundHandler = null
        thread.quitSafely()
        try {
            thread.join(500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun switchRatio(to16by9: Boolean) {
        if (this.is16by9 == to16by9) return
        this.is16by9 = to16by9
        rawRetryAttempted = false

        if (cameraDevice == null && !opening) {
            openCamera()
            return
        }

        reopenAfterClose = true
        closeInternal()
    }

    fun closeCamera() {
        reopenAfterClose = false
        closeInternal()
    }

    fun release() {
        onImageSaved = null
        onCameraStateUpdated = null
        mainUIHandler.removeCallbacksAndMessages(null)
        closeCamera()
        stopBackgroundThread()
        saveExecutor.shutdown()
    }

    private fun closeInternal() {
        wantOpen = false
        opening = false
        openGeneration.incrementAndGet()

        mainUIHandler.removeCallbacks(precaptureTimeout)
        mainUIHandler.removeCallbacks(revertTapFocus)
        synchronized(stillLock) {
            stillState = StillState.IDLE
            pendingStillRequest = null
        }
        failShot(0, force = true)

        val session = captureSession
        captureSession = null
        try {
            session?.stopRepeating()
            session?.abortCaptures()
        } catch (_: Exception) {
        }
        try {
            session?.close()
        } catch (_: Exception) {
        }

        val device = cameraDevice
        cameraDevice = null
        try {
            device?.close()
        } catch (_: Exception) {
        }

        synchronized(builderLock) { previewRequestBuilder = null }
        releaseReaders()
        try {
            previewSurface?.release()
        } catch (_: Exception) {
        }
        previewSurface = null
        rawStreamActive = false
        tapFocusActive = false
    }

    private fun releaseReaders() {
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null
        try {
            rawImageReader?.close()
        } catch (_: Exception) {
        }
        rawImageReader = null
    }

    private fun selectBackCameraId(): String? {
        var fallback: String? = null
        for (id in cameraManager.cameraIdList) {
            val c = cameraManager.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            if (caps != null &&
                caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)
            ) return id
            if (fallback == null) fallback = id
        }
        return fallback
    }

    @SuppressLint("MissingPermission")
    fun openCamera() {
        if (cameraDevice != null || opening) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) return

        startBackgroundThread()
        val handler = backgroundHandler ?: return

        try {
            val id = selectBackCameraId() ?: run {
                toast(R.string.toast_no_camera)
                return
            }
            cameraId = id
            val chars = cameraManager.getCameraCharacteristics(id)
            readCharacteristics(chars)

            releaseReaders()
            createImageReaders(chars)

            wantOpen = true
            opening = true
            val generation = openGeneration.incrementAndGet()
            cameraManager.openCamera(id, DeviceCallback(generation), handler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Open Camera Error", e)
            opening = false
            wantOpen = false
            releaseReaders()
            toast(R.string.toast_open_camera_failed)
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission revoked", e)
            opening = false
            wantOpen = false
            releaseReaders()
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Open Camera Error", e)
            opening = false
            wantOpen = false
            releaseReaders()
            toast(R.string.toast_open_camera_failed)
        }
    }

    private fun readCharacteristics(chars: CameraCharacteristics) {
        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        isManualSupported = capabilities?.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
        ) == true

        sensorArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let {
            minIso = it.lower; maxIso = it.upper
        }
        chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
            minExp = it.lower
            maxExp = it.upper.coerceAtMost(10_000_000_000L)
        }
        maxFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let {
            minEv = it.lower; maxEv = it.upper
        }
        chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.let {
            evStep = it.toFloat()
        }

        maxAfRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        maxAeRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        aeModes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: intArrayOf()
        aeLockAvailable = chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
        awbLockAvailable = chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true

        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        cfaArrangement = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
        sensorWhiteLevel = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
        chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { p ->
            staticBlackLevel = (p.getOffsetForIndex(0, 0) + p.getOffsetForIndex(1, 0) +
                p.getOffsetForIndex(0, 1) + p.getOffsetForIndex(1, 1)) / 4f
        }

        val wb = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
        val presets = wb?.filter {
            it != CaptureRequest.CONTROL_AWB_MODE_AUTO && wbLabelsMap.containsKey(it)
        } ?: emptyList()
        supportedWbModes = intArrayOf(CaptureRequest.CONTROL_AWB_MODE_AUTO) + presets.toIntArray()
    }

    private fun createImageReaders(chars: CameraCharacteristics) {
        val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val targetRatio = if (is16by9) 16f / 9f else 4f / 3f

        val chosenCapture = chooseSize(
            streamMap?.getOutputSizes(ImageFormat.JPEG), targetRatio, 40_000_000L
        ) ?: Size(if (is16by9) 1920 else 1440, 1080)
        captureWidth = chosenCapture.width
        captureHeight = chosenCapture.height

        val chosenPreview = chooseSize(
            streamMap?.getOutputSizes(SurfaceTexture::class.java), targetRatio, 2_200_000L
        ) ?: Size(if (is16by9) 1920 else 1440, 1080)
        previewWidth = chosenPreview.width
        previewHeight = chosenPreview.height

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener({ reader -> onJpegAvailable(reader) }, backgroundHandler)
        }

        rawRetryAttempted = false
        rawStreamActive = false
        val hasRawCapability = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
        val rawSizes = streamMap?.getOutputSizes(ImageFormat.RAW_SENSOR)

        if (rawEnabled && hasRawCapability && !rawSizes.isNullOrEmpty()) {
            val rawSize = rawSizes.maxByOrNull { it.width.toLong() * it.height } ?: rawSizes[0]
            rawStreamActive = true
            rawImageReader = ImageReader.newInstance(
                rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 1
            ).apply {
                setOnImageAvailableListener({ reader -> onRawAvailable(reader) }, backgroundHandler)
            }
        }
    }

    private inner class DeviceCallback(private val generation: Int) : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            if (generation != openGeneration.get() || !wantOpen) {
                camera.close()
                return
            }
            opening = false
            cameraDevice = camera
            startPreview(generation)
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            if (generation == openGeneration.get()) closeInternal()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            if (generation == openGeneration.get()) {
                closeInternal()
                toast(R.string.toast_camera_error, error)
            }
        }

        override fun onClosed(camera: CameraDevice) {
            if (reopenAfterClose) {
                reopenAfterClose = false
                openCamera()
            }
        }
    }

    fun reapplyPreviewBufferSize() {
        try {
            textureView.surfaceTexture?.setDefaultBufferSize(previewWidth, previewHeight)
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun startPreview(generation: Int) {
        val camera = cameraDevice ?: return
        val texture = textureView.surfaceTexture
        if (texture == null) {
            Log.e(TAG, "SurfaceTexture not available")
            return
        }
        val jpegSurface = imageReader?.surface ?: return

        texture.setDefaultBufferSize(previewWidth, previewHeight)
        try {
            previewSurface?.release()
        } catch (_: Exception) {
        }
        val surface = Surface(texture)
        previewSurface = surface

        val rawSurface = rawImageReader?.surface
        val surfaces = ArrayList<Surface>(3)
        surfaces.add(surface)
        surfaces.add(jpegSurface)
        if (rawSurface != null) surfaces.add(rawSurface)
        val includesRaw = rawSurface != null
        rawStreamActive = includesRaw

        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            synchronized(builderLock) { previewRequestBuilder = builder }

            camera.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (generation != openGeneration.get() || cameraDevice !== camera) {
                            session.close()
                            return
                        }
                        captureSession = session
                        applySettings()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session config failed (raw=$includesRaw)")
                        if (generation != openGeneration.get()) return
                        if (includesRaw && !rawRetryAttempted) {
                            rawRetryAttempted = true
                            rawStreamActive = false
                            try {
                                rawImageReader?.close()
                            } catch (_: Exception) {
                            }
                            rawImageReader = null
                            startPreview(generation)
                        } else {
                            toast(R.string.toast_open_camera_failed)
                        }
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "startPreview error", e)
            toast(R.string.toast_open_camera_failed)
        }
    }

    private fun autoAfMode(): Int = when {
        tapFocusActive && afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ->
            CaptureRequest.CONTROL_AF_MODE_AUTO
        afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) -> CaptureRequest.CONTROL_AF_MODE_AUTO
        else -> CaptureRequest.CONTROL_AF_MODE_OFF
    }

    private fun supportsAlwaysFlash(): Boolean =
        flashAvailable && aeModes.contains(CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)

    private fun flashWanted(): Boolean {
        val manualExposure = isManualSupported && (!isIsoAuto || !isSpeedAuto)
        return isFlashOn && flashAvailable && !manualExposure
    }

    private fun configureBuilder(
        b: CaptureRequest.Builder,
        useFlash: Boolean,
        stillCapture: Boolean = false
    ) {
        val manualFocus = !isAfAuto && isManualSupported && maxFocus > 0f
        if (manualFocus) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, currentFocusDistance.coerceIn(0f, maxFocus))
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, autoAfMode())
        }

        val manualExposure = isManualSupported && (!isIsoAuto || !isSpeedAuto)
        if (manualExposure) {
            val iso = if (!isIsoAuto) currentIso else (if (cachedIso > 0) cachedIso else max(minIso, 100))
            val exposure = if (!isSpeedAuto) currentExposureTime
            else (if (cachedExpTime > 0) cachedExpTime else 1_000_000_000L / 60)
            val clampedExp = exposure.coerceIn(minExp, maxExp)
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso.coerceIn(minIso, maxIso))
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, clampedExp)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, max(clampedExp, 33_333_333L))
            b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        } else {
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentEv)
            if (useFlash && supportsAlwaysFlash()) {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            } else {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                b.set(
                    CaptureRequest.FLASH_MODE,
                    if (stillCapture && useFlash && flashAvailable) CaptureRequest.FLASH_MODE_SINGLE
                    else CaptureRequest.FLASH_MODE_OFF
                )
            }
        }

        b.set(CaptureRequest.CONTROL_AWB_MODE, currentWb)
        b.set(CaptureRequest.CONTROL_AE_LOCK, isAELocked && aeLockAvailable && !manualExposure)
        b.set(CaptureRequest.CONTROL_AWB_LOCK, isAELocked && awbLockAvailable)
    }

    fun applySettings() {
        val session = captureSession ?: return
        try {
            synchronized(builderLock) {
                val builder = previewRequestBuilder ?: return
                configureBuilder(builder, useFlash = flashWanted())
                session.setRepeatingRequest(builder.build(), previewCallback, backgroundHandler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "applySettings error", e)
        }
    }

    fun triggerFocusAtPoint(x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        val sensorRect = sensorArraySize ?: return
        val session = captureSession ?: return

        try {
            val du = (x / viewWidth).coerceIn(0f, 1f)
            val dv = (y / viewHeight).coerceIn(0f, 1f)

            val rotated = sensorOrientation == 90 || sensorOrientation == 270
            val fullW = if (rotated) sensorRect.height().toFloat() else sensorRect.width().toFloat()
            val fullH = if (rotated) sensorRect.width().toFloat() else sensorRect.height().toFloat()

            val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
            val fullAspect = fullW / fullH
            val cropW = if (fullAspect > viewAspect) fullH * viewAspect else fullW
            val cropH = if (fullAspect > viewAspect) fullH else fullW / viewAspect
            val cropLeft = (fullW - cropW) / 2f
            val cropTop = (fullH - cropH) / 2f

            val dx = (cropLeft + du * cropW) / fullW
            val dy = (cropTop + dv * cropH) / fullH

            val su: Float
            val sv: Float
            when (sensorOrientation) {
                90 -> { su = dy; sv = 1f - dx }
                270 -> { su = 1f - dy; sv = dx }
                180 -> { su = 1f - dx; sv = 1f - dy }
                else -> { su = dx; sv = dy }
            }

            val centerX = (sensorRect.left + su * sensorRect.width()).toInt()
            val centerY = (sensorRect.top + sv * sensorRect.height()).toInt()
            val halfW = (sensorRect.width() / 10).coerceAtLeast(1)
            val halfH = (sensorRect.height() / 10).coerceAtLeast(1)

            val focusRect = Rect(
                (centerX - halfW).coerceIn(sensorRect.left, sensorRect.right - 1),
                (centerY - halfH).coerceIn(sensorRect.top, sensorRect.bottom - 1),
                (centerX + halfW).coerceIn(sensorRect.left + 1, sensorRect.right),
                (centerY + halfH).coerceIn(sensorRect.top + 1, sensorRect.bottom)
            )
            val meteringRect = MeteringRectangle(focusRect, MeteringRectangle.METERING_WEIGHT_MAX)

            val manualFocus = !isAfAuto && isManualSupported && maxFocus > 0f
            val canAutoFocus = !manualFocus && afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)

            synchronized(builderLock) {
                val builder = previewRequestBuilder ?: return
                if (maxAfRegions > 0 && !manualFocus) {
                    builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRect))
                }
                if (maxAeRegions > 0) {
                    builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRect))
                }
                tapFocusActive = canAutoFocus
                configureBuilder(builder, useFlash = flashWanted())

                if (canAutoFocus) {
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                    session.capture(builder.build(), previewCallback, backgroundHandler)
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                }
                session.setRepeatingRequest(builder.build(), previewCallback, backgroundHandler)
            }

            mainUIHandler.removeCallbacks(revertTapFocus)
            if (canAutoFocus) mainUIHandler.postDelayed(revertTapFocus, 3500)
        } catch (e: Exception) {
            Log.e(TAG, "triggerFocusAtPoint error", e)
        }
    }

    fun calculateIso(progress: Int): Int {
        if (progress == 0) return minIso
        return minIso + ((maxIso - minIso) * ((progress - 1) / 99f)).toInt()
    }

    fun calculateSpeed(progress: Int): Long {
        if (progress == 0) return minExp
        val logMin = ln(minExp.toDouble())
        val logMax = ln(maxExp.toDouble())
        return exp(logMin + ((logMax - logMin) * ((progress - 1) / 99f))).toLong()
    }

    fun calculateFocus(progress: Int): Float {
        if (progress == 0) return 0f
        return maxFocus * ((progress - 1) / 99f)
    }

    fun getWbMode(progress: Int): Int {
        if (progress == 0 || supportedWbModes.size <= 1) return CaptureRequest.CONTROL_AWB_MODE_AUTO
        val maxIdx = supportedWbModes.size - 1
        val index = 1 + ((progress - 1) * (maxIdx - 1) / 99)
        return supportedWbModes[index.coerceIn(1, maxIdx)]
    }

    fun getWbLabel(mode: Int): String = wbLabelsMap[mode] ?: "WB"

    fun calculateEv(progress: Int): Int {
        if (progress == 50) return 0
        return if (progress < 50) {
            minEv + ((progress / 49f) * -minEv).toInt()
        } else {
            (maxEv * ((progress - 50) / 50f)).toInt()
        }
    }

    fun getEvLabel(progress: Int): String {
        val evInt = calculateEv(progress)
        if (evInt == 0) return "0.0"
        val evFloat = evInt * evStep
        val sign = if (evFloat > 0) "+" else ""
        return String.format(Locale.US, "%s%.1f", sign, evFloat)
    }

    private fun chooseSize(sizes: Array<Size>?, targetRatio: Float, maxPixels: Long): Size? {
        if (sizes.isNullOrEmpty()) return null
        val matching = sizes.filter {
            abs(it.width.toFloat() / it.height.toFloat() - targetRatio) < 0.05f
        }
        val pool = if (matching.isNotEmpty()) matching else sizes.toList()
        return pool.filter { it.width.toLong() * it.height <= maxPixels }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: pool.minByOrNull { it.width.toLong() * it.height }
    }

    fun takePicture(deviceRotation: Int) {
        val camera = cameraDevice
        val session = captureSession
        val jpegSurface = imageReader?.surface
        if (camera == null || session == null || jpegSurface == null || previewRequestBuilder == null) {
            toast(R.string.toast_camera_not_ready)
            return
        }
        if (!busy.compareAndSet(false, true)) {
            toast(R.string.toast_busy)
            return
        }

        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(jpegSurface)
            val useFlash = flashWanted()
            val rawSurface = if (rawStreamActive && !useFlash) rawImageReader?.surface else null
            if (rawSurface != null) builder.addTarget(rawSurface)

            val surfaceRotation = when (deviceRotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            val jpegOrientation = (sensorOrientation - surfaceRotation + 360) % 360
            builder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
            builder.set(CaptureRequest.JPEG_QUALITY, 95.toByte())

            configureBuilder(builder, useFlash, stillCapture = true)

            synchronized(builderLock) {
                previewRequestBuilder?.get(CaptureRequest.CONTROL_AE_REGIONS)?.let {
                    builder.set(CaptureRequest.CONTROL_AE_REGIONS, it)
                }
                previewRequestBuilder?.get(CaptureRequest.CONTROL_AF_REGIONS)?.let {
                    builder.set(CaptureRequest.CONTROL_AF_REGIONS, it)
                }
            }

            val shot = PendingShot(expectRaw = rawSurface != null, jpegOrientation = jpegOrientation)
            synchronized(shotLock) { pendingShot = shot }

            val exposureMs = max(if (isSpeedAuto) cachedExpTime else currentExposureTime, 0L) / 1_000_000L
            mainUIHandler.removeCallbacks(shotWatchdog)
            mainUIHandler.postDelayed(shotWatchdog, 10_000L + exposureMs * 2)

            val request = builder.build()
            if (useFlash && supportsAlwaysFlash() && !isAELocked) {
                startPrecapture(session, request)
            } else {
                session.capture(request, stillCaptureCallback, backgroundHandler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "takePicture error", e)
            failShot(R.string.toast_capture_failed, force = true)
        }
    }

    private fun startPrecapture(session: CameraCaptureSession, stillRequest: CaptureRequest) {
        synchronized(stillLock) {
            pendingStillRequest = stillRequest
            stillState = StillState.WAITING_PRECAPTURE
        }
        mainUIHandler.removeCallbacks(precaptureTimeout)
        mainUIHandler.postDelayed(precaptureTimeout, 2000)
        try {
            synchronized(builderLock) {
                val builder = previewRequestBuilder
                if (builder == null) {
                    fireStillCapture()
                    return
                }
                configureBuilder(builder, useFlash = flashWanted())
                builder.set(
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START
                )
                session.capture(builder.build(), previewCallback, backgroundHandler)
                builder.set(
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE
                )
                session.setRepeatingRequest(builder.build(), previewCallback, backgroundHandler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "precapture error — shooting without it", e)
            fireStillCapture()
        }
    }

    private fun handlePrecaptureProgress(result: CaptureResult) {
        val state = synchronized(stillLock) { stillState }
        if (state == StillState.IDLE) return
        val ae = result.get(CaptureResult.CONTROL_AE_STATE)
        if (state == StillState.WAITING_PRECAPTURE) {
            if (ae == null ||
                ae == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
            ) {
                synchronized(stillLock) {
                    if (stillState == StillState.WAITING_PRECAPTURE) {
                        stillState = StillState.WAITING_NON_PRECAPTURE
                    }
                }
            }
        } else if (state == StillState.WAITING_NON_PRECAPTURE) {
            if (ae == null || ae != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                fireStillCapture()
            }
        }
    }

    private fun fireStillCapture() {
        val request = synchronized(stillLock) {
            if (stillState == StillState.IDLE) {
                null
            } else {
                stillState = StillState.IDLE
                val r = pendingStillRequest
                pendingStillRequest = null
                r
            }
        } ?: return

        mainUIHandler.removeCallbacks(precaptureTimeout)
        val session = captureSession
        if (session == null) {
            failShot(R.string.toast_capture_failed, force = true)
            return
        }
        try {
            session.capture(request, stillCaptureCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "capture after precapture error", e)
            failShot(R.string.toast_capture_failed, force = true)
            restorePreviewAfterShot()
        }
    }

    private val stillCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val meta = buildShotMeta(result)
            synchronized(shotLock) {
                val shot = pendingShot
                if (shot != null) {
                    shot.meta = meta
                    completeShotIfReady(shot)
                }
            }
            restorePreviewAfterShot()
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            Log.e(TAG, "Still capture failed, reason=${failure.reason}")
            failShot(R.string.toast_capture_failed)
            restorePreviewAfterShot()
        }
    }

    private fun restorePreviewAfterShot() {
        synchronized(stillLock) {
            stillState = StillState.IDLE
            pendingStillRequest = null
        }
        val session = captureSession
        if (session != null && flashWanted() && supportsAlwaysFlash()) {
            try {
                synchronized(builderLock) {
                    val builder = previewRequestBuilder
                    if (builder != null) {
                        configureBuilder(builder, useFlash = true)
                        builder.set(
                            CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                            CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL
                        )
                        session.capture(builder.build(), previewCallback, backgroundHandler)
                        builder.set(
                            CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                            CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "precapture cancel error", e)
            }
        }
        applySettings()
    }

    private fun buildShotMeta(result: TotalCaptureResult): ShotMeta {
        val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val rGain = gains?.red ?: 1f
        val gGain = if (gains != null) (gains.greenEven + gains.greenOdd) / 2f else 1f
        val bGain = gains?.blue ?: 1f

        val ccm = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        if (transform != null) {
            for (row in 0..2) {
                for (col in 0..2) {
                    ccm[row * 3 + col] = transform.getElement(col, row).toFloat()
                }
            }
        }

        val dynamicBlack = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
        val black = if (dynamicBlack != null && dynamicBlack.isNotEmpty()) {
            dynamicBlack.average().toFloat()
        } else {
            staticBlackLevel
        }

        return ShotMeta(
            iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: cachedIso,
            exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: cachedExpTime,
            aperture = result.get(CaptureResult.LENS_APERTURE) ?: 0f,
            focalLength = result.get(CaptureResult.LENS_FOCAL_LENGTH) ?: 0f,
            blackLevel = black,
            rGain = rGain,
            gGain = gGain,
            bGain = bGain,
            ccm = ccm
        )
    }

    private fun onJpegAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            Log.e(TAG, "acquire JPEG failed", e)
            null
        } ?: return

        val bytes = try {
            val buffer = image.planes[0].buffer
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } finally {
            image.close()
        }

        synchronized(shotLock) {
            val shot = pendingShot ?: return
            shot.jpeg = bytes
            completeShotIfReady(shot)
        }
    }

    private fun onRawAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            Log.e(TAG, "acquire RAW failed", e)
            null
        } ?: return

        var accepted = false
        synchronized(shotLock) {
            val shot = pendingShot
            if (shot != null && shot.expectRaw && shot.rawImage == null) {
                shot.rawImage = image
                accepted = true
                completeShotIfReady(shot)
            }
        }
        if (!accepted) image.close()
    }

    private fun completeShotIfReady(shot: PendingShot) {
        val jpeg = shot.jpeg ?: return
        val meta = shot.meta ?: return
        if (shot.expectRaw && shot.rawImage == null) return

        pendingShot = null
        val raw = shot.rawImage
        shot.rawImage = null
        mainUIHandler.removeCallbacks(shotWatchdog)
        try {
            saveExecutor.execute { runSave(jpeg, raw, meta, shot.jpegOrientation) }
        } catch (e: Exception) {
            Log.e(TAG, "save executor rejected task", e)
            raw?.close()
            busy.set(false)
        }
    }

    private fun failShot(messageRes: Int, force: Boolean = false) {
        var had = false
        synchronized(shotLock) {
            val shot = pendingShot
            if (shot != null) {
                had = true
                try {
                    shot.rawImage?.close()
                } catch (_: Exception) {
                }
                shot.rawImage = null
                pendingShot = null
            }
        }
        mainUIHandler.removeCallbacks(shotWatchdog)
        synchronized(stillLock) {
            stillState = StillState.IDLE
            pendingStillRequest = null
        }
        if (had || force) {
            busy.set(false)
            if (messageRes != 0 && had) toast(messageRes)
        }
    }

    private fun runSave(jpeg: ByteArray, raw: Image?, meta: ShotMeta, orientation: Int) {
        try {
            try {
                saveJpeg(jpeg)
            } catch (t: Throwable) {
                Log.e(TAG, "JPEG save error", t)
                toast(R.string.toast_save_failed)
            }
            if (raw != null) {
                try {
                    saveRawAsJpeg(raw, meta, orientation)
                } catch (t: Throwable) {
                    Log.e(TAG, "RAW save error", t)
                    toast(R.string.toast_save_failed)
                }
            }
        } finally {
            try {
                raw?.close()
            } catch (_: Exception) {
            }
            busy.set(false)
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    @Suppress("DEPRECATION")
    private fun createMediaStoreEntry(displayName: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ProCamera")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "ProCamera"
                )
                if (!dir.exists()) dir.mkdirs()
                put(MediaStore.MediaColumns.DATA, File(dir, displayName).absolutePath)
            }
        }
        return context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }

    private fun finishMediaStoreEntry(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        }
        mainUIHandler.post { onImageSaved?.invoke(uri) }
    }

    private fun deleteQuietly(uri: Uri) {
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
        }
    }

    private fun saveJpeg(bytes: ByteArray) {
        val uri = createMediaStoreEntry("PRO_JPG_${timestamp()}.jpg")
            ?: throw IOException("MediaStore insert returned null")
        try {
            val out = context.contentResolver.openOutputStream(uri)
                ?: throw IOException("openOutputStream returned null")
            out.use { it.write(bytes) }
            finishMediaStoreEntry(uri)
        } catch (e: Exception) {
            deleteQuietly(uri)
            throw e
        }
    }

    private fun saveRawAsJpeg(image: Image, meta: ShotMeta, orientation: Int) {
        val plane = image.planes[0]
        val width = image.width
        val height = image.height

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val tmp = File(context.cacheDir, "raw_${System.nanoTime()}.jpg")
        try {
            val ok = ImageProcessor().processRawPro(
                plane.buffer, bitmap, width, height, plane.rowStride,
                meta.blackLevel, meta.rGain, meta.gGain, meta.bGain,
                meta.ccm, cfaArrangement, sensorWhiteLevel
            )
            if (!ok) throw IOException("native demosaic failed")

            FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            writeExif(tmp, meta, orientation)

            val uri = createMediaStoreEntry("PRO_RAW_${timestamp()}.jpg")
                ?: throw IOException("MediaStore insert returned null")
            try {
                val out = context.contentResolver.openOutputStream(uri)
                    ?: throw IOException("openOutputStream returned null")
                out.use { o -> FileInputStream(tmp).use { it.copyTo(o) } }
                finishMediaStoreEntry(uri)
            } catch (e: Exception) {
                deleteQuietly(uri)
                throw e
            }
        } finally {
            bitmap.recycle()
            tmp.delete()
        }
    }

    private fun writeExif(file: File, meta: ShotMeta, orientation: Int) {
        try {
            val exif = ExifInterface(file.absolutePath)
            exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
            exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "ProCamera")
            if (meta.iso > 0) {
                exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, meta.iso.toString())
            }
            if (meta.exposureNs > 0) {
                exif.setAttribute(
                    ExifInterface.TAG_EXPOSURE_TIME,
                    String.format(Locale.US, "%.9f", meta.exposureNs / 1_000_000_000.0)
                )
            }
            if (meta.aperture > 0f) {
                exif.setAttribute(ExifInterface.TAG_F_NUMBER, String.format(Locale.US, "%.2f", meta.aperture))
            }
            if (meta.focalLength > 0f) {
                exif.setAttribute(
                    ExifInterface.TAG_FOCAL_LENGTH,
                    "${(meta.focalLength * 1000).toInt()}/1000"
                )
            }
            val datetime = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
            exif.setAttribute(ExifInterface.TAG_DATETIME, datetime)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, datetime)

            val orientationTag = when (orientation) {
                90 -> ExifInterface.ORIENTATION_ROTATE_90
                180 -> ExifInterface.ORIENTATION_ROTATE_180
                270 -> ExifInterface.ORIENTATION_ROTATE_270
                else -> ExifInterface.ORIENTATION_NORMAL
            }
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientationTag.toString())
            exif.saveAttributes()
        } catch (e: Exception) {
            Log.e(TAG, "EXIF write error", e)
        }
    }

    private fun toast(@StringRes resId: Int, vararg args: Any) {
        mainUIHandler.post {
            Toast.makeText(context, context.getString(resId, *args), Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "ProCamera"
    }
}
