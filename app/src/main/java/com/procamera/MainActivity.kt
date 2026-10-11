package com.procamera

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var viewFinder: TextureView
    private lateinit var btnCapture: View
    private lateinit var btnRatio: TextView
    private lateinit var thumbnailView: ImageView
    private lateinit var cameraController: CameraController

    private lateinit var sliderPanel: LinearLayout
    private lateinit var settingSeekbar: SeekBar
    private lateinit var btnAutoToggle: TextView

    private lateinit var btnAf: TextView
    private lateinit var btnIso: TextView
    private lateinit var btnS: TextView
    private lateinit var btnEv: TextView

    private var activeMode = "NONE"
    
    private var afProgress = 0; private var isoProgress = 0; private var sProgress = 0
    private var evProgress = 50

    private var latestImageUri: Uri? = null
    private var lastSettingTime = 0L
    private val applySettingsRunnable = Runnable { cameraController.applySettings() }
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.view_finder)
        btnCapture = findViewById(R.id.btn_capture)
        btnRatio = findViewById(R.id.btn_ratio)
        thumbnailView = findViewById(R.id.thumbnail_view)
        
        sliderPanel = findViewById(R.id.slider_panel)
        settingSeekbar = findViewById(R.id.setting_seekbar)
        btnAutoToggle = findViewById(R.id.btn_auto_toggle)

        btnAf = findViewById(R.id.btn_af); btnIso = findViewById(R.id.btn_iso)
        btnS = findViewById(R.id.btn_s); btnEv = findViewById(R.id.btn_ev)

        cameraController = CameraController(this, viewFinder)
        
        
        cameraController.minEv = -3
        cameraController.maxEv = 3

        btnRatio.setOnClickListener {
            val newIs16by9 = !cameraController.is16by9
            btnRatio.text = if (newIs16by9) "9:16" else "3:4"
            adjustPreviewAspectRatio(newIs16by9)
            cameraController.switchRatio(newIs16by9)
        }

        btnAutoToggle.setOnClickListener {
            val targetProgress = if (activeMode == "EV") 50 else 0
            settingSeekbar.progress = targetProgress
            updateSliderUI(targetProgress, true)
            cameraController.applySettings()
        }

        cameraController.onImageSaved = { uri ->
            latestImageUri = uri
            thumbnailView.setImageURI(uri)
        }

        thumbnailView.setOnClickListener {
            latestImageUri?.let { uri ->
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "image/jpeg")
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                startActivity(intent)
            }
        }

        btnCapture.setOnClickListener {
            val rotation = windowManager.defaultDisplay.rotation
            cameraController.takePicture(rotation)
            
            
            viewFinder.alpha = 0.5f
            mainHandler.postDelayed({
                viewFinder.alpha = 1.0f
            }, 150)
        }

        btnAf.setOnClickListener { toggleSlider("AF", afProgress) }
        btnIso.setOnClickListener { toggleSlider("ISO", isoProgress) }
        btnS.setOnClickListener { toggleSlider("S", sProgress) }
        btnEv.setOnClickListener { toggleSlider("EV", evProgress) }

        settingSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                updateSliderUI(progress, true)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                cameraController.applySettings()
            }
        })
    }

    private fun updateSliderUI(progress: Int, fromUser: Boolean) {
        val isAuto = if (activeMode == "EV") progress == 50 else progress == 0
        updateAutoButtonState(isAuto)

        when (activeMode) {
            "AF" -> {
                afProgress = progress
                cameraController.isAfAuto = isAuto
                if (isAuto) {
                    btnAf.text = "AF\nAUTO"
                    btnAf.setTextColor(Color.parseColor("#00FF00"))
                } else {
                    val focus = cameraController.calculateFocus(progress)
                    cameraController.currentFocusDistance = focus
                    btnAf.text = String.format(Locale.US, "AF\n%.1f", focus)
                    btnAf.setTextColor(Color.WHITE)
                }
            }
            "ISO" -> {
                isoProgress = progress
                cameraController.isIsoAuto = isAuto
                if (isAuto) {
                    btnIso.text = "ISO\nAUTO"
                    btnIso.setTextColor(Color.parseColor("#00FF00"))
                } else {
                    val iso = cameraController.calculateIso(progress)
                    cameraController.currentIso = iso
                    btnIso.text = "ISO\n$iso"
                    btnIso.setTextColor(Color.WHITE)
                }
            }
            "S" -> {
                sProgress = progress
                cameraController.isSpeedAuto = isAuto
                if (isAuto) {
                    btnS.text = "S\nAUTO"
                    btnS.setTextColor(Color.parseColor("#00FF00"))
                } else {
                    val speedNanos = cameraController.calculateSpeed(progress)
                    cameraController.currentExposureTime = speedNanos
                    btnS.text = "S\n${formatShutterText(speedNanos)}"
                    btnS.setTextColor(Color.WHITE)
                }
            }
            "EV" -> {
                evProgress = progress
                // คำนวณขอบเขต EV จาก -3 ถึง +3
                val ev = if (progress == 50) 0 else if (progress < 50) -3 + ((progress / 50f) * 3).toInt() else ((progress - 50) / 50f * 3).toInt()
                cameraController.currentEv = ev
                val sign = if (ev > 0) "+" else ""
                btnEv.text = "EV\n$sign$ev.0"
                btnEv.setTextColor(if (isAuto) Color.parseColor("#00FF00") else Color.WHITE)
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

    private fun updateAutoButtonState(isAuto: Boolean) {
        if (isAuto) {
            btnAutoToggle.setTextColor(Color.parseColor("#00FF00"))
        } else {
            btnAutoToggle.setTextColor(Color.WHITE)
        }
    }

    private fun formatShutterText(nanos: Long): String {
        val sec = nanos / 1_000_000_000.0
        return if (sec >= 0.5) String.format(Locale.US, "%.1fs", sec) else "1/${(1.0 / sec).toInt()}"
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
    
    private fun toggleSlider(mode: String, currentProgress: Int) {
        if (activeMode == mode) {
            sliderPanel.visibility = View.GONE; activeMode = "NONE"
            resetButtonColors(); return
        }

        activeMode = mode; sliderPanel.visibility = View.VISIBLE
        resetButtonColors()

        when (mode) {
            "AF" -> btnAf.setTextColor(Color.WHITE)
            "ISO" -> btnIso.setTextColor(Color.WHITE)
            "S" -> btnS.setTextColor(Color.WHITE)
            "EV" -> btnEv.setTextColor(Color.WHITE)
        }

        updateSliderUI(currentProgress, false)
        settingSeekbar.progress = currentProgress
    }

    private fun resetButtonColors() {
        btnAf.setTextColor(if (afProgress == 0) Color.parseColor("#00FF00") else Color.WHITE)
        btnIso.setTextColor(if (isoProgress == 0) Color.parseColor("#00FF00") else Color.WHITE)
        btnS.setTextColor(if (sProgress == 0) Color.parseColor("#00FF00") else Color.WHITE)
        btnEv.setTextColor(if (evProgress == 50) Color.parseColor("#00FF00") else Color.WHITE)
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

    private fun checkThumbnailExists() {
        latestImageUri?.let { uri ->
            try {
                val cursor = contentResolver.quให้ไม่เกินull, null, null, null)
                if (cursor != null && cursor.moveToFirst()) cursor.close()
                else { cursor?.close(); latestImageUri = null; thumbnailView.setImageDrawable(null) }
            } catch (e: Exception) {
                latestImageUri = null; thumbnailView.setImageDrawable(null)
            }
        }
    }

    override fun onPause() {
        cameraController.closeCamera(); cameraController.stopBackgroundThread()
        sliderPanel.visibility = View.GONE; activeMode = "NONE"
        resetButtonColors(); super.onPause()
    }

    private fun checkPermissionsAndStart() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
        } else {
            cameraController.openCamera()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (viewFinder.isAvailable) cameraController.openCamera()
            else viewFinder.surfaceTextureListener = surfaceTextureListener
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
}
