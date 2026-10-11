package com.procamera

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.view.TextureView
import android.view.View
import android.widget.ImageView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var viewFinder: TextureView
    private lateinit var btnCapture: View
    private lateinit var btnIso: TextView
    private lateinit var btnShutterSpeed: TextView
    private lateinit var thumbnailView: ImageView
    private lateinit var cameraController: CameraController

    private val isoValues = intArrayOf(0, 100, 200, 400, 800, 1600)
    private val speedValues = longArrayOf(0, 1000000000L/1000, 1000000000L/500, 1000000000L/250, 1000000000L/60, 1000000000L/30)
    private val speedLabels = arrayOf("AUTO", "1/1000", "1/500", "1/250", "1/60", "1/30")
    
    private var isoIndex = 0
    private var speedIndex = 0
    private var latestImageUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.view_finder)
        btnCapture = findViewById(R.id.btn_capture)
        btnIso = findViewById(R.id.btn_iso)
        btnShutterSpeed = findViewById(R.id.btn_shutter_speed)
        thumbnailView = findViewById(R.id.thumbnail_view)
        
        cameraController = CameraController(this, viewFinder)

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
            cameraController.takePicture()
        }

        btnIso.setOnClickListener {
            isoIndex = (isoIndex + 1) % isoValues.size
            updateManualControls()
        }

        btnShutterSpeed.setOnClickListener {
            speedIndex = (speedIndex + 1) % speedValues.size
            updateManualControls()
        }
    }
    
    private fun updateManualControls() {
        if (isoIndex == 0 && speedIndex == 0) {
            cameraController.isManualMode = false
            btnIso.text = "ISO\nAUTO"
            btnShutterSpeed.text = "S\nAUTO"
        } else {
            cameraController.isManualMode = true
            
            if (isoIndex > 0) cameraController.currentIso = isoValues[isoIndex]
            if (speedIndex > 0) cameraController.currentExposureTime = speedValues[speedIndex]
            
            btnIso.text = "ISO\n${if(isoIndex == 0) "AUTO" else isoValues[isoIndex]}"
            btnShutterSpeed.text = "S\n${speedLabels[speedIndex]}"
        }
        cameraController.applySettings()
    }

    override fun onResume() {
        super.onResume()
        cameraController.startBackgroundThread()
        if (viewFinder.isAvailable) {
            checkPermissionsAndStart()
        } else {
            viewFinder.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        cameraController.closeCamera()
        cameraController.stopBackgroundThread()
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
            cameraController.openCamera()
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
