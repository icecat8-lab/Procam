package com.procamera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var viewFinder: TextureView
    private lateinit var btnCapture: View
    private lateinit var btnRatio: TextView
    private lateinit var btnFlash: ImageView
    private lateinit var thumbnailView: ImageView
    private lateinit var focusRing: View
    
    private lateinit var sliderPanel: LinearLayout
    private lateinit var settingSeekbar: SeekBar
    private lateinit var btnAutoToggle: TextView

    private lateinit var btnAf: TextView
    private lateinit var btnIso: TextView
    private lateinit var btnWb: TextView
    private lateinit var btnS: TextView
    private lateinit var btnEv: TextView

    private lateinit var cameraController: CameraController
    private lateinit var viewModel: CameraViewModel

    private val mainHandler = Handler(Looper.getMainLooper())
    private val applySettingsRunnable = Runnable { cameraController.applySettings() }
    private var lastSettingTime = 0L

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) {
            startCameraIfReady()
        } else {
            Toast.makeText(this, "จำเป็นต้องใช้สิทธิ์กล้องถ่ายรูป", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewModel = ViewModelProvider(this)[CameraViewModel::class.java]

        initViews()
        setupCameraController()
        setupListeners()
        
        val touchHandler = TouchFocusHandler(this, viewFinder, focusRing, cameraController)
        viewFinder.setOnTouchListener(touchHandler)

        restoreSliderUI()
    }

    private fun initViews() {
        viewFinder = findViewById(R.id.view_finder)
        btnCapture = findViewById(R.id.btn_capture)
        btnRatio = findViewById(R.id.btn_ratio)
        btnFlash = findViewById(R.id.btn_flash)
        thumbnailView = findViewById(R.id.thumbnail_view)
        focusRing = findViewById(R.id.focus_ring)
        
        sliderPanel = findViewById(R.id.slider_panel)
        settingSeekbar = findViewById(R.id.setting_seekbar)
        btnAutoToggle = findViewById(R.id.btn_auto_toggle)

        btnAf = findViewById(R.id.btn_af)
        btnIso = findViewById(R.id.btn_iso)
        btnWb = findViewById(R.id.btn_wb)
        btnS = findViewById(R.id.btn_s)
        btnEv = findViewById(R.id.btn_ev)
    }

    private fun setupCameraController() {
        cameraController = CameraController(this, viewFinder)
        cameraController.onCameraStateUpdated = { focusDist, iso, speedNanos ->
            mainHandler.post {
                if (cameraController.isAfAuto) btnAf.text = String.format(Locale.US, "AF\n%.1f", focusDist)
                if (cameraController.isIsoAuto) btnIso.text = "ISO\n$iso"
                if (cameraController.isSpeedAuto) btnS.text = "S\n${formatShutterText(speedNanos)}"
            }
        }

        cameraController.onImageSaved = { uri ->
            viewModel.latestImageUri = uri
            mainHandler.post { thumbnailView.setImageURI(uri) }
        }
    }

    private fun setupListeners() {
        btnRatio.setOnClickListener {
            val newIs16by9 = !cameraController.is16by9
            btnRatio.text = if (newIs16by9) "16:9" else "4:3"
            adjustPreviewAspectRatio(newIs16by9)
            cameraController.switchRatio(newIs16by9)
        }
        
        btnFlash.setOnClickListener {
            cameraController.isFlashOn = !cameraController.isFlashOn
            btnFlash.setImageResource(if (cameraController.isFlashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
        }

        btnAutoToggle.setOnClickListener {
            val targetProgress = if (viewModel.activeMode == "EV") 50 else 0
            settingSeekbar.progress = targetProgress
            updateSliderUI(targetProgress, fromUser = true)
        }

        thumbnailView.setOnClickListener {
            viewModel.latestImageUri?.let { uri -> openGallery(uri) }
        }

        btnCapture.setOnClickListener {
            val rotation = windowManager.defaultDisplay.rotation
            cameraController.takePicture(rotation)
            
            viewFinder.alpha = 0.5f
            mainHandler.postDelayed({ viewFinder.alpha = 1.0f }, 100)
        }

        btnAf.setOnClickListener { toggleSlider("AF", viewModel.afProgress) }
        btnIso.setOnClickListener { toggleSlider("ISO", viewModel.isoProgress) }
        btnWb.setOnClickListener { toggleSlider("WB", viewModel.wbProgress) }
        btnS.setOnClickListener { toggleSlider("S", viewModel.sProgress) }
        btnEv.setOnClickListener { toggleSlider("EV", viewModel.evProgress) }

        settingSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) updateSliderUI(progress, fromUser = true)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                cameraController.applySettings()
            }
        })
    }

    private fun updateSliderUI(progress: Int, fromUser: Boolean) {
        val isAuto = if (viewModel.activeMode == "EV") progress == 50 else progress == 0
        btnAutoToggle.setTextColor(if (isAuto) Color.GREEN else Color.WHITE)

        when (viewModel.activeMode) {
            "AF" -> {
                viewModel.afProgress = progress
                cameraController.isAfAuto = isAuto
                if (!isAuto) {
                    val focus = cameraController.calculateFocus(progress)
                    cameraController.currentFocusDistance = focus
                    btnAf.text = String.format(Locale.US, "AF\n%.1f", focus)
                }
            }
            "ISO" -> {
                viewModel.isoProgress = progress
                cameraController.isIsoAuto = isAuto
                if (!isAuto) {
                    val iso = cameraController.calculateIso(progress)
                    cameraController.currentIso = iso
                    btnIso.text = "ISO\n$iso"
                }
            }
            "WB" -> {
                viewModel.wbProgress = progress
                val mode = cameraController.getWbMode(progress)
                cameraController.currentWb = mode
                btnWb.text = "WB\n${cameraController.getWbLabel(mode)}"
            }
            "S" -> {
                viewModel.sProgress = progress
                cameraController.isSpeedAuto = isAuto
                if (!isAuto) {
                    val speedNanos = cameraController.calculateSpeed(progress)
                    cameraController.currentExposureTime = speedNanos
                    btnS.text = "S\n${formatShutterText(speedNanos)}"
                }
            }
            "EV" -> {
                viewModel.evProgress = progress
                val ev = cameraController.calculateEv(progress)
                cameraController.currentEv = ev
                btnEv.text = "EV\n${cameraController.getEvLabel(progress)}"
            }
        }

        if (fromUser) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastSettingTime > 32) {
                cameraController.applySettings()
                lastSettingTime = currentTime
            } else {
                viewFinder.removeCallbacks(applySettingsRunnable)
                viewFinder.postDelayed(applySettingsRunnable, 32)
            }
        }
    }

    private fun toggleSlider(mode: String, currentProgress: Int) {
        if (viewModel.activeMode == mode) {
            sliderPanel.visibility = View.GONE
            viewModel.activeMode = "NONE"
            resetButtonColors()
            return
        }
        
        viewModel.activeMode = mode
        sliderPanel.visibility = View.VISIBLE
        
        resetButtonColors()
        when (mode) {
            "AF" -> btnAf.setTextColor(Color.GREEN)
            "ISO" -> btnIso.setTextColor(Color.GREEN)
            "WB" -> btnWb.setTextColor(Color.GREEN)
            "S" -> btnS.setTextColor(Color.GREEN)
            "EV" -> btnEv.setTextColor(Color.GREEN)
        }
        
        updateSliderUI(currentProgress, false)
        settingSeekbar.progress = currentProgress
    }

    private fun restoreSliderUI() {
        if (viewModel.activeMode != "NONE") {
            val mode = viewModel.activeMode
            viewModel.activeMode = "NONE"
            val progress = when (mode) {
                "AF" -> viewModel.afProgress
                "ISO" -> viewModel.isoProgress
                "WB" -> viewModel.wbProgress
                "S" -> viewModel.sProgress
                "EV" -> viewModel.evProgress
                else -> 0
            }
            viewFinder.post { toggleSlider(mode, progress) }
        }
    }

    private fun resetButtonColors() {
        val defaultColor = Color.WHITE
        btnAf.setTextColor(defaultColor)
        btnIso.setTextColor(defaultColor)
        btnWb.setTextColor(defaultColor)
        btnS.setTextColor(defaultColor)
        btnEv.setTextColor(defaultColor)
    }

    private fun adjustPreviewAspectRatio(is16by9: Boolean) {
        viewFinder.post {
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val layoutParams = viewFinder.layoutParams
            layoutParams.width = screenWidth
            layoutParams.height = if (is16by9) (screenWidth * 16) / 9 else (screenWidth * 4) / 3
            viewFinder.layoutParams = layoutParams
        }
    }

    private fun formatShutterText(nanos: Long): String {
        val sec = nanos / 1_000_000_000.0
        return if (sec >= 0.5) String.format(Locale.US, "%.1fs", sec) else "1/${(1.0 / sec).toInt()}"
    }

    private fun openGallery(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/*")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        try { startActivity(intent) } catch (e: Exception) {
            Toast.makeText(this, "ไม่พบแอปสำหรับดูรูปภาพ", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        cameraController.startBackgroundThread()
        if (viewFinder.isAvailable) {
            adjustPreviewAspectRatio(cameraController.is16by9)
            checkPermissionsAndStart()
        } else {
            viewFinder.surfaceTextureListener = surfaceTextureListener
        }
        checkThumbnailExists()
    }

    override fun onPause() {
        cameraController.closeCamera()
        cameraController.stopBackgroundThread()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)

        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startCameraIfReady()
        } else {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun startCameraIfReady() {
        if (viewFinder.isAvailable) {
            adjustPreviewAspectRatio(cameraController.is16by9)
            cameraController.openCamera()
        }
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            adjustPreviewAspectRatio(cameraController.is16by9)
            checkPermissionsAndStart()
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private fun checkThumbnailExists() {
        viewModel.latestImageUri?.let { uri ->
            try {
                val cursor = contentResolver.query(uri, null, null, null, null)
                if (cursor != null && cursor.moveToFirst()) {
                    cursor.close()
                    thumbnailView.setImageURI(uri)
                } else {
                    cursor?.close()
                    viewModel.latestImageUri = null
                    thumbnailView.setImageDrawable(null)
                }
            } catch (e: Exception) {
                viewModel.latestImageUri = null
                thumbnailView.setImageDrawable(null)
            }
        }
    }
}
