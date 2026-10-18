package com.procamera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
import android.view.Surface
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
    private lateinit var btnMenu: ImageView
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
    private val viewModel: CameraViewModel by viewModels()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val applySettingsRunnable = Runnable { cameraController.applySettings() }
    private var lastSettingTime = 0L

    private var permissionPromptShown = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (hasCameraPermission()) {
            startCameraIfReady()
        } else {
            Toast.makeText(this, R.string.toast_camera_permission_required, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        viewFinder.surfaceTextureListener = surfaceTextureListener

        restoreSliderUI()
    }

    private fun initViews() {
        viewFinder = findViewById(R.id.view_finder)
        btnCapture = findViewById(R.id.btn_capture)
        btnRatio = findViewById(R.id.btn_ratio)
        btnFlash = findViewById(R.id.btn_flash)
        btnMenu = findViewById(R.id.btn_menu)
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
            loadThumbnailInto(uri)
        }
    }

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
        ViewCompat.requestApplyInsets(root)
    }

    private fun setupListeners() {
        btnRatio.setOnClickListener {
            val newIs16by9 = !cameraController.is16by9
            btnRatio.text = if (newIs16by9) getString(R.string.ratio_16_9) else getString(R.string.ratio_4_3)
            adjustPreviewAspectRatio(newIs16by9)
            cameraController.switchRatio(newIs16by9)
        }

        btnFlash.setOnClickListener {
            if (!cameraController.flashAvailable) {
                Toast.makeText(this, R.string.toast_flash_not_available, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val on = !cameraController.isFlashOn
            cameraController.isFlashOn = on
            btnFlash.setImageResource(if (on) R.drawable.ic_flash_on else R.drawable.ic_flash_off)

            btnFlash.imageTintList = ColorStateList.valueOf(if (on) accentColor() else Color.WHITE)
            btnFlash.startAnimation(AnimationUtils.loadAnimation(this, android.R.anim.fade_in))
        }

        btnMenu.setOnClickListener {
            startActivity(Intent(this, MenuActivity::class.java))
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
            cameraController.takePicture(currentRotation())

            viewFinder.alpha = 0.4f
            mainHandler.postDelayed({ viewFinder.alpha = 1.0f }, 80)
        }

        btnAf.setOnClickListener {
            if (cameraController.maxFocus <= 0f || !cameraController.isManualSupported) {
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
            val now = System.currentTimeMillis()
            if (now - lastSettingTime > 60) {
                cameraController.applySettings()
                lastSettingTime = now
            } else {
                viewFinder.removeCallbacks(applySettingsRunnable)
                viewFinder.postDelayed(applySettingsRunnable, 60)
            }
        }
    }

    private fun toggleSlider(mode: String, currentProgress: Int) {
        if (viewModel.activeMode == mode) {
            viewModel.activeMode = "NONE"
            resetButtonColors()
            sliderPanel.animate().cancel()
            sliderPanel.animate().alpha(0f).setDuration(120).withEndAction {
                if (viewModel.activeMode == "NONE") sliderPanel.visibility = View.GONE
            }.start()
            return
        }

        viewModel.activeMode = mode
        sliderPanel.animate().cancel()
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

    private fun syncControllerFromViewModel() {
        val c = cameraController

        val af = viewModel.afProgress
        c.isAfAuto = af == 0
        if (af != 0) {
            val focus = c.calculateFocus(af)
            c.currentFocusDistance = focus
            btnAf.text = String.format(Locale.US, "AF\n%.1f", focus)
        }

        val iso = viewModel.isoProgress
        c.isIsoAuto = iso == 0
        if (iso != 0) {
            c.currentIso = c.calculateIso(iso)
            btnIso.text = "ISO\n${c.currentIso}"
        }

        val s = viewModel.sProgress
        c.isSpeedAuto = s == 0
        if (s != 0) {
            c.currentExposureTime = c.calculateSpeed(s)
            btnS.text = "S\n${formatShutterText(c.currentExposureTime)}"
        }

        val wb = viewModel.wbProgress
        c.currentWb = c.getWbMode(wb)
        if (wb != 0) btnWb.text = "WB\n${c.getWbLabel(c.currentWb)}"

        val ev = viewModel.evProgress
        c.currentEv = c.calculateEv(ev)
        if (ev != 50) btnEv.text = "EV\n${c.getEvLabel(ev)}"
    }

    private fun resetButtonColors() {
        btnAf.setTextColor(Color.WHITE)
        btnIso.setTextColor(Color.WHITE)
        btnWb.setTextColor(Color.WHITE)
        btnS.setTextColor(Color.WHITE)
        btnEv.setTextColor(Color.WHITE)
    }

    private fun adjustPreviewAspectRatio(is16by9: Boolean) {
        viewFinder.post {
            val root = findViewById<View>(R.id.root_layout)
            val availW = if (root.width > 0) root.width else resources.displayMetrics.widthPixels
            val availH = if (root.height > 0) root.height else resources.displayMetrics.heightPixels

            var w = availW
            var h = if (is16by9) w * 16 / 9 else w * 4 / 3
            if (h > availH) {
                h = availH
                w = if (is16by9) h * 9 / 16 else h * 3 / 4
            }

            val lp = viewFinder.layoutParams
            if (lp.width != w || lp.height != h) {
                lp.width = w
                lp.height = h
                viewFinder.layoutParams = lp
            }
        }
    }

    private fun formatShutterText(nanos: Long): String {
        if (nanos <= 0L) return "--"
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

    private fun currentRotation(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            this.display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
    }

    override fun onResume() {
        super.onResume()
        cameraController.rawEnabled = AppSettings.isRawEnabled(this)
        cameraController.startBackgroundThread()
        if (viewFinder.isAvailable) {
            adjustPreviewAspectRatio(cameraController.is16by9)
            checkPermissionsAndStart()
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
        cameraController.release()
        super.onDestroy()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun missingPermissions(): List<String> {
        val wanted = mutableListOf(Manifest.permission.CAMERA)

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            wanted.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkPermissionsAndStart() {
        if (hasCameraPermission()) startCameraIfReady()

        val missing = missingPermissions()
        if (missing.isNotEmpty() && !permissionPromptShown) {
            permissionPromptShown = true
            requestPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startCameraIfReady() {
        if (viewFinder.isAvailable && hasCameraPermission()) {
            adjustPreviewAspectRatio(cameraController.is16by9)
            cameraController.openCamera()
            syncControllerFromViewModel()
        }
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            adjustPreviewAspectRatio(cameraController.is16by9)
            checkPermissionsAndStart()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            cameraController.reapplyPreviewBufferSize()
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private fun accentColor(): Int = Color.parseColor("#FFD60A")

    private fun toastManualUnsupported() {
        Toast.makeText(this, R.string.toast_manual_not_supported, Toast.LENGTH_SHORT).show()
    }

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
