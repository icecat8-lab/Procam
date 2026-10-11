package com.procamera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
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

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    var isIsoAuto = true
    var currentIso = 100
    var isSpeedAuto = true
    var currentExposureTime = 1000000000L / 60
    var isAfAuto = true
    var currentFocusDistance = 0f
    var currentWb = CaptureRequest.CONTROL_AWB_MODE_AUTO
    var currentEv = 0
    var isAELocked = false

    var minIso = 50; var maxIso = 3200
    var minExp = 1000000L; var maxExp = 1000000000L
    var maxFocus = 10f
    var isManualSupported = false
    
    var supportedWbModes = intArrayOf(CaptureRequest.CONTROL_AWB_MODE_AUTO)
    val wbLabelsMap = mapOf(
        CaptureRequest.CONTROL_AWB_MODE_AUTO to "AWB",
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

    @SuppressLint("MissingPermission")
    fun openCamera() {
        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return

            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            isManualSupported = capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
            
            chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { minIso = it.lower; maxIso = it.upper }
            chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { minExp = it.lower; maxExp = it.upper }
            chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.let { maxFocus = it }
            
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
                    val image = reader.acquireLatestImage()
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    saveImageToGallery(bytes)
                    image.close()
                }
            }, backgroundHandler)

            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) { }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            startPreview()
        }
        override fun onDisconnected(camera: CameraDevice) {
            camera.close(); cameraDevice = null
        }
        override fun onError(camera: CameraDevice, error: Int) {
            camera.close(); cameraDevice = null
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

        try {
            previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder!!.addTarget(surface)

            camera.createCaptureSession(listOf(surface, imageSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    applySettings()
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, backgroundHandler)
        } catch (e: CameraAccessException) { }
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
            
            // ล็อก AE/AWB
            builder.set(CaptureRequest.CONTROL_AE_LOCK, isAELocked)
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, isAELocked)

            captureSession?.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) { }
    }

    fun takePicture(deviceRotation: Int) {
        val camera = cameraDevice
        val session = captureSession
        
        if (camera == null || session == null) {
            Toast.makeText(context, "กล้องยังไม่พร้อม", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(imageReader!!.surface)
            
            val characteristics = cameraManager.getCameraCharacteristics(camera.id)
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val surfaceRotation = when (deviceRotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            val jpegOrientation = (sensorOrientation - surfaceRotation + 360) % 360
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)

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
            }

            captureBuilder.set(CaptureRequest.CONTROL_AWB_MODE, currentWb)
            captureBuilder.set(CaptureRequest.CONTROL_AE_LOCK, isAELocked)
            captureBuilder.set(CaptureRequest.CONTROL_AWB_LOCK, isAELocked)
            
            session.stopRepeating()
            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    super.onCaptureCompleted(session, request, result)
                    startPreview()
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) { }
    }

    private fun saveImageToGallery(bytes: ByteArray) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "PRO_$timeStamp.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ProCamera")
            }
        }

        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        try {
            uri?.let { finalUri ->
                context.contentResolver.openOutputStream(finalUri)?.use { it.write(bytes) }
                Handler(context.mainLooper).post { onImageSaved?.invoke(finalUri) }
            }
        } catch (e: Exception) { }
    }

    fun closeCamera() {
        captureSession?.close(); captureSession = null
        cameraDevice?.close(); cameraDevice = null
        imageReader?.close(); imageReader = null
    }
}
