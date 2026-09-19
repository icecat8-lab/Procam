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
        val ev: Float = 0f,
        val durationMs: Long = 0L,
        val audioLevelL: Float = 0f,
        val audioLevelR: Float = 0f,
        val lastUri: Uri? = null,
        val error: String? = null
    )

    companion object {
        private const val TAG = "ProcamEngine"
        private const val CAMERA_ID = "0"
        private const val AUDIO_SAMPLE_RATE = 48000
        private const val AUDIO_BITRATE = 192_000
    }

    private var cameraManager: CameraManager? = null
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
    private var muxerStarted = false

    private var audioThread: Thread? = null
    @Volatile private var audioRecording = false
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
            val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
            val kelvin = if (gains != null) {
                gainsToKelvin(gains.red, (gains.greenEven + gains.greenOdd) / 2f, gains.blue)
            } else 0

            if (iso != state.iso || shutter != state.shutterNs || kelvin != state.wbKelvin) {
                emit { copy(iso = iso, shutterNs = shutter, wbKelvin = kelvin) }
            }
        }
    }

    private fun gainsToKelvin(r: Float, g: Float, b: Float): Int {
        if (r <= 0.001f || b <= 0.001f || g <= 0.001f) return 0
        val rb = r / b
        if (rb <= 0.001f) return 0
        val logRb = ln(rb.toDouble())
        val kelvin = 6490.0 * Math.pow(logRb, 3.0) -
            3_520_000.0 * Math.pow(logRb, 2.0) +
            6_824_000.0 * logRb +
            5_663_000.0
        val normalized = (kelvin / 100.0).toInt() * 100
        return normalized.coerceIn(2000, 12000)
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

        if (cameraDevice != null) {
            isOpening = false
            previewSurface = surface
            createSession()
            return
        }

        previewSurface = surface

        try {
            queryEvRange(cm)
            @Suppress("MissingPermission")
            cm.openCamera(CAMERA_ID, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    isOpening = false
                    cameraDevice = camera
                    createSession()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    isOpening = false
                    camera.close(); cameraDevice = null
                    emit { copy(isOpen = false, error = "Camera disconnected") }
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    isOpening = false
                    camera.close(); cameraDevice = null
                    emit { copy(isOpen = false, error = "Camera error $error") }
                }
            }, bgHandler)
        } catch (t: Throwable) {
            isOpening = false
            Log.e(TAG, "open failed", t)
            emit { copy(error = t.message) }
        }
    }

    private fun queryEvRange(cm: CameraManager) {
        try {
            val chars = cm.getCameraCharacteristics(CAMERA_ID)
            val range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            if (range != null) {
                minEvIndex = range.lower
                maxEvIndex = range.upper
            }
            if (step != null) {
                evStep = step.toFloat()
            }
        } catch (_: Throwable) {}
    }

    fun attachSurface(surface: Surface) {
        ensureThread()
        previewSurface = surface
        if (cameraDevice != null) {
            createSession()
        }
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

    fun setEv(evValue: Float) {
        val stepsFromZero = (evValue / evStep).roundToInt()
        currentEvIndex = stepsFromZero.coerceIn(minEvIndex, maxEvIndex)
        val evFloat = currentEvIndex * evStep
        emit { copy(ev = evFloat) }

        try {
            val device = cameraDevice ?: return
            val preview = previewSurface ?: return
            if (state.isRecording) {
                val videoSurface = videoInputSurface ?: return
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
                val rb = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(preview)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentEvIndex)
                }
                session?.setRepeatingRequest(rb.build(), captureCallback, bgHandler)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "setEv failed", t)
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
                            val rb = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(preview)
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentEvIndex)
                            }
                            s.setRepeatingRequest(rb.build(), captureCallback, bgHandler)
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
        val device = cameraDevice ?: return
        val preview = previewSurface ?: return

        try {
            val file = File(
                context.cacheDir,
                "procam_rec_${System.currentTimeMillis()}.mp4"
            )
            tempFile = file

            val videoFormat = MediaFormat.createVideoFormat(
                settings.codec, settings.width, settings.height
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, settings.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, settings.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            videoEncoder = MediaCodec.createEncoderByType(settings.codec).apply {
                configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                videoInputSurface = createInputSurface()
                start()
            }

            val audioFormat = MediaFormat.createAudioFormat(
                "audio/mp4a-latm", AUDIO_SAMPLE_RATE, 2
            ).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            audioEncoder = MediaCodec.createEncoderByType("audio/mp4a-latm").apply {
                configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            muxer = MediaMuxer(
                file.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            videoTrackIndex = -1
            audioTrackIndex = -1
            muxerStarted = false

            closeCurrentSession()

            val videoSurface = videoInputSurface!!

            @Suppress("DEPRECATION")
            device.createCaptureSession(
                listOf(preview, videoSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        val rb = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(preview)
                            addTarget(videoSurface)
                            set(
                                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                Range(settings.fps, settings.fps)
                            )
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentEvIndex)
                        }
                        try {
                            s.setRepeatingRequest(rb.build(), captureCallback, bgHandler)
                            Thread.sleep(100)
                            startEncoderThreads()
                            audioRecording = true
                            startAudioCapture()
                            startTimeMs = System.currentTimeMillis()
                            emit { copy(isRecording = true, durationMs = 0L, error = null) }
                            startTimer()
                        } catch (t: Throwable) {
                            Log.e(TAG, "start repeating failed", t)
                            emit { copy(error = t.message) }
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        Log.e(TAG, "Record session configure failed")
                        emit { copy(error = "Record session config failed") }
                        cleanupRecording()
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

    private fun startEncoderThreads() {
        Thread { drainVideo() }.start()
        Thread { drainAudio() }.start()
    }

    private fun drainVideo() {
        val enc = videoEncoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            while (state.isRecording) {
                val outIdx = enc.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIdx >= 0) {
                    val buf = enc.getOutputBuffer(outIdx)
                    if (buf != null && bufferInfo.size > 0 &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        buf.position(bufferInfo.offset)
                        buf.limit(bufferInfo.offset + bufferInfo.size)
                        writeSampleData(true, buf, bufferInfo)
                    }
                    enc.releaseOutputBuffer(outIdx, false)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "drainVideo", t)
        }
    }

    private fun drainAudio() {
        val enc = audioEncoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            while (audioRecording) {
                val outIdx = enc.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIdx >= 0) {
                    val buf = enc.getOutputBuffer(outIdx)
                    if (buf != null && bufferInfo.size > 0 &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        buf.position(bufferInfo.offset)
                        buf.limit(bufferInfo.offset + bufferInfo.size)
                        writeSampleData(false, buf, bufferInfo)
                    }
                    enc.releaseOutputBuffer(outIdx, false)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "drainAudio", t)
        }
    }

    @Synchronized
    private fun writeSampleData(
        isVideo: Boolean,
        buf: ByteBuffer,
        info: MediaCodec.BufferInfo
    ) {
        val mux = muxer ?: return
        if (!muxerStarted) {
            if (videoTrackIndex < 0) {
                val f = videoEncoder?.outputFormat ?: return
                videoTrackIndex = mux.addTrack(f)
            }
            if (audioTrackIndex < 0) {
                val f = audioEncoder?.outputFormat ?: return
                audioTrackIndex = mux.addTrack(f)
            }
            if (videoTrackIndex >= 0 && audioTrackIndex >= 0) {
                mux.start()
                muxerStarted = true
            } else return
        }
        val track = if (isVideo) videoTrackIndex else audioTrackIndex
        if (track < 0) return
        try {
            mux.writeSampleData(track, buf, info)
        } catch (t: Throwable) {
            Log.e(TAG, "writeSampleData", t)
        }
    }

    private fun startAudioCapture() {
        val minBuf = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, 16384)
        @Suppress("MissingPermission")
        val record = AudioRecord(
            MediaRecorder.AudioSource.CAMCORDER,
            AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )
        audioRecord = record
        record.startRecording()

        audioThread = Thread {
            val enc = audioEncoder ?: return@Thread
            val pcm = ByteArray(bufSize)
            while (audioRecording) {
                val read = record.read(pcm, 0, pcm.size)
                if (read <= 0) continue

                var sumL = 0.0
                var sumR = 0.0
                var count = 0
                var i = 0
                while (i + 3 < read) {
                    val l = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort()
                    val r = ((pcm[i + 3].toInt() shl 8) or (pcm[i + 2].toInt() and 0xFF)).toShort()
                    sumL += l.toDouble() * l
                    sumR += r.toDouble() * r
                    count++
                    i += 4
                }
                if (count > 0) {
                    val rmsL = sqrt(sumL / count).toFloat() / 32768f
                    val rmsR = sqrt(sumR / count).toFloat() / 32768f
                    val dbL = (20 * kotlin.math.log10(rmsL.coerceAtLeast(1e-6f)) + 60) / 60
                    val dbR = (20 * kotlin.math.log10(rmsR.coerceAtLeast(1e-6f)) + 60) / 60
                    emit {
                        copy(
                            audioLevelL = dbL.coerceIn(0f, 1f),
                            audioLevelR = dbR.coerceIn(0f, 1f)
                        )
                    }
                }

                val inIdx = enc.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val inBuf = enc.getInputBuffer(inIdx) ?: continue
                    inBuf.clear()
                    inBuf.put(pcm, 0, read)
                    enc.queueInputBuffer(inIdx, 0, read, System.nanoTime() / 1000, 0)
                }
            }
        }.also { it.start() }
    }

    fun stopRecording() {
        if (!state.isRecording) return
        val savedFile = tempFile

        try {
            audioRecording = false
            audioThread?.join(500)
            audioThread = null
            try { audioRecord?.stop() } catch (_: Throwable) {}
            try { audioRecord?.release() } catch (_: Throwable) {}
            audioRecord = null

            try { videoEncoder?.signalEndOfInputStream() } catch (_: Throwable) {}

            Thread.sleep(400)

            val ve = videoEncoder
            videoEncoder = null
            try { ve?.stop() } catch (_: Throwable) {}
            try { ve?.release() } catch (_: Throwable) {}

            val ae = audioEncoder
            audioEncoder = null
            try { ae?.stop() } catch (_: Throwable) {}
            try { ae?.release() } catch (_: Throwable) {}

            var savedUri: Uri? = null
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

            emit {
                copy(
                    isRecording = false,
                    durationMs = 0L,
                    audioLevelL = 0f,
                    audioLevelR = 0f,
                    lastUri = savedUri
                )
            }

            savedFile?.delete()
            tempFile = null

            if (cameraDevice != null && previewSurface != null) {
                createSession()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "stopRecording", t)
            cleanupRecording()
            emit { copy(isRecording = false, durationMs = 0L) }
        }
    }

    private fun cleanupRecording() {
        try { audioRecording = false } catch (_: Throwable) {}
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
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null

            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input ->
                    input.copyTo(out)
                }
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
        try {
            if (state.isRecording) stopRecording()
            closeCurrentSession()
            cameraDevice?.close(); cameraDevice = null
            cameraManager = null
            bgThread?.quitSafely(); bgThread = null
            bgHandler = null
        } catch (_: Throwable) {}
        emit { copy(isOpen = false) }
    }
}
