package com.procamera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var viewFinder: TextureView
    private lateinit var btnCapture: View
    private lateinit var btnRatio: TextView
    private lateinit var btnFlash: ImageView
    private lateinit var thumbnailView: ImageView
    private lateinit var focusRing: View
    private lateinit var focusEvText: TextView

    private lateinit var sliderPanel: LinearLayout
    private lateinit var settingSeekbar: SeekBar
    private lateinit var btnAutoToggle: TextView
    private lateinit var sliderValueTitle: TextView

    private lateinit var btnAf: TextView
    private lateinit var btnIso: TextView
    private lateinit var btnWb: TextView
    private lateinit var btnS: TextView
    private lateinit var btnEv: TextView

    private lateinit var cameraController: CameraController
    // Use the androidx.activity viewModels() delegate (was deprecated ViewModelProvider call)
    private val viewModel: CameraViewModel by viewModels()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val applySettingsRunnable = Runnable { cameraController.applySettings() }
    private var lastSettingTime = 0L

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) {
            startCameraIfReady()
        } else {
            Toast.makeText(this, R.string.toast_camera_permission_required, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge (modern non-deprecated API — replaces systemUiVisibility flags)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContentView(R.layout.activity_main)

        initViews()
        setupCameraController()
        setupListeners()
        applyWindowInsets()

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
        focusEvText = findViewById(R.id.focus_ev_text)

        sliderPanel = findViewById(R.id.slider_panel)
        settingSeekbar = findViewById(R.id.setting_seekbar)
        btnAutoToggle = findViewById(R.id.btn_auto_toggle)
        sliderValueTitle = findViewById(R.id.slider_value_title)

        btnAf = findViewById(R.id.btn_af)
        btnIso = findViewById(R.id.btn_iso)
        btnWb = findViewById(R.id.btn_wb)
        btnS = findViewById(R.id.btn_s)
        btnEv = findViewById(R.id.btn_ev)
    }

    private fun setupCameraController() {
        cameraController = CameraController(this, viewFinder)
        cameraController.onCameraStateUpdated = { focusDist, iso, speedNanos ->
            // Already throttled in CameraController — safe to update UI here
            if (cameraController.isAfAuto) {
                btnAf.text = String.format(Locale.US, "AF\n%.1f", focusDist)
            }
            if (cameraController.isIsoAuto) {
                btnIso.text = "ISO\n$iso"
            }
            if (cameraController.isSpeedAuto) {
                btnS.text = "S\n${formatShutterText(speedNanos)}"
            }
        }

        cameraController.onImageSaved = { uri ->
            viewModel.latestImageUri = uri
            // FIX: decode a small downscaled thumbnail instead of the full-res image
            // (setImageURI on a 12–50 MP JPEG could allocate 50–200 MB for a 48dp view)
            loadThumbnailInto(uri)
        }
    }

    /**
     * FIX: top/bottom spacing now follows real WindowInsets (status bar, nav bar,
     * display cutout) instead of a hardcoded 28dp margin that overlapped cutouts.
     */
    private fun applyWindowInsets() {
        val root = findViewById<View>(R.id.root_layout)
        val topBar = findViewById<View>(R.id.top_bar)
        val bottomControls = findViewById<LinearLayout>(R.id.bottom_controls)
        val density = resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            topBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = bars.top + (8 * density).toInt()
            }
            bottomControls.updatePadding(bottom = bars.bottom + (24 * density).toInt())
            insets
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
            if (!cameraController.flashAvailable) {
                Toast.makeText(this, R.string.toast_flash_not_available, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            cameraController.isFlashOn = !cameraController.isFlashOn
            btnFlash.setImageResource(
                if (cameraController.isFlashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
            )
            // iOS-style pop animation
            btnFlash.startAnimation(
                AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
            )
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
            val rotation = currentRotation()
            cameraController.takePicture(rotation)

            // iOS-style shutter animation: quick fade-out + scale-down + back
            viewFinder.alpha = 0.4f
            mainHandler.postDelayed({ viewFinder.alpha = 1.0f }, 80)
        }

        btnAf.setOnClickListener {
            if (cameraController.maxFocus <= 0f) {
                toastManualUnsupported()
            } else toggleSlider("AF", viewModel.afProgress)
        }
        btnIso.setOnClickListener {
            if (!cameraController.isManualSupported) toastManualUnsupported()
            else toggleSlider("ISO", viewModel.isoProgress)
        }
        btnWb.setOnClickListener { toggleSlider("WB", viewModel.wbProgress) }
        btnS.setOnClickListener {
            if (!cameraController.isManualSupported) toastManualUnsupported()
            else toggleSlider("S", viewModel.sProgress)
        }
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
        btnAutoToggle.setTextColor(if (isAuto) accentColor() else Color.WHITE)

        when (viewModel.activeMode) {
            "AF" -> {
                viewModel.afProgress = progress
                cameraController.isAfAuto = isAuto
                if (!isAuto) {
                    val focus = cameraController.calculateFocus(progress)
                    cameraController.currentFocusDistance = focus
                    btnAf.text = String.format(Locale.US, "AF\n%.1f", focus)
                    sliderValueTitle.text = String.format(Locale.US, "FOCUS  %.1f", focus)
                } else {
                    sliderValueTitle.text = "FOCUS  AUTO"
                }
            }
            "ISO" -> {
                viewModel.isoProgress = progress
                cameraController.isIsoAuto = isAuto
                if (!isAuto) {
                    val iso = cameraController.calculateIso(progress)
                    cameraController.currentIso = iso
                    btnIso.text = "ISO\n$iso"
                    sliderValueTitle.text = "ISO  $iso"
                } else {
                    sliderValueTitle.text = "ISO  AUTO"
                }
            }
            "WB" -> {
                viewModel.wbProgress = progress
                val mode = cameraController.getWbMode(progress)
                cameraController.currentWb = mode
                val label = cameraController.getWbLabel(mode)
                btnWb.text = "WB\n$label"
                sliderValueTitle.text = "WB  $label"
            }
            "S" -> {
                viewModel.sProgress = progress
                cameraController.isSpeedAuto = isAuto
                if (!isAuto) {
                    val speedNanos = cameraController.calculateSpeed(progress)
                    cameraController.currentExposureTime = speedNanos
                    val label = formatShutterText(speedNanos)
                    btnS.text = "S\n$label"
                    sliderValueTitle.text = "SHUTTER  $label"
                } else {
                    sliderValueTitle.text = "SHUTTER  AUTO"
                }
            }
            "EV" -> {
                viewModel.evProgress = progress
                val ev = cameraController.calculateEv(progress)
                cameraController.currentEv = ev
                val label = cameraController.getEvLabel(progress)
                btnEv.text = "EV\n$label"
                sliderValueTitle.text = "EV  $label"
                focusEvText.text = "EV $label"
            }
        }

        if (fromUser) {
            // Throttle applySettings — was firing on every progress tick
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastSettingTime > 60) {
                cameraController.applySettings()
                lastSettingTime = currentTime
            } else {
                viewFinder.removeCallbacks(applySettingsRunnable)
                viewFinder.postDelayed(applySettingsRunnable, 60)
            }
        }
    }

    private fun toggleSlider(mode: String, currentProgress: Int) {
        if (viewModel.activeMode == mode) {
            // Close slider with animation
            sliderPanel.animate().alpha(0f).setDuration(120).withEndAction {
                sliderPanel.visibility = View.GONE
            }
            viewModel.activeMode = "NONE"
            resetButtonColors()
            return
        }

        viewModel.activeMode = mode
        sliderPanel.visibility = View.VISIBLE
        sliderPanel.alpha = 0f
        sliderPanel.animate().alpha(1f).setDuration(180).start()

        resetButtonColors()
        when (mode) {
            "AF" -> btnAf.setTextColor(accentColor())
            "ISO" -> btnIso.setTextColor(accentColor())
            "WB" -> btnWb.setTextColor(accentColor())
            "S" -> btnS.setTextColor(accentColor())
            "EV" -> btnEv.setTextColor(accentColor())
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
        btnAf.setTextColor(Color.WHITE)
        btnIso.setTextColor(Color.WHITE)
        btnWb.setTextColor(Color.WHITE)
        btnS.setTextColor(Color.WHITE)
        btnEv.setTextColor(Color.WHITE)
    }

    /**
     * FIX: aspect ratio formula was inverted (16:9 produced a height taller than 4:3).
     * For a 16:9 layout:  width:height = 16:9  →  height = width * 9 / 16
     * For a 4:3  layout:  width:height = 4:3   →  height = width * 3 / 4
     */
    private fun adjustPreviewAspectRatio(is16by9: Boolean) {
        viewFinder.post {
            val displayMetrics: DisplayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val layoutParams = viewFinder.layoutParams
            layoutParams.width = screenWidth
            layoutParams.height = if (is16by9) (screenWidth * 9) / 16 else (screenWidth * 3) / 4
            viewFinder.layoutParams = layoutParams
        }
    }

    private fun formatShutterText(nanos: Long): String {
        val sec = nanos / 1_000_000_000.0
        return if (sec >= 0.5) String.format(Locale.US, "%.1fs", sec)
        else "1/${(1.0 / sec).toInt()}"
    }

    private fun openGallery(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/*")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.toast_no_viewer_app, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * FIX: was using deprecated windowManager.defaultDisplay.rotation on API 30+.
     * On Android 11+, prefer Context.getDisplay().rotation.
     */
    private fun currentRotation(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            this.display?.rotation ?: android.view.Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
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
        viewModel.latestImageUri?.let { loadThumbnailInto(it) }
    }

    override fun onPause() {
        cameraController.closeCamera()
        cameraController.stopBackgroundThread()
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun checkPermissionsAndStart() {
        val permissions = listOf(Manifest.permission.CAMERA)
        if (permissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) {
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

    /** iOS-style accent (system yellow) */
    private fun accentColor(): Int = Color.parseColor("#FFD60A")

    /** Toast shown when the device lacks manual controls capability. */
    private fun toastManualUnsupported() {
        Toast.makeText(this, R.string.toast_manual_not_supported, Toast.LENGTH_SHORT).show()
    }

    /**
     * FIX: was checkThumbnailExists() → setImageURI() which decoded the FULL-resolution
     * image (12–50 MP = 50–200 MB bitmap) into a 48dp thumbnail → OOM/jank risk.
     * Now validates existence and decodes a small, EXIF-corrected bitmap off the main thread.
     */
    private fun loadThumbnailInto(uri: Uri) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    decodeThumbnail(uri, 256)
                } catch (e: Exception) {
                    null
                }
            }
            if (bitmap != null) {
                thumbnailView.setImageBitmap(bitmap)
            } else {
                viewModel.latestImageUri = null
                thumbnailView.setImageDrawable(null)
            }
        }
    }

    /** Decode a downscaled, EXIF-rotated square thumbnail for [uri]. */
    private fun decodeThumbnail(uri: Uri, targetSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetSize &&
            bounds.outHeight / (sample * 2) >= targetSize
        ) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val rotated = rotateByExif(uri, src)
        return ThumbnailUtils.extractThumbnail(
            rotated, targetSize, targetSize, ThumbnailUtils.OPTIONS_RECYCLE_INPUT
        )
    }

    private fun rotateByExif(uri: Uri, bmp: Bitmap): Bitmap = try {
        val orientation = contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation == 0f) bmp else Bitmap.createBitmap(
            bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true
        )
    } catch (e: Exception) {
        bmp
    }
}
