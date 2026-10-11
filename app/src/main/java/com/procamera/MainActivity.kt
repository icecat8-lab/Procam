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
import android.view.TextureView
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var viewFinder: TextureView
    private lateinit var btnCapture: View
    private lateinit var btnRatio: TextView
    private lateinit var thumbnailView: ImageView
    private lateinit var cameraController: CameraController

    private lateinit var sliderPanel: LinearLayout
    private lateinit var settingSeekbar: SeekBar
    private lateinit var sliderValueText: TextView

    private lateinit var btnAf: TextView
    private lateinit var btnIso: TextView
    private lateinit var btnWb: TextView
    private lateinit var btnS: TextView
    private lateinit var btnEv: TextView

    private val isoValues = intArrayOf(0, 100, 200, 400, 800, 1600, 3200)
    private val speedValues = longArrayOf(0, 1000000000L/1000, 1000000000L/500, 1000000000L/250, 1000000000L/60, 1000000000L/30, 1000000000L/15, 1000000000L/4)
    private val speedLabels = arrayOf("AUTO", "1/1000", "1/500", "1/250", "1/60", "1/30", "1/15", "1/4")
    private val wbValues = intArrayOf(CaptureRequest.CONTROL_AWB_MODE_AUTO, CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT, CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT, CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT)
    private val wbLabels = arrayOf("AWB", "Cloudy", "Day", "Fluor", "Incan")
    private val evValues = intArrayOf(-4, -3, -2, -1, 0, 1, 2, 3, 4)

    private var activeMode = "NONE"
    private var isoIndex = 0
    private var speedIndex = 0
    private var wbIndex = 0
    private var evIndex = 4
    private var afProgress = 0

    private var latestImageUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.view_finder)
        btnCapture = findViewById(R.id.btn_capture)
        btnRatio = findViewById(R.id.btn_ratio)
        thumbnailView = findViewById(R.id.thumbnail_view)
        
        sliderPanel = findViewById(R.id.slider_panel)
        settingSeekbar = findViewById(R.id.setting_seekbar)
        sliderValueText = findViewById(R.id.slider_value_text)

        btnAf = findViewById(R.id.btn_af)
        btnIso = findViewById(R.id.btn_iso)
        btnWb = findViewById(R.id.btn_wb)
        btnS = findViewById(R.id.btn_s)
        btnEv = findViewById(R.id.btn_ev)

        cameraController = CameraController(this, viewFinder)

        btnRatio.setOnClickListener {
            val newIs16by9 = !cameraController.is16by9
            btnRatio.text = if (newIs16by9) "9:16" else "3:4"
            adjustPreviewAspectRatio(newIs16by9)
            cameraController.switchRatio(newIs16by9)
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
        }

        btnAf.setOnClickListener { toggleSlider("AF") }
        btnIso.setOnClickListener { toggleSlider("ISO") }
        btnWb.setOnClickListener { toggleSlider("WB") }
        btnS.setOnClickListener { toggleSlider("S") }
        btnEv.setOnClickListener { toggleSlider("EV") }

        settingSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                when (activeMode) {
                    "AF" -> {
                        afProgress = progress
                        val isAuto = progress == 0
                        btnAf.text = "AF\n${if (isAuto) "AUTO" else "M"}"
                        sliderValueText.text = if (isAuto) "AUTO" else (progress / 10f).toString()
                        cameraController.isAfAuto = isAuto
                        if (!isAuto) cameraController.currentFocusDistance = progress / 10f
                    }
                    "ISO" -> {
                        isoIndex = progress
                        val isAuto = progress == 0
                        btnIso.text = "ISO\n${if (isAuto) "AUTO" else isoValues[progress]}"
                        sliderValueText.text = if (isAuto) "AUTO" else isoValues[progress].toString()
                        cameraController.isIsoAuto = isAuto
                        if (!isAuto) cameraController.currentIso = isoValues[progress]
                    }
                    "WB" -> {
                        wbIndex = progress
                        btnWb.text = "WB\n${wbLabels[progress]}"
                        sliderValueText.text = wbLabels[progress]
                        cameraController.currentWb = wbValues[progress]
                    }
                    "S" -> {
                        speedIndex = progress
                        val isAuto = progress == 0
                        btnS.text = "S\n${speedLabels[progress]}"
                        sliderValueText.text = speedLabels[progress]
                        cameraController.isSpeedAuto = isAuto
                        if (!isAuto) cameraController.currentExposureTime = speedValues[progress]
                    }
                    "EV" -> {
                        evIndex = progress
                        val value = evValues[progress]
                        val sign = if (value > 0) "+" else ""
                        btnEv.text = "EV\n$sign$value"
                        sliderValueText.text = "$sign$value"
                        cameraController.currentEv = value
                    }
                }
                cameraController.applySettings()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun adjustPreviewAspectRatio(is16by9: Boolean) {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val layoutParams = viewFinder.layoutParams
        
        layoutParams.width = screenWidth
        layoutParams.height = if (is16by9) {
            (screenWidth * 16 / 9)
        } else {
            (screenWidth * 4 / 3)
        }
        viewFinder.layoutParams = layoutParams
    }
    
    private fun toggleSlider(mode: String) {
        if (activeMode == mode) {
            sliderPanel.visibility = View.GONE
            activeMode = "NONE"
            resetButtonColors()
            return
        }

        activeMode = mode
        sliderPanel.visibility = View.VISIBLE
        resetButtonColors()

        when (mode) {
            "AF" -> {
                btnAf.setTextColor(Color.parseColor("#FFD700"))
                settingSeekbar.max = 100
                settingSeekbar.progress = afProgress
                sliderValueText.text = if (afProgress == 0) "AUTO" else (afProgress / 10f).toString()
            }
            "ISO" -> {
                btnIso.setTextColor(Color.parseColor("#FFD700"))
                settingSeekbar.max = isoValues.size - 1
                settingSeekbar.progress = isoIndex
                sliderValueText.text = if (isoIndex == 0) "AUTO" else isoValues[isoIndex].toString()
            }
            "WB" -> {
                btnWb.setTextColor(Color.parseColor("#FFD700"))
                settingSeekbar.max = wbValues.size - 1
                settingSeekbar.progress = wbIndex
                sliderValueText.text = wbLabels[wbIndex]
            }
            "S" -> {
                btnS.setTextColor(Color.parseColor("#FFD700"))
                settingSeekbar.max = speedValues.size - 1
                settingSeekbar.progress = speedIndex
                sliderValueText.text = speedLabels[speedIndex]
            }
            "EV" -> {
                btnEv.setTextColor(Color.parseColor("#FFD700"))
                settingSeekbar.max = evValues.size - 1
                settingSeekbar.progress = evIndex
                sliderValueText.text = if (evValues[evIndex] > 0) "+${evValues[evIndex]}" else evValues[evIndex].toString()
            }
        }
    }

    private fun resetButtonColors() {
        btnAf.setTextColor(Color.WHITE)
        btnIso.setTextColor(Color.WHITE)
        btnWb.setTextColor(Color.WHITE)
        btnS.setTextColor(Color.WHITE)
        btnEv.setTextColor(Color.WHITE)
    }

    override fun onResume() {
        super.onResume()
        cameraController.startBackgroundThread()
        adjustPreviewAspectRatio(cameraController.is16by9)
        if (viewFinder.isAvailable) {
            checkPermissionsAndStart()
        } else {
            viewFinder.surfaceTextureListener = surfaceTextureListener
        }
        checkThumbnailExists()
    }

    private fun checkThumbnailExists() {
        latestImageUri?.let { uri ->
            try {
                val cursor = contentResolver.query(uri, null, null, null, null)
                if (cursor != null && cursor.moveToFirst()) {
                    cursor.close()
                } else {
                    cursor?.close()
                    latestImageUri = null
                    thumbnailView.setImageDrawable(null)
                }
            } catch (e: Exception) {
                latestImageUri = null
                thumbnailView.setImageDrawable(null)
            }
        }
    }

    override fun onPause() {
        cameraController.closeCamera()
        cameraController.stopBackgroundThread()
        sliderPanel.visibility = View.GONE
        activeMode = "NONE"
        resetButtonColors()
        super.onPause()
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
            if (viewFinder.isAvailable) {
                cameraController.openCamera()
            } else {
                viewFinder.surfaceTextureListener = surfaceTextureListener
            }
        }
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            checkPermissionsAndStart()
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }
}
