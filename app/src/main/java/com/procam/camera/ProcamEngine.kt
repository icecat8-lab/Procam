package com.procam.camera

import android.content.Context
import android.hardware.camera2.*
import android.hardware.camera2.params.RggbChannelVector
import android.media.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

class ProcamEngine(
    private val context: Context,
    private val onState: (State) -> Unit
) {
    data class State(
        val isOpen: Boolean = false,
        val isRecording: Boolean = false,
        val iso: Int = 100,
        val shutterNs: Long = 1_000_000_000L / 30,
        val wbKelvin: Int = 5500,
        val focusDistance: Float = 0f,
        val durationMs: Long = 0L,
        val lastFile: String? = null,
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

    private var manualIso: Int = 100
    private var manualShutterNs: Long = 1_000_000_000L / 30
    private var manualWbKelvin: Int = 5500
    private var manualFocus: Float = 0f
    private var manualMode = true

    private var isoRange: Range<Int> = Range(100, 3200)
    private var shutterRangeNs: Range<Long> = Range(1_000_000L, 500_000_000L)
    private var focusRange: Float = 10f

    private var state = State()
    private fun emit(patch: State.() -> State) {
        state = state.patch()
        onState(state)
    }

    // ==================== LIFECYCLE ====================

    fun open(cameraManager: CameraManager, surface: Surface) {
        bgThread = HandlerThread("ProcamBg").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)
        previewSurface = surface

        try {
            val chars = cameraManager.getCameraCharacteristics(CAMERA_ID)
            isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                ?: Range(100, 3200)
            shutterRangeNs = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                ?: Range(1_000_000L, 500_000_000L)
            focusRange = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

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
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
                applyManual(this)
            }

            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(preview), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        s.setRepeatingRequest(requestBuilder!!.build(), null, bgHandler)
                        emit { copy(isOpen = true) }
                    } catch (t: Throwable) {
                        Log.e(TAG, "repeating failed", t)
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

    private fun applyManual(b: CaptureRequest.Builder) {
        if (!manualMode) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            return
        }
        val iso = manualIso.coerceIn(isoRange.lower, isoRange.upper)
        val shutter = manualShutterNs.coerceIn(shutterRangeNs.lower, shutterRangeNs.upper)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
        b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
        b.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
        val rggb = colorMatrixForKelvin(manualWbKelvin)
        b.set(
            CaptureRequest.COLOR_CORRECTION_GAINS,
            RggbChannelVector(rggb[0], rggb[1], rggb[2], rggb[3])
        )
        if (focusRange > 0f) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocus.coerceIn(0f, focusRange))
        }
    }

    private fun colorMatrixForKelvin(kelvin: Int): FloatArray {
        val k = kelvin / 100f
        val r: Float
        val g: Float
        val b: Float
        if (k <= 66) {
            r = 1f
            g = (0.39008157876f * Math.log(k.toDouble()) - 0.63184144378).toFloat().coerceIn(0f, 1f)
            b = if (k <= 19) 0f
            else (0.543206789110f * Math.log((k - 10).toDouble()) - 1.19625408914).toFloat()
                .coerceIn(0f, 1f)
        } else {
            r = (1.29293618606f * Math.pow(k - 60.0, -0.1332047592)).toFloat().coerceIn(0f, 1f)
            g = (1.12989086089f * Math.pow(k - 60.0, -0.0755148492)).toFloat().coerceIn(0f, 1f)
            b = 1f
        }
        return floatArrayOf(r, g, g, b)
    }

    // ==================== MANUAL CONTROL ====================

    fun setIso(value: Int) {
        manualIso = value
        requestBuilder?.let {
            applyManual(it)
            session?.setRepeatingRequest(it.build(), null, bgHandler)
        }
        emit { copy(iso = value) }
    }

    fun setShutter(ns: Long) {
        manualShutterNs = ns
        requestBuilder?.let {
            applyManual(it)
            session?.setRepeatingRequest(it.build(), null, bgHandler)
        }
        emit { copy(shutterNs = ns) }
    }

    fun setWb(kelvin: Int) {
        manualWbKelvin = kelvin
        requestBuilder?.let {
            applyManual(it)
            session?.setRepeatingRequest(it.build(), null, bgHandler)
        }
        emit { copy(wbKelvin = kelvin) }
    }

    fun setFocus(distance: Float) {
        manualFocus = distance
        requestBuilder?.let {
            applyManual(it)
            session?.setRepeatingRequest(it.build(), null, bgHandler)
        }
        emit { copy(focusDistance = distance) }
    }

    fun setManualMode(on: Boolean) {
        manualMode = on
        requestBuilder?.let {
            applyManual(it)
            session?.setRepeatingRequest(it.build(), null, bgHandler)
        }
    }

    // ==================== RECORDING ====================

    fun startRecording(outputFile: File) {
        if (state.isRecording) return
        val device = cameraDevice ?: return
        val preview = previewSurface ?: return

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
                            applyManual(this)
                        }
                        try {
                            s.setRepeatingRequest(rb.build(), null, bgHandler)
                            startEncoderThreads()
                            audioRecording = true
                            startAudioCapture()
                            startTimeMs = System.currentTimeMillis()
                            emit { copy(isRecording = true, error = null) }
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

            emit { copy(isRecording = false, lastFile = state.lastFile) }

            cameraDevice?.let {
                session?.close()
                createSession()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "stopRecording", t)
        }
    }

    private fun startTimer() {
        bgHandler?.post(object : Runnable {
            override fun run() {
                if (state.isRecording) {
                    val elapsed = System.currentTimeMillis() - startTimeMs
                    emit { copy(durationMs = elapsed) }
                    bgHandler?.postDelayed(this, 100)
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
