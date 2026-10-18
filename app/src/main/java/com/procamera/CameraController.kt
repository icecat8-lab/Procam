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
