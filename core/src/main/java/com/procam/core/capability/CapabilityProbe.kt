package com.procam.core.capability

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.os.Build

object CapabilityProbe {

    fun probe(context: Context): CapabilityReport {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameras = manager.cameraIdList.mapNotNull { id ->
            runCatching { probeCamera(manager, id) }.getOrNull()
        }

        val primary = cameras.firstOrNull {
            it.facing == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameras.firstOrNull()

        val level = primary?.level ?: Camera2Level.LEGACY
        val encoders = probeEncoders()
        val audio = probeAudio(context)
        val quirks = DeviceQuirkRegistry.lookup(Build.MANUFACTURER, Build.MODEL)

        val tier = when {
            level >= Camera2Level.LEVEL_3 && encoders.hevcHardware &&
                !quirks.contains(DeviceQuirk.NO_MANUAL_CONTROL) -> DeviceTier.PRO
            level >= Camera2Level.FULL && encoders.avcHardware -> DeviceTier.STANDARD
            else -> DeviceTier.BASIC
        }

        return CapabilityReport(
            brand = Build.MANUFACTURER,
            model = Build.MODEL,
            androidSdk = Build.VERSION.SDK_INT,
            camera2Level = level,
            cameras = cameras,
            encoders = encoders,
            audio = audio,
            quirks = quirks,
            tier = tier
        )
    }

    private fun probeCamera(manager: CameraManager, id: String): CameraCapability {
        val chars = manager.getCameraCharacteristics(id)
        val level = when (
            chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
        ) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> Camera2Level.LIMITED
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> Camera2Level.FULL
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> Camera2Level.LEVEL_3
            else -> Camera2Level.LEGACY
        }

        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: intArrayOf()
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        val sizes = map?.getOutputSizes(MediaRecorder::class.java)
            ?.map { it.width to it.height }
            ?.distinct()
            ?: emptyList()

        val fps = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map { it.lower..it.upper }
            ?: emptyList()

        return CameraCapability(
            id = id,
            level = level,
            facing = chars.get(CameraCharacteristics.LENS_FACING) ?: -1,
            focalLengths = (chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?: floatArrayOf()).toList(),
            sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                ?.let { it.width to it.height },
            supportsRaw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW),
            supportsManualSensor = caps.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
            ),
            supportsManualPost = caps.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING
            ),
            supportedSizes = sizes,
            supportedFps = fps
        )
    }

    private fun probeEncoders(): EncoderCapability {
        var hevc = false
        var avc = false
        var av1 = false
        var hevc10 = false
        var maxHevcBitrate = 0

        for (info in MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos) {
            if (!info.isEncoder) continue
            for (type in info.supportedTypes) {
                when (type.lowercase()) {
                    MediaFormatCompat.HEVC -> {
                        if (isHardware(info)) {
                            hevc = true
                            runCatching {
                                val caps = info.getCapabilitiesForType(type)
                                hevc10 = hevc10 || caps.profileLevels.any {
                                    it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                                }
                                maxHevcBitrate = maxOf(
                                    maxHevcBitrate,
                                    caps.videoCapabilities?.bitrateRange?.upper ?: 0
                                )
                            }
                        }
                    }
                    MediaFormatCompat.AVC -> if (isHardware(info)) avc = true
                    MediaFormatCompat.AV1 -> if (isHardware(info)) av1 = true
                }
            }
        }

        return EncoderCapability(
            hevcHardware = hevc,
            avcHardware = avc,
            av1Hardware = av1,
            supports10BitHevc = hevc10,
            maxHevcBitrate = maxHevcBitrate
        )
    }

    private fun isHardware(info: MediaCodecInfo): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            !info.isSoftwareOnly
        } else {
            !info.name.startsWith("OMX.google.", true) &&
                !info.name.startsWith("c2.android.", true)
        }
    }

    private fun probeAudio(context: Context): AudioCapability {
        val rates = listOf(48_000, 44_100).filter { rate ->
            AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ) > 0
        }

        val stereo = AudioRecord.getMinBufferSize(
            48_000,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ) > 0

        val usb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).any {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
        } else false

        return AudioCapability(
            sampleRates = rates,
            supportsStereo = stereo,
            supportsUsbMic = usb
        )
    }

    private object MediaFormatCompat {
        const val AVC = "video/avc"
        const val HEVC = "video/hevc"
        const val AV1 = "video/av01"
    }
}
