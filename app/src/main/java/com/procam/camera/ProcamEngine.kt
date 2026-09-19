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
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class VideoSettings(
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    val bitrate: Int = 20_000_000,
    val codec: String = MediaFormat.MIMETYPE_VIDEO_AVC,
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
        val recordingCodec: String = "H.264",
        val recordingFps: Int = 30,
        val error: String? = null
    )

    companion object {
        private const val TAG = "ProcamEngine"
        private const val AUDIO_SAMPLE_RATE = 48_000
        private const val AUDIO_BITRATE = 192_000
        private const val VIDEO_I_FRAME_INTERVAL = 1
    }

    private val muxerLock = Any()

    private var cameraManager: CameraManager? = null
    private var cameraId: String? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var isOpening = false

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
    @Volatile private var muxerStarted = false
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    private var audioEnabled = false

    @Volatile private var drainVideoRunning = false
    @Volatile private var drainAudioRunning = false
    @Volatile private var audioRecording = false

    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var captureAudioThread: Thread? = null

    private var startTimeMs = 0L
    private var firstVideoPtsUs = Long.MIN_VALUE
    private var lastVideoPtsUs = 0L

    private var minEvIndex = 0
    private var maxEvIndex = 0
    private var evStep = 1f / 6f
    private var currentEvIndex = 0

    private var currentRecordFps = 30
    private var currentRecordWidth = 1920
    private var currentRecordHeight = 1080

    private var audioChannels = 1
    private var audioBytesPerFrame = 2
    private var audioSamplesSubmitted = 0L

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
        return ratioToKelvin((rn / bn).toDouble())
    }

    private fun gainsToKelvin(r: Float, g: Float, b: Float): Int {
        if (r <= 0.001f || b <= 0.001f || g <= 0.001f) return 0
        return ratioToKelvin((r / b).toDouble())
    }

    private fun ratioToKelvin(ratio: Double): Int {
        if (ratio <= 0.001) return 0
        val logRb = ln(ratio)
        val kelvin = 6490.0 * Math.pow(logRb, 3.0) -
            3_520_000.0 * Math.pow(logRb, 2.0) +
            6_824_000.0 * logRb +
            5_663_000.0
        if (kelvin.isNaN() || kelvin.isInfinite()) return 0
        return ((kelvin / 100.0).roundToInt() * 100).coerceIn(2000, 12000)
    }

    private fun ensureThread() {
        if (bgThread == null) {
            bgThread = HandlerThread("ProcamBg").also { it.start() }
            bgHandler = Handler(bgThread!!.looper)
        }
    }

    fun open(cm: CameraManager, surface: Surface) {
        if (isOpening) return
        isOpening = true
        ensureThread()
        cameraManager = cm
        previewSurface = surface
        cameraId = choosePrimaryCameraId(cm)

        if (cameraDevice != null) {
            isOpening = false
            createSession()
            return
        }

        try {
            val selectedCameraId = cameraId ?: throw IllegalStateException("No usable camera found")
            queryEvRange(cm)
            @Suppress("MissingPermission")
            cm.openCamera(selectedCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    isOpening = false
                    cameraDevice = camera
                    createSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    isOpening = false
                    camera.close()
                    cameraDevice = null
                    emit { copy(isOpen = false, error = "Camera disconnected") }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    isOpening = false
                    camera.close()
                    cameraDevice = null
                    emit { copy(isOpen = false, error = "Camera error $error") }
                }
            }, bgHandler)
        } catch (t: Throwable) {
            isOpening = false
            Log.e(TAG, "open failed", t)
            emit { copy(error = t.message ?: "Unable to open camera") }
        }
    }

    private fun choosePrimaryCameraId(cm: CameraManager): String? {
        return runCatching {
            cm.cameraIdList.firstOrNull { id ->
                val chars = cm.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull()
        }.getOrNull()
    }

    private fun queryEvRange(cm: CameraManager) {
        runCatching {
            val chars = cm.getCameraCharacteristics(cameraId ?: return)
            chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let {
                minEvIndex = it.lower
                maxEvIndex = it.upper
            }
            chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.let {
                evStep = it.toFloat().takeIf { v -> v > 0f } ?: evStep
            }
        }
    }

    fun attachSurface(surface: Surface) {
        ensureThread()
        previewSurface = surface
        if (cameraDevice != null && !state.isRecording) createSession()
    }

    fun detachSurface() {
        previewSurface = null
    }

    private fun closeCurrentSession() {
        try { session?.stopRepeating() } catch (_: Throwable) {}
        try { session?.abortCaptures() } catch (_: Throwable) {}
        try { session?.close() } catch (_: Throwable) {}
        session = null
    }

    private fun chooseFps(requested: Int): Int {
        val chars = cameraManager?.getCameraCharacteristics(cameraId ?: return 30) ?: return 30
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return 30
        val exact = ranges
            .filter { it.upper >= requested && it.lower <= requested }
            .minByOrNull { abs(it.upper - requested) + abs(it.lower - requested) }
        if (exact != null) return requested

        return ranges
            .filter { it.upper <= requested }
            .maxByOrNull { it.upper }
            ?.upper
            ?: ranges.minByOrNull { it.upper }?.upper
            ?: 30
    }

    private fun fpsRangeFor(fps: Int): Range<Int> {
        val ranges = cameraManager
            ?.getCameraCharacteristics(cameraId ?: return Range(fps, fps))
            ?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            .orEmpty()

        return ranges
            .filter { it.upper >= fps && it.lower <= fps }
            .minByOrNull { abs(it.upper - fps) + abs(it.lower - fps) }
            ?: Range(fps, fps)
    }

    private fun supportedRecordingSizes(): List<Pair<Int, Int>> {
        val chars = cameraManager?.getCameraCharacteristics(cameraId ?: return emptyList()) ?: return emptyList()
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return emptyList()
        return map.getOutputSizes(MediaRecorder::class.java)
            ?.map { it.width to it.height }
            ?.distinct()
            .orEmpty()
    }

    private fun chooseRecordingSize(requestedW: Int, requestedH: Int): Pair<Int, Int> {
        val sizes = supportedRecordingSizes()
        if (sizes.isEmpty()) return requestedW to requestedH

        val exact = sizes.firstOrNull { it.first == requestedW && it.second == requestedH }
        if (exact != null) return exact

        val requestedAspect = requestedW.toDouble() / requestedH.toDouble()
        return sizes
            .filter { it.first <= requestedW && it.second <= requestedH }
            .minByOrNull {
                abs((it.first.toDouble() / it.second) - requestedAspect) * 10_000.0 +
                    abs((it.first * it.second).toDouble() - (requestedW * requestedH).toDouble()) /
                    max(1.0, (requestedW * requestedH).toDouble())
            }
            ?: sizes.maxByOrNull { it.first.toLong() * it.second }!!
    }

    private data class EncoderChoice(
        val mime: String,
        val codecName: String,
        val hardware: Boolean,
        val maxBitrate: Int
    )

    private fun findEncoder(
        mime: String,
        width: Int,
        height: Int,
        fps: Int
    ): EncoderChoice? {
        val infos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, true) } }

        fun valid(info: MediaCodecInfo): Boolean = runCatching {
            val caps = info.getCapabilitiesForType(mime)
            val vc = caps.videoCapabilities ?: return false
            vc.isSizeSupported(width, height) &&
                vc.getSupportedFrameRatesFor(width, height).contains(fps.toDouble())
        }.getOrDefault(false)

        val ordered = infos.sortedBy { info ->
            if (isHardwareCodec(info)) 0 else 1
        }
        val selected = ordered.firstOrNull(::valid) ?: return null
        val caps = runCatching { selected.getCapabilitiesForType(mime).videoCapabilities }.getOrNull()
        return EncoderChoice(
            mime = mime,
            codecName = selected.name,
            hardware = isHardwareCodec(selected),
            maxBitrate = caps?.bitrateRange?.upper ?: 0
        )
    }

    private fun isHardwareCodec(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return !info.isSoftwareOnly
        return !info.name.startsWith("OMX.google.", true) &&
            !info.name.startsWith("c2.android.", true)
    }

    private fun bitrateFor(width: Int, height: Int, fps: Int, requested: Int, max: Int): Int {
        val floor = 2_000_000L
        val estimated = width.toLong() * height.toLong() * fps.toLong() / 4L
        val requestedSafe = requested.toLong().coerceAtLeast(floor)
        val value = minOf(max.takeIf { it > 0 }?.toLong() ?: Long.MAX_VALUE, maxOf(estimated, requestedSafe))
        return value.coerceIn(floor, 120_000_000L).toInt()
    }

    private fun buildPreviewRequest(device: CameraDevice, preview: Surface): CaptureRequest {
        val fps = chooseFps(30)
        return device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(preview)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentEvIndex)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRangeFor(fps))
            set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO)
        }.build()
    }

    fun setEv(evValue: Float) {
        val stepsFromZero = (evValue / evStep).roundToInt()
        currentEvIndex = stepsFromZero.coerceIn(minEvIndex, maxEvIndex)
        emit { copy(ev = currentEvIndex * evStep) }

        bgHandler?.post {
            try {
                val device = cameraDevice ?: return@post
                val preview = previewSurface ?: return@post
                if (state.isRecording) {
                    val video = videoInputSurface ?: return@post
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(preview)
                        addTarget(video)
                        set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentEvIndex)
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRangeFor(currentRecordFps))
                    }
                    session?.setRepeatingRequest(request.build(), captureCallback, bgHandler)
                } else {
                    session?.setRepeatingRequest(
                        buildPreviewRequest(device, preview),
                        captureCallback,
                        bgHandler
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "setEv failed", t)
                emit { copy(error = t.message ?: "Unable to set EV") }
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
                            Log.e(TAG, "preview request failed", t)
                            emit { copy(error = t.message ?: "Preview failed") }
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        Log.e(TAG, "Preview session configure failed")
                        emit { copy(isOpen = false, error = "Camera preview configuration failed") }
                    }
                },
                bgHandler
            )
        } catch (t: Throwable) {
            Log.e(TAG, "createSession failed", t)
            emit { copy(error = t.message ?: "Camera session failed") }
        }
    }

    fun startRecording(settings: VideoSettings) {
        if (state.isRecording) return
        ensureThread()
        bgHandler?.post { startRecordingInternal(settings) }
    }

    private fun startRecordingInternal(requested: VideoSettings) {
        val device = cameraDevice
        val preview = previewSurface
        if (device == null || preview == null) {
            emit { copy(error = "Camera is not ready") }
            return
        }

        val (width, height) = chooseRecordingSize(requested.width, requested.height)
        val fps = chooseFps(requested.fps)

        var choice = findEncoder(requested.codec, width, height, fps)
        var actualCodec = requested.codec
        var codecLabel = requested.codecLabel

        if (choice == null && requested.codec == MediaFormat.MIMETYPE_VIDEO_HEVC) {
            choice = findEncoder(MediaFormat.MIMETYPE_VIDEO_AVC, width, height, fps)
            actualCodec = MediaFormat.MIMETYPE_VIDEO_AVC
            codecLabel = "H.264"
        }
        if (choice == null) {
            choice = findEncoder(MediaFormat.MIMETYPE_VIDEO_AVC, width, height, fps)
            actualCodec = MediaFormat.MIMETYPE_VIDEO_AVC
            codecLabel = "H.264"
        }

        if (choice == null) {
            emit { copy(error = "No compatible video encoder for ${width}×${height} @ ${fps} fps") }
            return
        }

        currentRecordWidth = width
        currentRecordHeight = height
        currentRecordFps = fps

        val fallbackMessage = when {
            requested.fps != fps ->
                "Requested ${requested.fps} fps is unavailable; using ${fps} fps"
            requested.codec == MediaFormat.MIMETYPE_VIDEO_HEVC &&
                actualCodec != MediaFormat.MIMETYPE_VIDEO_HEVC ->
                "H.265 is unavailable for this configuration; using H.264"
            width != requested.width || height != requested.height ->
                "Requested ${requested.width}×${requested.height} is unavailable; using ${width}×${height}"
            else -> null
        }

        emit {
            copy(
                recordingCodec = if (actualCodec == MediaFormat.MIMETYPE_VIDEO_HEVC) "H.265" else "H.264",
                recordingFps = fps,
                error = fallbackMessage
            )
        }

        val file = File(context.cacheDir, "procam_rec_${System.currentTimeMillis()}.mp4")
        tempFile = file

        try {
            val bitrate = bitrateFor(width, height, fps, requested.bitrate, choice.maxBitrate)

            val videoFormat = MediaFormat.createVideoFormat(actualCodec, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_I_FRAME_INTERVAL)
            }

            videoEncoder = MediaCodec.createByCodecName(choice.codecName).apply {
                configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                videoInputSurface = createInputSurface()
                start()
            }

            audioEnabled = createAudioEncoder()
            muxer = MediaMuxer(
                file.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            videoTrackIndex = -1
            audioTrackIndex = -1
            videoFormat = null
            audioFormat = null
            muxerStarted = false
            firstVideoPtsUs = Long.MIN_VALUE
            lastVideoPtsUs = 0L
            audioSamplesSubmitted = 0L

            closeCurrentSession()

            val videoSurface = videoInputSurface ?: throw IllegalStateException("Video encoder surface missing")
            @Suppress("DEPRECATION")
            device.createCaptureSession(
                listOf(preview, videoSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        try {
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(preview)
                                addTarget(videoSurface)
                                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                                set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentEvIndex)
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRangeFor(fps))
                                set(
                                    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                                    CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
                                )
                            }

                            drainVideoRunning = true
                            videoThread = Thread(::drainVideo, "ProcamVideoDrain").also { it.start() }

                            if (audioEnabled) {
                                drainAudioRunning = true
                                audioThread = Thread(::drainAudio, "ProcamAudioDrain").also { it.start() }
                                audioRecording = true
                                startAudioCapture()
                            }

                            s.setRepeatingRequest(request.build(), captureCallback, bgHandler)

                            startTimeMs = System.currentTimeMillis()
                            emit {
                                copy(
                                    isRecording = true,
                                    durationMs = 0L,
                                    audioLevelL = 0f,
                                    audioLevelR = 0f,
                                    error = fallbackMessage
                                )
                            }
                            startTimer()
                        } catch (t: Throwable) {
                            Log.e(TAG, "record request failed", t)
                            cleanupRecording()
                            emit { copy(error = t.message ?: "Unable to start recording") }
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        Log.e(TAG, "Record session configure failed")
                        cleanupRecording()
                        if (requested.codec == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                            startRecordingInternal(
                                requested.copy(
                                    codec = MediaFormat.MIMETYPE_VIDEO_AVC,
                                    codecLabel = "H.264"
                                )
                            )
                        } else {
                            emit {
                                copy(
                                    error = "Camera does not support ${width}×${height} @ ${fps} fps with preview"
                                )
                            }
                        }
                    }
                },
                bgHandler
            )
        } catch (t: Throwable) {
            Log.e(TAG, "startRecording failed", t)
            cleanupRecording()

            if (requested.codec == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                Log.w(TAG, "HEVC failed at runtime; retrying with H.264", t)
                startRecordingInternal(
                    requested.copy(
                        codec = MediaFormat.MIMETYPE_VIDEO_AVC,
                        codecLabel = "H.264"
                    )
                )
            } else {
                emit { copy(error = t.message ?: "Unable to start recording") }
            }
        }
    }

    private fun createAudioEncoder(): Boolean {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            AUDIO_SAMPLE_RATE,
            1
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        return try {
            audioChannels = 1
            audioBytesPerFrame = 2
            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "AAC encoder unavailable; recording video without audio", t)
            audioEncoder?.release()
            audioEncoder = null
            false
        }
    }

    private fun startAudioCapture() {
        val channel = AudioFormat.CHANNEL_IN_MONO
        val minBuf = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            channel,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            audioRecording = false
            return
        }

        val bufSize = max(minBuf, 16_384)
        val record = try {
            @Suppress("MissingPermission")
            AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER,
                AUDIO_SAMPLE_RATE,
                channel,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord create failed", t)
            null
        }

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            try { record?.release() } catch (_: Throwable) {}
            audioRecording = false
            return
        }

        audioRecord = record
        try {
            record.startRecording()
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord start failed", t)
            audioRecording = false
            record.release()
            audioRecord = null
            return
        }

        captureAudioThread = Thread({
            val enc = audioEncoder ?: return@Thread
            val pcm = ByteArray(bufSize)

            try {
                while (audioRecording) {
                    val read = record.read(pcm, 0, pcm.size)
                    if (read <= 0) continue

                    updateAudioMeter(pcm, read)

                    var offset = 0
                    while (offset < read && audioRecording) {
                        val inputIndex = enc.dequeueInputBuffer(50_000)
                        if (inputIndex < 0) continue

                        val input = enc.getInputBuffer(inputIndex) ?: continue
                        input.clear()
                        val bytes = minOf(input.remaining(), read - offset)
                        input.put(pcm, offset, bytes)

                        val frames = bytes / audioBytesPerFrame
                        val ptsUs = audioSamplesSubmitted * 1_000_000L / AUDIO_SAMPLE_RATE
                        enc.queueInputBuffer(inputIndex, 0, bytes, ptsUs, 0)
                        audioSamplesSubmitted += frames
                        offset += bytes
                    }
                }
            } catch (t: Throwable) {
                if (audioRecording) Log.e(TAG, "audio capture loop failed", t)
            }
        }, "ProcamAudioCapture").also {
            it.start()
        }
    }

    private fun updateAudioMeter(pcm: ByteArray, read: Int) {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < read) {
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toDouble()
            sum += sample * sample
            count++
            i += 2
        }
        if (count == 0) return
        val rms = sqrt(sum / count) / 32768.0
        val level = ((20.0 * kotlin.math.log10(max(rms, 1e-6)) + 60.0) / 60.0)
            .coerceIn(0.0, 1.0).toFloat()
        emit { copy(audioLevelL = level, audioLevelR = level) }
    }

    private fun drainVideo() {
        val enc = videoEncoder ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (drainVideoRunning || !muxerStarted) {
                when (val index = enc.dequeueOutputBuffer(info, 20_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        synchronized(muxerLock) {
                            videoFormat = enc.outputFormat
                            maybeStartMuxerLocked()
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!drainVideoRunning) break
                    }
                    else -> if (index >= 0) {
                        val buffer = enc.getOutputBuffer(index)
                        if (buffer != null && info.size > 0 &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            val copyInfo = MediaCodec.BufferInfo().apply {
                                set(info.offset, info.size, normalizeVideoPts(info.presentationTimeUs), info.flags)
                            }
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            writeSampleDataLocked(true, buffer, copyInfo)
                        }
                        val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        enc.releaseOutputBuffer(index, false)
                        if (eos) break
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "drainVideo failed", t)
        } finally {
            drainVideoRunning = false
        }
    }

    private fun drainAudio() {
        val enc = audioEncoder ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (drainAudioRunning || !muxerStarted) {
                when (val index = enc.dequeueOutputBuffer(info, 20_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        synchronized(muxerLock) {
                            audioFormat = enc.outputFormat
                            maybeStartMuxerLocked()
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!drainAudioRunning) break
                    }
                    else -> if (index >= 0) {
                        val buffer = enc.getOutputBuffer(index)
                        if (buffer != null && info.size > 0 &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            val copyInfo = MediaCodec.BufferInfo().apply {
                                set(info.offset, info.size, info.presentationTimeUs, info.flags)
                            }
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            writeSampleDataLocked(false, buffer, copyInfo)
                        }
                        val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        enc.releaseOutputBuffer(index, false)
                        if (eos) break
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "drainAudio failed", t)
        } finally {
            drainAudioRunning = false
        }
    }

    private fun normalizeVideoPts(ptsUs: Long): Long {
        if (firstVideoPtsUs == Long.MIN_VALUE) firstVideoPtsUs = ptsUs
        val normalized = (ptsUs - firstVideoPtsUs).coerceAtLeast(0L)
        lastVideoPtsUs = max(lastVideoPtsUs, normalized)
        return normalized
    }

    private fun maybeStartMuxerLocked() {
        if (muxerStarted) return
        val mux = muxer ?: return
        val vf = videoFormat ?: return

        if (audioEnabled && audioFormat == null) return

        if (videoTrackIndex < 0) videoTrackIndex = mux.addTrack(vf)
        if (audioEnabled && audioTrackIndex < 0) {
            audioTrackIndex = mux.addTrack(audioFormat!!)
        }

        mux.start()
        muxerStarted = true
    }

    private fun writeSampleDataLocked(
        isVideo: Boolean,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo
    ) {
        synchronized(muxerLock) {
            val mux = muxer ?: return
            if (!muxerStarted) return
            val track = if (isVideo) videoTrackIndex else audioTrackIndex
            if (track < 0) return
            try {
                mux.writeSampleData(track, buffer, info)
            } catch (t: Throwable) {
                Log.e(TAG, "writeSampleData failed", t)
            }
        }
    }

    fun stopRecording() {
        if (!state.isRecording) return
        bgHandler?.post { stopRecordingInternal() }
    }

    private fun stopRecordingInternal() {
        if (!state.isRecording) return

        try { session?.stopRepeating() } catch (_: Throwable) {}
        audioRecording = false
        try { audioRecord?.stop() } catch (_: Throwable) {}

        try { videoEncoder?.signalEndOfInputStream() } catch (t: Throwable) {
            Log.w(TAG, "signal video EOS failed", t)
        }

        joinThread(captureAudioThread, 1500)
        captureAudioThread = null

        if (audioEnabled) {
            try {
                val ae = audioEncoder
                if (ae != null) {
                    val index = ae.dequeueInputBuffer(100_000)
                    if (index >= 0) {
                        ae.queueInputBuffer(
                            index,
                            0,
                            0,
                            audioSamplesSubmitted * 1_000_000L / AUDIO_SAMPLE_RATE,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "audio EOS failed", t)
            }
        }

        joinThread(videoThread, 3000)
        joinThread(audioThread, 3000)
        videoThread = null
        audioThread = null

        drainVideoRunning = false
        drainAudioRunning = false

        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null

        var savedUri: Uri? = null
        val savedFile = tempFile

        synchronized(muxerLock) {
            try {
                if (muxerStarted) {
                    muxer?.stop()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "muxer stop failed", t)
            }
            try { muxer?.release() } catch (_: Throwable) {}
            muxer = null
            muxerStarted = false
            videoFormat = null
            audioFormat = null
        }

        stopAndReleaseCodec("video")
        stopAndReleaseCodec("audio")

        try { videoInputSurface?.release() } catch (_: Throwable) {}
        videoInputSurface = null

        videoTrackIndex = -1
        audioTrackIndex = -1
        audioEnabled = false

        if (savedFile != null && savedFile.exists() && savedFile.length() > 0L) {
            savedUri = copyToGallery(savedFile)
        }

        savedFile?.delete()
        tempFile = null

        emit {
            copy(
                isRecording = false,
                durationMs = 0L,
                audioLevelL = 0f,
                audioLevelR = 0f,
                lastUri = savedUri,
                error = if (savedUri == null) "Recording could not be saved" else null
            )
        }

        if (cameraDevice != null && previewSurface != null) createSession()
    }

    private fun stopAndReleaseCodec(which: String) {
        if (which == "video") {
            val c = videoEncoder
            videoEncoder = null
            try { c?.stop() } catch (_: Throwable) {}
            try { c?.release() } catch (_: Throwable) {}
        } else {
            val c = audioEncoder
            audioEncoder = null
            try { c?.stop() } catch (_: Throwable) {}
            try { c?.release() } catch (_: Throwable) {}
        }
    }

    private fun joinThread(thread: Thread?, timeoutMs: Long) {
        if (thread == null || thread === Thread.currentThread()) return
        try { thread.join(timeoutMs) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun cleanupRecording() {
        drainVideoRunning = false
        drainAudioRunning = false
        audioRecording = false

        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null

        joinThread(captureAudioThread, 500)
        joinThread(videoThread, 500)
        joinThread(audioThread, 500)
        captureAudioThread = null
        videoThread = null
        audioThread = null

        synchronized(muxerLock) {
            try { muxer?.release() } catch (_: Throwable) {}
            muxer = null
            muxerStarted = false
            videoFormat = null
            audioFormat = null
        }

        stopAndReleaseCodec("video")
        stopAndReleaseCodec("audio")

        try { videoInputSurface?.release() } catch (_: Throwable) {}
        videoInputSurface = null

        videoTrackIndex = -1
        audioTrackIndex = -1
        audioEnabled = false

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
            val uri = resolver.insert(collection, values) ?: return null

            try {
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                } ?: throw IllegalStateException("Unable to open MediaStore output")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                        null,
                        null
                    )
                }
                uri
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
        } catch (t: Throwable) {
            Log.e(TAG, "copyToGallery failed", t)
            null
        }
    }

    private fun startTimer() {
        bgHandler?.post(object : Runnable {
            override fun run() {
                if (state.isRecording) {
                    emit { copy(durationMs = System.currentTimeMillis() - startTimeMs) }
                    bgHandler?.postDelayed(this, 200)
                }
            }
        })
    }

    fun close() {
        val handler = bgHandler
        if (state.isRecording && handler != null) {
            val finish = Runnable {
                stopRecordingInternal()
                closeCurrentSession()
                try { cameraDevice?.close() } catch (_: Throwable) {}
                cameraDevice = null
                cameraId = null
                emit { copy(isOpen = false) }
                bgThread?.quitSafely()
                bgThread = null
                bgHandler = null
            }
            if (LooperCompat.isCurrent(handler)) finish.run() else handler.post(finish)
        } else {
            closeCurrentSession()
            try { cameraDevice?.close() } catch (_: Throwable) {}
            cameraDevice = null
            cameraId = null
            emit { copy(isOpen = false) }
            handler?.post {
                bgThread?.quitSafely()
                bgThread = null
                bgHandler = null
            }
        }
    }

    private object LooperCompat {
        fun isCurrent(handler: Handler): Boolean =
            android.os.Looper.myLooper() == handler.looper
    }
}
