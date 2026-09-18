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
import kotlin.math.sqrt

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
        val durationMs: Long = 0L,
        val audioLevelL: Float = 0f,
        val audioLevelR: Float = 0f,
        val lastFile: String? = null,
        val lastUri: Uri? = null,
        val error: String? = null
    )

    companion object {
        private const val TAG = "ProcamEngine"
        private const val CAMERA_ID = "0"
        private const val VIDEO_WIDTH = 1920
        private const val VIDEO_HEIGHT = 1080
        private const val FRAME_RATE = 30
        private const val VIDEO_BITRATE = 20_000_000
        private const val I_FRAME_INTERVAL = 1
        private const val AUDIO_SAMPLE_RATE = 48000
        private const val AUDIO_BITRATE = 192_000
    }

    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var requestBuilder: CaptureRequest.Builder? = null

    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private var videoEncoder: MediaCodec? = null
    private var videoInputSurface: Surface? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false

    private var audioThread: Thread? = null
    @Volatile private var audioRecording = false
    private var startTimeMs = 0L
    private var currentOutputFile: File? = null

    private var manualMode = false

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
            val kelvin = gains?.let { gainsToKelvin(it.red, it.greenEven, it.blue) } ?: 0

            if (iso != state.iso || shutter != state.shutterNs || kelvin != state.wbKelvin) {
                emit { copy(iso = iso, shutterNs = shutter, wbKelvin = kelvin) }
            }
        }
    }

    private fun gainsToKelvin(r: Float, g: Float, b: Float): Int {
        if (r <= 0f || b <= 0f) return 0
        val ratio = (r / b).coerceIn(0.3f, 4f)
        val k = (2000 + (3f - ratio) / 2.5f * 8000f).toInt()
        return k.coerceIn(2000, 10000)
    }

    fun open(cameraManager: CameraManager, surface: Surface) {
        if (cameraDevice != null) return
        bgThread = HandlerThread("ProcamBg").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)
        previewSurface = surface

        try {
            @Suppress("MissingPermission")
            cameraManager.openCamera(CAMERA_ID, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createSession()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close(); cameraDevice = null
                    emit { copy(isOpen = false, error = "Camera disconnected") }
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); cameraDevice = null
                    emit { copy(isOpen = false, error = "Camera error $error") }
                }
            }, bgHandler)
        } catch (t: Throwable) {
            Log.e(TAG, "open failed", t)
            emit { copy(error = t.message) }
        }
    }

    private fun createSession() {
        val device = cameraDevice ?: return
        val preview = previewSurface ?: return

        try {
            requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                applyModes(this)
            }

            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(preview), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        s.setRepeatingRequest(requestBuilder!!.build(), captureCallback, bgHandler)
                        emit { copy(isOpen = true, error = null) }
                    } catch (t: Throwable) {
                        Log.e(TAG, "repeating failed", t)
                        emit { copy(error = t.message) }
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    emit { copy(error = "Session config failed") }
                }
            }, bgHandler)
        } catch (t: Throwable) {
            Log.e(TAG, "session create failed", t)
            emit { copy(error = t.message) }
        }
    }

    private fun applyModes(b: CaptureRequest.Builder) {
        if (manualMode) {
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
        } else {
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }
    }

    fun startRecording(outputFile: File) {
        if (state.isRecording) return
        val device = cameraDevice ?: return
        val preview = previewSurface ?: return

        currentOutputFile = outputFile

        try {
            val videoFormat = MediaFormat.createVideoFormat(
                "video/avc", VIDEO_WIDTH, VIDEO_HEIGHT
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }
            videoEncoder = MediaCodec.createEncoderByType("video/avc").apply {
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
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            videoTrackIndex = -1
            audioTrackIndex = -1
            muxerStarted = false

            @Suppress("DEPRECATION")
            device.createCaptureSession(
                listOf(preview, videoInputSurface!!),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        val rb = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(preview)
                            addTarget(videoInputSurface!!)
                            set(
                                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                Range(FRAME_RATE, FRAME_RATE)
                            )
                            applyModes(this)
                        }
                        try {
                            s.setRepeatingRequest(rb.build(), captureCallback, bgHandler)
                            startEncoderThreads()
                            audioRecording = true
                            startAudioCapture()
                            startTimeMs = System.currentTimeMillis()
                            emit { copy(isRecording = true, durationMs = 0L, error = null) }
                            startTimer()
                        } catch (t: Throwable) {
                            Log.e(TAG, "recording start failed", t)
                            emit { copy(error = t.message) }
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        emit { copy(error = "Record session config failed") }
                    }
                },
                bgHandler
            )
        } catch (t: Throwable) {
            Log.e(TAG, "startRecording failed", t)
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
                val f = videoEncoder!!.outputFormat
                videoTrackIndex = mux.addTrack(f)
            }
            if (audioTrackIndex < 0) {
                val f = audioEncoder!!.outputFormat
                audioTrackIndex = mux.addTrack(f)
            }
            if (videoTrackIndex >= 0 && audioTrackIndex >= 0) {
                mux.start()
                muxerStarted = true
            } else return
        }
        val track = if (isVideo) videoTrackIndex else audioTrackIndex
        if (track < 0) return
        mux.writeSampleData(track, buf, info)
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
                    emit { copy(audioLevelL = dbL.coerceIn(0f, 1f), audioLevelR = dbR.coerceIn(0f, 1f)) }
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
        try {
            audioRecording = false
            audioThread?.join(500)
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            videoEncoder?.signalEndOfInputStream()

            val ve = videoEncoder
            videoEncoder = null
            ve?.stop()
            ve?.release()

            val ae = audioEncoder
            audioEncoder = null
            ae?.stop()
            ae?.release()

            if (muxerStarted) muxer?.stop()
            muxer?.release()
            muxer = null
            muxerStarted = false
            videoTrackIndex = -1
            audioTrackIndex = -1

            videoInputSurface?.release()
            videoInputSurface = null

            currentOutputFile?.let { file ->
                val uri = addToGallery(file)
                emit { copy(isRecording = false, durationMs = 0L, audioLevelL = 0f, audioLevelR = 0f, lastFile = file.absolutePath, lastUri = uri) }
            } ?: emit { copy(isRecording = false, durationMs = 0L, audioLevelL = 0f, audioLevelR = 0f) }

            cameraDevice?.let {
                session?.close()
                createSession()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "stopRecording", t)
        }
    }

    private fun addToGallery(file: File): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Procam")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            file.delete()
            uri
        } catch (t: Throwable) {
            Log.e(TAG, "addToGallery failed", t)
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
            session?.close(); session = null
            cameraDevice?.close(); cameraDevice = null
            bgThread?.quitSafely(); bgThread = null
            bgHandler = null
        } catch (_: Throwable) {
        }
        emit { copy(isOpen = false) }
    }
}
