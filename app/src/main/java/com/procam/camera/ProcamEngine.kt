package com.procam.camera

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.*
import android.media.*
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class VideoSettings(
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    val bitrate: Int = 20_000_000,
    val codec: String = "video/avc",
    val codecLabel: String = "H.264"
)

class ProcamEngine(
    private val context: Context,
    private val onState: (State) -> Unit
) {
    data class State(
        val isOpen: Boolean = false,
        val isRecording: Boolean = false,
        val iso: Int = 0,
        val shutterNs: Long = 0L,
        val wbKelvin: Int = 0,
        val wbTint: Int = 0,
        val ev: Float = 0f,
        val durationMs: Long = 0L,
        val audioLevelL: Float = 0f,
        val audioLevelR: Float = 0f,
        val lastUri: Uri? = null,
        val error: String? = null
    )

    companion object {
        private const val TAG = "ProcamEngine"
                private const val AUDIO_SAMPLE_RATE = 48000
        private const val AUDIO_BITRATE = 192_000
    }

    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var isOpening = false
    private var isClosing = false
    private var cameraId: String? = null

    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private var videoEncoder: MediaCodec? = null
    private var videoInputSurface: Surface? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null
    private var tempFile: File? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false
    @Volatile private var videoFormatReady = false
    @Volatile private var audioFormatReady = false

    @Volatile private var drainVideoRunning = false
    @Volatile private var drainAudioRunning = false
    @Volatile private var audioRecording = false
    private var audioThread: Thread? = null
    private var audioDrainThread: Thread? = null
    private var videoThread: Thread? = null
    private var startTimeMs = 0L

    private var minEvIndex = 0
    private var maxEvIndex = 0
    private var evStep = 1f / 6f
    private var currentEvIndex = 0

    private var state = State()
    private fun emit(patch: State.() -> State) {
        state = state.patch()
        onState(state)
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            s: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
            val shutter = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L

            var kelvin = 0

            val neutralPoint = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
            if (neutralPoint != null && neutralPoint.size >= 3) {
                val r = neutralPoint[0].toFloat()
                val g = neutralPoint[1].toFloat()
                val b = neutralPoint[2].toFloat()
                if (r > 0.001f && g > 0.001f && b > 0.001f) {
                    kelvin = rgbToKelvin(r, g, b)
                }
            }

            if (kelvin == 0) {
                val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
                if (gains != null) {
                    kelvin = gainsToKelvin(
                        gains.red,
                        (gains.greenEven + gains.greenOdd) / 2f,
                        gains.blue
                    )
                }
            }

            if (iso != state.iso || shutter != state.shutterNs || kelvin != state.wbKelvin) {
                emit { copy(iso = iso, shutterNs = shutter, wbKelvin = kelvin) }
            }
        }
    }

    private fun rgbToKelvin(r: Float, g: Float, b: Float): Int {
        val total = r + g + b
        if (total <= 0.001f) return 0
        val rn = r / total
        val bn = b / total
        if (rn <= 0.001f || bn <= 0.001f) return 0
        val ratio = rn / bn
        return ratioToKelvin(ratio.toDouble())
    }

    private fun gainsToKelvin(r: Float, g: Float, b: Float): Int {
        if (r <= 0.001f || b <= 0.001f || g <= 0.001f) return 0
        val ratio = (r / b).toDouble()
        return ratioToKelvin(ratio)
    }

    private fun ratioToKelvin(ratio: Double): Int {
        if (ratio <= 0.001) return 0
        val logRb = ln(ratio)
        val kelvin = 6490.0 * Math.pow(logRb, 3.0) -
            3_520_000.0 * Math.pow(logRb, 2.0) +
            6_824_000.0 * logRb +
            5_663_000.0
        if (kelvin.isNaN() || kelvin.isInfinite()) return 0
        val normalized = (kelvin / 100.0).roundToInt() * 100
        return normalized.coerceIn(2000, 12000)
    }

    private fun ensureThread() {
        if (bgThread == null) {
            bgThread = HandlerThread("ProcamBg").also { it.start() }
            bgHandler = Handler(bgThread!!.looper)
        }
    }

    fun open(cm: CameraManager, surface: Surface) {
        ensureThread()
        cameraManager = cm
        previewSurface = surface
        val handler = bgHandler ?: return
        handler.post {
            if (isClosing || state.isRecording) return@post
            if (cameraDevice != null) {
                createSession()
                return@post
            }
            if (isOpening) return@post
            isOpening = true
            try {
                val id = findBackCameraId(cm) ?: run {
                    isOpening = false
                    emit { copy(isOpen = false, error = "No back camera") }
                    return@post
                }
                cameraId = id
                queryEvRange(cm, id)
                @Suppress("MissingPermission")
                cm.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (isClosing || previewSurface == null) {
                            camera.close()
                            isOpening = false
                            return
                        }
                        isOpening = false
                        cameraDevice = camera
                        createSession()
                    }
                    override fun onDisconnected(camera: CameraDevice) {
                        isOpening = false
                        camera.close()
                        if (cameraDevice === camera) cameraDevice = null
                        closeCurrentSession()
                        emit { copy(isOpen = false, error = "Camera disconnected") }
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        isOpening = false
                        camera.close()
                        if (cameraDevice === camera) cameraDevice = null
                        closeCurrentSession()
                        emit { copy(isOpen = false, error = "Camera error $error") }
                    }
                }, handler)
            } catch (t: Throwable) {
                isOpening = false
                Log.e(TAG, "open failed", t)
                emit { copy(isOpen = false, error = t.message) }
            }
        }
    }

    private fun findBackCameraId(cm: CameraManager): String? {
        return try {
            cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull()
        } catch (_: Throwable) {
            null
        }
    }

    private fun queryEvRange(cm: CameraManager, id: String) {
        try {
            val chars = cm.getCameraCharacteristics(id)
            val range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            if (range != null) {
                minEvIndex = range.lower
                maxEvIndex = range.upper
            }
            if (step != null) evStep = step.toFloat()
        } catch (_: Throwable) {}
    }

    fun attachSurface(surface: Surface) {
        ensureThread()
        previewSurface = surface
        bgHandler?.post {
            if (!isClosing && cameraDevice != null && !state.isRecording) createSession()
        }
    }

    fun detachSurface() {
        previewSurface = null
        bgHandler?.post {
            if (!state.isRecording) closeCurrentSession()
        }
    }

    private fun closeCurrentSession() {
        try { session?.stopRepeating() } catch (_: Throwable) {}
        try { session?.abortCaptures() } catch (_: Throwable) {}
        try { session?.close() } catch (_: Throwable) {}
        session = null
    }

    private fun buildPreviewRequest(device: CameraDevice, preview: Surface): CaptureRequest {
        return device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(preview)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentEvIndex)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
            set(
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
            )
        }.build()
    }

    fun setEv(evValue: Float) {
        val stepsFromZero = (evValue / evStep).roundToInt()
        currentEvIndex = stepsFromZero.coerceIn(minEvIndex, maxEvIndex)
        val evFloat = currentEvIndex * evStep
        emit { copy(ev = evFloat) }

        bgHandler?.post {
            try {
                val device = cameraDevice ?: return@post
                val preview = previewSurface ?: return@post
                if (state.isRecording) {
                    val videoSurface = videoInputSurface ?: return@post
                    val rb = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(preview)
                        addTarget(videoSurface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentEvIndex)
                    }
                    session?.setRepeatingRequest(rb.build(), captureCallback, bgHandler)
                } else {
                    session?.setRepeatingRequest(
                        buildPreviewRequest(device, preview),
                        captureCallback,
                        bgHandler
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "setEv failed", t)
            }
        }
    }

    private fun createSession() {
        val device = cameraDevice ?: return
        val preview = previewSurface ?: return

        closeCurrentSession()

        try {
            @Suppress("DEPRECATION")
            device.createCaptureSession(
                listOf(preview),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        try {
                            s.setRepeatingRequest(
                                buildPreviewRequest(device, preview),
                                captureCallback,
                                bgHandler
                            )
                            emit { copy(isOpen = true, error = null) }
                        } catch (t: Throwable) {
                            Log.e(TAG, "createSession onConfigured failed", t)
                            emit { copy(error = t.message) }
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        Log.e(TAG, "Session configure failed")
                        emit { copy(error = "Session config failed") }
                    }
                },
                bgHandler
            )
        } catch (t: Throwable) {
            Log.e(TAG, "createSession failed", t)
            emit { copy(error = t.message) }
        }
    }

    fun startRecording(settings: VideoSettings) {
        if (state.isRecording) return
        ensureThread()
        bgHandler?.post { startRecordingInternal(settings) }
    }

    private fun resolveRecordingSettings(requested: VideoSettings): VideoSettings? {
        val cm = cameraManager ?: return null
        val id = cameraId ?: findBackCameraId(cm) ?: return null
        val chars = try { cm.getCameraCharacteristics(id) } catch (_: Throwable) { return null }
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val cameraSizes = map.getOutputSizes(Surface::class.java)?.toList().orEmpty()
        if (cameraSizes.isEmpty()) return null

        val fixedFps = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.filter { it.lower == it.upper }
            ?.map { it.lower }
            ?.distinct()
            ?.sorted()
            .orEmpty()
        val actualFps = fixedFps.minByOrNull { kotlin.math.abs(it - requested.fps) } ?: 30

        fun encoderSupports(mime: String, width: Int, height: Int, fps: Int): Boolean {
            return try {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                    if (!info.isEncoder) return@any false
                    val type = info.supportedTypes.firstOrNull { it.equals(mime, true) } ?: return@any false
                    val caps = info.getCapabilitiesForType(type)
                    val vc = caps.videoCapabilities
                    vc.isSizeAndRateSupported(width, height, fps.toDouble())
                }
            } catch (_: Throwable) { false }
        }

        fun codecCandidates(): List<Pair<String, String>> = if (requested.codec.equals("video/hevc", true)) {
            listOf("video/hevc" to "H.265", "video/avc" to "H.264")
        } else {
            listOf("video/avc" to "H.264")
        }

        val requestedAspect = requested.width.toFloat() / requested.height.toFloat()
        val sizeCandidates = cameraSizes
            .sortedBy { kotlin.math.abs((it.width.toFloat() / it.height.toFloat()) - requestedAspect) * 10_000f + kotlin.math.abs(it.width - requested.width).toFloat() }
            .filter { it.width >= 640 && it.height >= 360 }

        for ((mime, label) in codecCandidates()) {
            val chosen = sizeCandidates.firstOrNull { size ->
                encoderSupports(mime, size.width, size.height, actualFps)
            } ?: continue
            val bitrate = requested.bitrate.coerceAtLeast(2_000_000)
            return requested.copy(
                width = chosen.width,
                height = chosen.height,
                fps = actualFps,
                bitrate = bitrate,
                codec = mime,
                codecLabel = label
            )
        }
        return null
    }

    private fun startRecordingInternal(requested: VideoSettings) {
        val device = cameraDevice ?: return
        val preview = previewSurface ?: return
        if (state.isRecording) return

        val settings = resolveRecordingSettings(requested) ?: run {
            emit { copy(error = "Requested recording mode is not supported") }
            return
        }

        try {
            val file = File(context.cacheDir, "procam_rec_${System.currentTimeMillis()}.mp4")
            tempFile = file

            val videoFormat = MediaFormat.createVideoFormat(settings.codec, settings.width, settings.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, settings.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, settings.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            videoEncoder = MediaCodec.createEncoderByType(settings.codec).apply {
                configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                videoInputSurface = createInputSurface()
                start()
            }

            val audioFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", AUDIO_SAMPLE_RATE, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            audioEncoder = MediaCodec.createEncoderByType("audio/mp4a-latm").apply {
                configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            videoTrackIndex = -1
            audioTrackIndex = -1
            muxerStarted = false
            videoFormatReady = false
            audioFormatReady = false

            closeCurrentSession()
            val videoSurface = videoInputSurface ?: throw IllegalStateException("Encoder surface unavailable")

            @Suppress("DEPRECATION")
            device.createCaptureSession(
                listOf(preview, videoSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (cameraDevice !== device || previewSurface !== preview) {
                            try { s.close() } catch (_: Throwable) {}
                            cleanupRecording()
                            return
                        }
                        session = s
                        try {
                            val rb = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(preview)
                                addTarget(videoSurface)
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(settings.fps, settings.fps))
                                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentEvIndex)
                            }
                            drainVideoRunning = true
                            drainAudioRunning = true
                            audioRecording = true
                            videoThread = Thread { drainVideo() }.also { it.start() }
                            audioDrainThread = Thread { drainAudio() }.also { it.start() }
                            audioThread = Thread { startAudioCapture() }.also { it.start() }
                            s.setRepeatingRequest(rb.build(), captureCallback, bgHandler)
                            emit { copy(isRecording = true, durationMs = 0L, error = null) }
                            startTimeMs = System.currentTimeMillis()
                            startTimer()
                        } catch (t: Throwable) {
                            Log.e(TAG, "start repeating failed", t)
                            cleanupRecording()
                            emit { copy(error = t.message) }
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        cleanupRecording()
                        emit { copy(error = "Record session config failed") }
                    }
                },
                bgHandler
            )
        } catch (t: Throwable) {
            Log.e(TAG, "startRecording failed", t)
            cleanupRecording()
            emit { copy(error = t.message) }
        }
    }

    private fun drainVideo() {
        val enc = videoEncoder ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (drainVideoRunning) {
                when (val index = enc.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        synchronized(this) {
                            if (!videoFormatReady) {
                                val format = enc.outputFormat
                                videoTrackIndex = muxer?.addTrack(format) ?: -1
                                videoFormatReady = true
                                maybeStartMuxerLocked()
                            }
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (index >= 0) {
                        val buffer = enc.getOutputBuffer(index)
                        if (buffer != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            writeSampleDataLocked(true, buffer, info)
                        }
                        enc.releaseOutputBuffer(index, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "drainVideo", t)
        } finally {
            drainVideoRunning = false
        }
    }

    private fun drainAudio() {
        val enc = audioEncoder ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (drainAudioRunning) {
                when (val index = enc.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        synchronized(this) {
                            if (!audioFormatReady) {
                                val format = enc.outputFormat
                                audioTrackIndex = muxer?.addTrack(format) ?: -1
                                audioFormatReady = true
                                maybeStartMuxerLocked()
                            }
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (index >= 0) {
                        val buffer = enc.getOutputBuffer(index)
                        if (buffer != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            writeSampleDataLocked(false, buffer, info)
                        }
                        enc.releaseOutputBuffer(index, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "drainAudio", t)
        } finally {
            drainAudioRunning = false
        }
    }

    @Synchronized
    private fun maybeStartMuxerLocked() {
        if (!muxerStarted && videoFormatReady && audioFormatReady) {
            muxer?.start()
            muxerStarted = true
        }
    }

    @Synchronized
    private fun writeSampleDataLocked(isVideo: Boolean, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        val mux = muxer ?: return
        if (!muxerStarted) return
        val track = if (isVideo) videoTrackIndex else audioTrackIndex
        if (track >= 0) {
            try { mux.writeSampleData(track, buf, info) } catch (t: Throwable) { Log.e(TAG, "writeSampleData", t) }
        }
    }

    private fun startAudioCapture() {
        val minBuf = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            audioRecording = false
            return
        }
        val bufSize = maxOf(minBuf, 16384)
        val record = try {
            @Suppress("MissingPermission")
            AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER,
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord create failed", t)
            audioRecording = false
            return
        }
        audioRecord = record
        try { record.startRecording() } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord start failed", t)
            audioRecording = false
            try { record.release() } catch (_: Throwable) {}
            audioRecord = null
            return
        }

        val enc = audioEncoder ?: return
        val pcm = ByteArray(bufSize)
        while (audioRecording) {
            val read = try { record.read(pcm, 0, pcm.size) } catch (_: Throwable) { -1 }
            if (read <= 0) continue
            val inIdx = try { enc.dequeueInputBuffer(10_000) } catch (_: Throwable) { -1 }
            if (inIdx >= 0) {
                try {
                    val inBuf = enc.getInputBuffer(inIdx) ?: continue
                    inBuf.clear()
                    inBuf.put(pcm, 0, read)
                    val ptsUs = (System.nanoTime() / 1000L)
                    enc.queueInputBuffer(inIdx, 0, read, ptsUs, 0)
                } catch (_: Throwable) {}
            }
        }
        try {
            val inIdx = enc.dequeueInputBuffer(100_000)
            if (inIdx >= 0) enc.queueInputBuffer(inIdx, 0, 0, System.nanoTime() / 1000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        } catch (_: Throwable) {}
    }

    fun stopRecording() {
        if (!state.isRecording) return
        bgHandler?.post { stopRecordingInternal() }
    }

    private fun stopRecordingInternal() {
        if (!state.isRecording) return

        emit { copy(isRecording = false, durationMs = 0L, audioLevelL = 0f, audioLevelR = 0f) }

        try { session?.stopRepeating() } catch (_: Throwable) {}

        try { videoEncoder?.signalEndOfInputStream() } catch (_: Throwable) {}

        audioRecording = false

        audioThread?.join(1500)
        audioDrainThread?.join(1500)
        videoThread?.join(1500)

        val videoWaitStart = System.currentTimeMillis()
        while (drainVideoRunning && System.currentTimeMillis() - videoWaitStart < 2000) {
            Thread.sleep(20)
        }

        val audioWaitStart = System.currentTimeMillis()
        while (drainAudioRunning && System.currentTimeMillis() - audioWaitStart < 1000) {
            Thread.sleep(20)
        }

        drainVideoRunning = false
        drainAudioRunning = false
        audioThread = null
        audioDrainThread = null
        videoThread = null

        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null

        val ve = videoEncoder
        videoEncoder = null
        try { ve?.stop() } catch (_: Throwable) {}
        try { ve?.release() } catch (_: Throwable) {}

        val ae = audioEncoder
        audioEncoder = null
        try { ae?.stop() } catch (_: Throwable) {}
        try { ae?.release() } catch (_: Throwable) {}

        var savedUri: Uri? = null
        val savedFile = tempFile

        try {
            if (muxerStarted) muxer?.stop()
            muxer?.release()
            muxer = null
            muxerStarted = false

            if (savedFile != null && savedFile.exists() && savedFile.length() > 0) {
                savedUri = copyToGallery(savedFile)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "muxer stop failed", t)
            try { muxer?.release() } catch (_: Throwable) {}
            muxer = null
        }

        videoTrackIndex = -1
        audioTrackIndex = -1

        try { videoInputSurface?.release() } catch (_: Throwable) {}
        videoInputSurface = null

        emit { copy(lastUri = savedUri) }

        savedFile?.delete()
        tempFile = null

        if (cameraDevice != null && previewSurface != null) {
            createSession()
        }
    }

    private fun cleanupRecording() {
        drainVideoRunning = false
        drainAudioRunning = false
        audioRecording = false
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
        try { videoEncoder?.stop() } catch (_: Throwable) {}
        try { videoEncoder?.release() } catch (_: Throwable) {}
        videoEncoder = null
        try { audioEncoder?.stop() } catch (_: Throwable) {}
        try { audioEncoder?.release() } catch (_: Throwable) {}
        audioEncoder = null
        try { muxer?.release() } catch (_: Throwable) {}
        muxer = null
        try { videoInputSurface?.release() } catch (_: Throwable) {}
        videoInputSurface = null
        muxerStarted = false
        videoFormatReady = false
        audioFormatReady = false
        videoTrackIndex = -1
        audioTrackIndex = -1
        try { tempFile?.delete() } catch (_: Throwable) {}
        tempFile = null
    }

    private fun copyToGallery(file: File): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "PROCAM_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Procam")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val uri = resolver.insert(collection, values)
                ?: return null

            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            } ?: run {
                resolver.delete(uri, null, null)
                return null
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val update = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                resolver.update(uri, update, null, null)
            }
            uri
        } catch (t: Throwable) {
            Log.e(TAG, "copyToGallery failed", t)
            null
        }
    }

    private fun startTimer() {
        bgHandler?.post(object : Runnable {
            override fun run() {
                if (state.isRecording) {
                    val elapsed = System.currentTimeMillis() - startTimeMs
                    emit { copy(durationMs = elapsed) }
                    bgHandler?.postDelayed(this, 200)
                }
            }
        })
    }

    fun close() {
        ensureThread()
        bgHandler?.post {
            if (state.isRecording) stopRecordingInternal()
            closeCurrentSession()
            isOpening = false
            isClosing = true
            try { cameraDevice?.close() } catch (_: Throwable) {}
            cameraDevice = null
            cameraId = null
            isClosing = false
            emit { copy(isOpen = false, isRecording = false) }
        }
    }

    fun shutdown() {
        val handler = bgHandler
        if (handler == null) return
        handler.post {
            if (state.isRecording) stopRecordingInternal()
            closeCurrentSession()
            try { cameraDevice?.close() } catch (_: Throwable) {}
            cameraDevice = null
            cameraManager = null
            isOpening = false
            isClosing = true
            emit { copy(isOpen = false, isRecording = false) }
            bgThread?.quitSafely()
            bgThread = null
            bgHandler = null
            isClosing = false
        }
    }

}
