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
import java.util.*
import kotlin.math.exp
import kotlin.math.ln

class CameraController(private val context: Context, private val textureView: TextureView) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null
    private var rawImageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val mainUIHandler = Handler(Looper.getMainLooper())
    
    var isIsoAuto = true
    var currentIso = 100
    var isSpeedAuto = true
    var currentExposureTime = 1000000000L / 60
    var isAfAuto = true
    var currentFocusDistance = 0f
    var currentWb = CaptureRequest.CONTROL_AWB_MODE_AUTO
    var currentEv = 0
    var isAELocked = false
    var isFlashOn = false

    var minIso = 50; var maxIso = 3200
    var minExp = 1000000L; var maxExp = 1000000000L
    var maxFocus = 10f
    var minEv = -3; var maxEv = 3
    var evStep = 1f 
    var isManualSupported = false
    private var sensorArraySize: Rect? = null
    
    private var lastBlackLevel = 64f
    private var lastRGain = 1.0f
    private var lastGGain = 1.0f
    private var lastBGain = 1.0f
    private var lastCcm = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    
    private var lastJpegOrientation = 0
    private var lastIsoCapture = 100
    private var lastExpTimeCapture = 0L
    private var lastAperture = 0f
    private var lastFocalLength = 0f
    
    var supportedWbModes = intArrayOf(CaptureRequest.CONTROL_AWB_MODE_AUTO)
    val wbLabelsMap = mapOf(
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

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val focusDist = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
            val expTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            mainUIHandler.post {
                onCameraStateUpdated?.invoke(focusDist, iso, expTime)
            }
        }
    }

    fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) { }
    }

    fun switchRatio(to16by9: Boolean) {
        if (this.is16by9 == to16by9) return
        this.is16by9 = to16by9
        closeCamera()
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

    @SuppressLint("MissingPermission")
    fun openCamera() {
        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return

            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            isManualSupported = capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
            
            sensorArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { minIso = it.lower; maxIso = it.upper }
            chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { minExp = it.lower; maxExp = it.upper }
            chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.let { maxFocus = it }
            
            chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let { minEv = it.lower; maxEv = it.upper }
            chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.let { evStep = it.toFloat() }

            chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.let { modes ->
                val list = modes.toMutableList()
                list.remove(CaptureRequest.CONTROL_AWB_MODE_AUTO)
                supportedWbModes = intArrayOf(CaptureRequest.CONTROL_AWB_MODE_AUTO) + list.toIntArray()
            }

            val imgWidth = if (is16by9) 1920 else 1440
            val imgHeight = 1080

            imageReader = ImageReader.newInstance(imgWidth, imgHeight, ImageFormat.JPEG, 1)
            imageReader?.setOnImageAvailableListener({ reader ->
                backgroundHandler?.post {
                    try {
                        val image = reader.acquireLatestImage()
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        image.close()
                        
                        saveJpegToGallery(bytes)
                    } catch (e: Exception) { Log.e("Camera", "JPEG Save Error", e) }
                }
            }, backgroundHandler)

            val rawSizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.RAW_SENSOR)
            if (!rawSizes.isNullOrEmpty()) {
                val rawWidth = rawSizes[0].width
                val rawHeight = rawSizes[0].height
                
                rawImageReader = ImageReader.newInstance(rawWidth, rawHeight, ImageFormat.RAW_SENSOR, 1).apply {
                    setOnImageAvailableListener({ reader ->
                        backgroundHandler?.post {
                            try {
                                val image = reader.acquireLatestImage()
                                val plane = image.planes[0]
                                val buffer = plane.buffer
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)
                                
                                val outWidth = image.width / 2
                                val outHeight = image.height / 2
                                val outBytes = ByteArray(outWidth * outHeight * 4)

                                val bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
                                val outBuffer = java.nio.ByteBuffer.wrap(outBytes)
                                bitmap.copyPixelsFromBuffer(outBuffer)
                                
                                saveBitmapToGallery(bitmap)
                                image.close()
                            } catch (e: Exception) { Log.e("Camera", "RAW Save Error", e) }
                        }
                    }, backgroundHandler)
                }
            }

            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) { Log.e("Camera", "Open Camera Error", e) }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            startPreview()
        }
        override fun onDisconnected(camera: CameraDevice) { closeCamera() }
        override fun onError(camera: CameraDevice, error: Int) { closeCamera() }
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

        val surfaces = if (rawSurface != null) listOf(surface, imageSurface, rawSurface) else listOf(surface, imageSurface)

        try {
            previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder!!.addTarget(surface)

            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    applySettings()
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, backgroundHandler)
        } catch (e: CameraAccessException) { }
    }

    fun triggerFocusAtPoint(x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        val sensorRect = sensorArraySize ?: return
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return

        try {
            val percentX = x / viewWidth
            val percentY = y / viewHeight
            
            val centerX = (percentX * sensorRect.width()).toInt()
            val centerY = (percentY * sensorRect.height()).toInt()
            val halfW = sensorRect.width() / 10
            val halfH = sensorRect.height() / 10
            
            val focusRect = Rect(
                maxOf(sensorRect.left, centerX - halfW),
                maxOf(sensorRect.top, centerY - halfH),
                minOf(sensorRect.right, centerX + halfW),
                minOf(sensorRect.bottom, centerY + halfH)
            )
            
            val meteringRect = MeteringRectangle(focusRect, MeteringRectangle.METERING_WEIGHT_MAX)
            val meteringArray = arrayOf(meteringRect)

            builder.set(CaptureRequest.CONTROL_AF_REGIONS, meteringArray)
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, meteringArray)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            session.capture(builder.build(), null, backgroundHandler)
            
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            session.setRepeatingRequest(builder.build(), previewCallback, backgroundHandler)
        } catch (e: Exception) { }
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
        return supportedWbModes[index]
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
        } catch (e: Exception) { }
    }

    fun takePicture(deviceRotation: Int) {
        val camera = cameraDevice; val session = captureSession
        if (camera == null || session == null) {
            mainUIHandler.post { Toast.makeText(context, "กล้องยังไม่พร้อม", Toast.LENGTH_SHORT).show() }
            return
        }

        try {
            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            imageReader?.surface?.let { captureBuilder.addTarget(it) }
            
            val characteristics = cameraManager.getCameraCharacteristics(camera.id)
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val surfaceRotation = when (deviceRotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            lastJpegOrientation = (sensorOrientation - surfaceRotation + 360) % 360
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, lastJpegOrientation)
            
            if (isAfAuto) captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            else { captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF); captureBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, currentFocusDistance) }

            if (!isIsoAuto || !isSpeedAuto) {
                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                if (!isIsoAuto) captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
                if (!isSpeedAuto) captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureTime)
            } else {
                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                captureBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentEv)
            }
            
            captureBuilder.set(CaptureRequest.FLASH_MODE, if (isFlashOn) CaptureRequest.FLASH_MODE_SINGLE else CaptureRequest.FLASH_MODE_OFF)
            captureBuilder.set(CaptureRequest.CONTROL_AWB_MODE, currentWb)
            captureBuilder.set(CaptureRequest.CONTROL_AE_LOCK, isAELocked)
            captureBuilder.set(CaptureRequest.CONTROL_AWB_LOCK, isAELocked)
            
            session.stopRepeating()
            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    super.onCaptureCompleted(session, request, result)
                    
                    result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let {
                        lastRGain = it.red; lastGGain = it.greenEven; lastBGain = it.blue
                    }
                    result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let {
                        for (i in 0..8) lastCcm[i] = it.getElement(i % 3, i / 3).toFloat()
                    }
                    lastBlackLevel = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.get(0) ?: 64f
                    lastIsoCapture = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
                    lastExpTimeCapture = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
                    lastAperture = result.get(CaptureResult.LENS_APERTURE) ?: 0f
                    lastFocalLength = result.get(CaptureResult.LENS_FOCAL_LENGTH) ?: 0f
                    
                    startPreview()
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) { }
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

        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
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
        val filename = "PRO_RAW_$timeStamp.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ProCamera")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let { finalUri ->
            context.contentResolver.openOutputStream(finalUri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
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
                
                exif.setAttribute(ExifInterface.TAG_F_NUMBER, lastAperture.toString())
                exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "${(lastFocalLength * 1000).toInt()}/1000")
                
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
        } catch (e: Exception) { Log.e("Camera", "EXIF Write Error", e) }
    }
}

