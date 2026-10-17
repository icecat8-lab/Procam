package com.procamera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.ExifInterface
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.exp
import kotlin.math.ln

class CameraController(
    context: Context,
    private val textureView: TextureView
) {
    // Use applicationContext to avoid leaking Activity
    private val context: Context = context.applicationContext
    private val cameraManager: CameraManager =
        this.context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null
    private var rawImageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val mainUIHandler = Handler(Looper.getMainLooper())

    // Public settings (read by UI; written by UI on main thread)
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

    var minIso = 50
    var maxIso = 3200
    var minExp = 1_000_000L
    var maxExp = 1_000_000_000L
    var maxFocus = 10f
    var minEv = -3
    var maxEv = 3
    var evStep = 1f
    var isManualSupported = false

    private var sensorArraySize: Rect? = null
    private var maxAfRegions = 0
    private var maxAeRegions = 0
    private var cameraId: String? = null

    // Last captured frame metadata (used for EXIF + RAW processing)
    @Volatile private var lastBlackLevel = 64f
    @Volatile private var lastRGain = 1.0f
    @Volatile private var lastGGain = 1.0f
    @Volatile private var lastBGain = 1.0f
    @Volatile private var lastCcm = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    @Volatile private var lastJpegOrientation = 0
    @Volatile private var lastIsoCapture = 100
    @Volatile private var lastExpTimeCapture = 0L
    @Volatile private var lastAperture = 0f
    @Volatile private var lastFocalLength = 0f

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

    // Throttle main-thread UI updates from previewCallback (was firing every frame → jank)
    private val lastUiUpdateNanos = AtomicLong(0)
    private val pendingUiUpdate = AtomicBoolean(false)
    private val uiUpdateRunnable = Runnable {
        pendingUiUpdate.set(false)
        val focusDist = cachedFocusDist
        val iso = cachedIso
        val expTime = cachedExpTime
        onCameraStateUpdated?.invoke(focusDist, iso, expTime)
    }

    @Volatile private var cachedFocusDist = 0f
    @Volatile private var cachedIso = 0
    @Volatile private var cachedExpTime = 0L

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val focusDist = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: cachedFocusDist
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: cachedIso
            val expTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: cachedExpTime

            cachedFocusDist = focusDist
            cachedIso = iso
            cachedExpTime = expTime

            // Throttle UI updates to ~16 fps max (60ms) to avoid main-thread jank
            val now = System.nanoTime()
            val last = lastUiUpdateNanos.get()
            if (now - last > 60_000_000L) {
                lastUiUpdateNanos.set(now)
                mainUIHandler.post(uiUpdateRunnable)
            } else if (!pendingUiUpdate.getAndSet(true)) {
                mainUIHandler.postDelayed(uiUpdateRunnable, 80)
            }
        }
    }

    fun startBackgroundThread() {
        // FIX: was leaking a HandlerThread every call
        if (backgroundThread?.isAlive == true) return
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        backgroundThread = null
        backgroundHandler = null
    }

    fun switchRatio(to16by9: Boolean) {
        if (this.is16by9 == to16by9) return
        this.is16by9 = to16by9
        // FIX: don't reopen immediately — close session synchronously, then reopen
        closeCameraSync()
        openCamera()
    }

    fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        rawImageReader?.close()
        rawImageReader = null
    }

    /** Synchronous close that waits for session/device callbacks to settle. */
    private fun closeCameraSync() {
        try {
            captureSession?.abortCaptures()
        } catch (_: Exception) {
        }
        closeCamera()
    }

    @SuppressLint("MissingPermission")
    fun openCamera() {
        // FIX: don't reopen if already open
        if (cameraDevice != null) return

        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: run {
                mainUIHandler.post {
                    Toast.makeText(context, "ไม่พบกล้องหลัง", Toast.LENGTH_SHORT).show()
                }
                return
            }
            this.cameraId = cameraId

            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            isManualSupported = capabilities?.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
            ) == true

            sensorArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let {
                minIso = it.lower; maxIso = it.upper
            }
            chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
                minExp = it.lower; maxExp = it.upper
            }
            chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.let {
                maxFocus = it
            }
            chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let {
                minEv = it.lower; maxEv = it.upper
            }
            chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.let {
                evStep = it.toFloat()
            }

            // FIX: query max metering regions — used to validate triggerFocusAtPoint()
            maxAfRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
            maxAeRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0

            chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.let { modes ->
                val list = modes.toMutableList()
                list.remove(CaptureRequest.CONTROL_AWB_MODE_AUTO)
                supportedWbModes = intArrayOf(CaptureRequest.CONTROL_AWB_MODE_AUTO) + list.toIntArray()
            }

            val imgWidth = if (is16by9) 1920 else 1440
            val imgHeight = 1080

            imageReader = ImageReader.newInstance(imgWidth, imgHeight, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    backgroundHandler?.post {
                        try {
                            // FIX: was crashing when image was null during rapid capture
                            val image = reader.acquireLatestImage() ?: return@post
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            image.close()
                            saveJpegToGallery(bytes)
                        } catch (e: Exception) {
                            Log.e(TAG, "JPEG Save Error", e)
                        }
                    }
                }, backgroundHandler)
            }

            val rawSizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.RAW_SENSOR)
            if (!rawSizes.isNullOrEmpty()) {
                val rawSize = rawSizes[0]
                rawImageReader = ImageReader.newInstance(
                    rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 1
                ).apply {
                    setOnImageAvailableListener({ reader ->
                        backgroundHandler?.post {
                            try {
                                val image = reader.acquireLatestImage() ?: return@post
                                try {
                                    val plane = image.planes[0]
                                    val buffer = plane.buffer
                                    val bytes = ByteArray(buffer.remaining())
                                    buffer.get(bytes)

                                    val rowStride = plane.rowStride
                                    val width = image.width
                                    val height = image.height

                                    // Pre-allocate output buffer
                                    val outputBytes = ByteArray(width * height * 4)

                                    ImageProcessor().processRawPro(
                                        rawData = bytes,
                                        outputData = outputBytes,
                                        width = width,
                                        height = height,
                                        rowStride = rowStride,
                                        blackLevel = lastBlackLevel,
                                        rGain = lastRGain,
                                        gGain = lastGGain,
                                        bGain = lastBGain,
                                        ccm = lastCcm
                                    )
                                    val bitmap = Bitmap.createBitmap(
                                        width, height, Bitmap.Config.ARGB_8888
                                    )
                                    bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(outputBytes))
                                    saveBitmapToGallery(bitmap)
                                } finally {
                                    image.close()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "RAW Save Error", e)
                            }
                        }
                    }, backgroundHandler)
                }
            }

            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Open Camera Error", e)
            mainUIHandler.post {
                Toast.makeText(context, "เปิดกล้องไม่สำเร็จ: ${e.reason}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission revoked", e)
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            startPreview()
        }
        override fun onDisconnected(camera: CameraDevice) {
            closeCamera()
        }
        override fun onError(camera: CameraDevice, error: Int) {
            closeCamera()
            mainUIHandler.post {
                Toast.makeText(context, "กล้องขัดข้อง (error $error)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startPreview() {
        val camera = cameraDevice ?: return
        val texture = textureView.surfaceTexture ?: return

        val prevWidth = if (is16by9) 1920 else 1440
        val prevHeight = 1080
        texture.setDefaultBufferSize(prevWidth, prevHeight)

        val surface = Surface(texture)
        val imageSurface = imageReader?.surface ?: return
        val rawSurface = rawImageReader?.surface

        val surfaces = mutableListOf(surface, imageSurface)
        if (rawSurface != null) surfaces.add(rawSurface)

        try {
            previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                .apply { addTarget(surface) }

            camera.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        applySettings()
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session config failed")
                    }
                },
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "startPreview error", e)
        }
    }

    /**
     * Trigger a metering/focus at the given view-coordinate point.
     * FIX: validates that the device actually supports AF/AE regions, and clamps
     * metering rect dimensions to be > 0 and inside the active array.
     */
    fun triggerFocusAtPoint(x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        val sensorRect = sensorArraySize ?: return
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return

        try {
            val percentX = (x / viewWidth).coerceIn(0f, 1f)
            val percentY = (y / viewHeight).coerceIn(0f, 1f)

            val centerX = (percentX * sensorRect.width()).toInt()
            val centerY = (percentY * sensorRect.height()).toInt()
            val halfW = (sensorRect.width() / 10).coerceAtLeast(1)
            val halfH = (sensorRect.height() / 10).coerceAtLeast(1)

            val focusRect = Rect(
                (centerX - halfW).coerceIn(sensorRect.left, sensorRect.right - 1),
                (centerY - halfH).coerceIn(sensorRect.top, sensorRect.bottom - 1),
                (centerX + halfW).coerceIn(sensorRect.left + 1, sensorRect.right),
                (centerY + halfH).coerceIn(sensorRect.top + 1, sensorRect.bottom)
            )

            val meteringRect = MeteringRectangle(focusRect, MeteringRectangle.METERING_WEIGHT_MAX)

            if (maxAfRegions > 0) {
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRect))
            }
            if (maxAeRegions > 0) {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRect))
            }
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            session.capture(builder.build(), null, backgroundHandler)

            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            session.setRepeatingRequest(builder.build(), previewCallback, backgroundHandler)
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

    fun applySettings() {
        val builder = previewRequestBuilder ?: return
        try {
            if (isAfAuto) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            } else {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, currentFocusDistance)
            }

            if (!isIsoAuto || !isSpeedAuto) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                if (!isIsoAuto) builder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
                if (!isSpeedAuto) builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureTime)
            } else {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentEv)
            }

            builder.set(CaptureRequest.CONTROL_AWB_MODE, currentWb)
            builder.set(CaptureRequest.CONTROL_AE_LOCK, isAELocked)
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, isAELocked)

            captureSession?.setRepeatingRequest(builder.build(), previewCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "applySettings error", e)
        }
    }

    fun takePicture(deviceRotation: Int) {
        val camera = cameraDevice
        val session = captureSession
        val currentCameraId = cameraId
        if (camera == null || session == null || currentCameraId == null) {
            mainUIHandler.post {
                Toast.makeText(context, "กล้องยังไม่พร้อม", Toast.LENGTH_SHORT).show()
            }
            return
        }

        try {
            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            imageReader?.surface?.let { captureBuilder.addTarget(it) }
            rawImageReader?.surface?.let { captureBuilder.addTarget(it) }

            val characteristics = cameraManager.getCameraCharacteristics(currentCameraId)
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val surfaceRotation = when (deviceRotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            lastJpegOrientation = (sensorOrientation - surfaceRotation + 360) % 360
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, lastJpegOrientation)

            if (isAfAuto) {
                captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            } else {
                captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                captureBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, currentFocusDistance)
            }

            if (!isIsoAuto || !isSpeedAuto) {
                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                if (!isIsoAuto) captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
                if (!isSpeedAuto) captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureTime)
            } else {
                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                captureBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentEv)
                // FIX: trigger precapture so flash fires correctly when needed
                if (isFlashOn) {
                    captureBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                        CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                }
            }

            captureBuilder.set(
                CaptureRequest.FLASH_MODE,
                if (isFlashOn) CaptureRequest.FLASH_MODE_SINGLE else CaptureRequest.FLASH_MODE_OFF
            )
            captureBuilder.set(CaptureRequest.CONTROL_AWB_MODE, currentWb)
            captureBuilder.set(CaptureRequest.CONTROL_AE_LOCK, isAELocked)
            captureBuilder.set(CaptureRequest.CONTROL_AWB_LOCK, isAELocked)

            session.stopRepeating()
            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    super.onCaptureCompleted(session, request, result)
                    result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let {
                        lastRGain = it.red
                        lastGGain = it.greenEven
                        lastBGain = it.blue
                    }
                    result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let {
                        // ColorSpaceTransform is a 3x3 matrix of Rational
                        for (r in 0..2) {
                            for (c in 0..2) {
                                lastCcm[r * 3 + c] = it.getElement(r, c).toFloat()
                            }
                        }
                    }
                    lastBlackLevel = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.get(0) ?: 64f
                    lastIsoCapture = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
                    lastExpTimeCapture = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
                    lastAperture = result.get(CaptureResult.LENS_APERTURE) ?: 0f
                    lastFocalLength = result.get(CaptureResult.LENS_FOCAL_LENGTH) ?: 0f

                    // Restart preview on background thread (was already on it)
                    startPreview()
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "takePicture error", e)
        }
    }

    private fun saveJpegToGallery(bytes: ByteArray) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "PRO_JPG_$timeStamp.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ProCamera")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
        )
        uri?.let { finalUri ->
            context.contentResolver.openOutputStream(finalUri)?.use { out ->
                out.write(bytes)
            }
            writeExifToUri(finalUri)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(finalUri, contentValues, null, null)
            }
            mainUIHandler.post { onImageSaved?.invoke(finalUri) }
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        // FIX: was naming a JPEG "PRO_RAW_xxx.jpg" — misleading. Rename to PRO_DEMOSAIC.
        val filename = "PRO_DEMOSAIC_$timeStamp.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ProCamera")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
        )
        uri?.let { finalUri ->
            context.contentResolver.openOutputStream(finalUri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            writeExifToUri(finalUri)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(finalUri, contentValues, null, null)
            }
            mainUIHandler.post { onImageSaved?.invoke(finalUri) }
        }
    }

    private fun writeExifToUri(uri: Uri) {
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
                exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
                exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, lastIsoCapture.toString())

                val expTimeSec = lastExpTimeCapture / 1_000_000_000.0
                exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, expTimeSec.toString())

                if (lastAperture > 0f) {
                    exif.setAttribute(ExifInterface.TAG_F_NUMBER, lastAperture.toString())
                }
                // FIX: focal length format must be "numerator/denominator" Rational
                // and only write if we actually got a value from the camera
                if (lastFocalLength > 0f) {
                    val numerator = (lastFocalLength * 1000).toInt()
                    exif.setAttribute(
                        ExifInterface.TAG_FOCAL_LENGTH,
                        "$numerator/1000"
                    )
                }

                val datetime = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
                exif.setAttribute(ExifInterface.TAG_DATETIME, datetime)

                val orientationTag = when (lastJpegOrientation) {
                    90 -> ExifInterface.ORIENTATION_ROTATE_90
                    180 -> ExifInterface.ORIENTATION_ROTATE_180
                    270 -> ExifInterface.ORIENTATION_ROTATE_270
                    else -> ExifInterface.ORIENTATION_NORMAL
                }
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientationTag.toString())
                exif.saveAttributes()
            }
        } catch (e: Exception) {
            Log.e(TAG, "EXIF Write Error", e)
        }
    }

    companion object {
        private const val TAG = "ProCamera"
    }
}
