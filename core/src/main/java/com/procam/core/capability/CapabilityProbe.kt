package com.procam.core.capability

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
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
        val level = cameras.minByOrNull { it.level.ordinal }?.level ?: Camera2Level.LEGACY
        val encoders = probeEncoders()
        val audio = probeAudio()
        val quirks = DeviceQuirkRegistry.lookup(Build.MANUFACTURER, Build.MODEL)
        val tier = when {
            level == Camera2Level.LEVEL_3 && encoders.hevcHardware &&
                !quirks.contains(DeviceQuirk.NO_MANUAL_CONTROL) -> DeviceTier.PRO
            level >= Camera2Level.FULL && encoders.hevcHardware -> DeviceTier.STANDARD
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

    private fun probeCamera(manager: CameraManager, id: String): CameraCapability? {
        val chars = manager.getCameraCharacteristics(id)
        val lv = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: return null
        val level = when (lv) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> Camera2Level.LEGACY
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> Camera2Level.LIMITED
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> Camera2Level.FULL
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> Camera2Level.LEVEL_3
            else -> Camera2Level.LEGACY
        }
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(MediaRecorder::class.java)
            ?.map { it.width to it.height } ?: emptyList()
        val fps = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map { it.lower..it.upper } ?: emptyList()
        return CameraCapability(
            id = id,
            level = level,
            facing = chars.get(CameraCharacteristics.LENS_FACING) ?: -1,
            focalLengths = (chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?: floatArrayOf()).toList(),
            sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                ?.let { it.width to it.height },
            supportsRaw = caps.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
            ),
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
        var hevc = false; var avc = false; var av1 = false
        var hevc10 = false; var maxBr = 0
        for (info in MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos) {
            if (!info.isEncoder) continue
            val hw = !info.name.startsWith("OMX.google") &&
                     !info.name.startsWith("c2.android")
            for (type in info.supportedTypes) when (type) {
                "video/hevc" -> if (hw) {
                    hevc = true
                    runCatching {
                        val caps = info.getCapabilitiesForType(type)
                        hevc10 = caps.profileLevels.any {
                            it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                        }
                        maxBr = caps.videoCapabilities?.bitrateRange?.upper ?: 0
                    }
                }
                "video/avc" -> if (hw) avc = true
                "video/av01" -> if (hw) av1 = true
            }
        }
        return EncoderCapability(hevc, avc, av1, hevc10, maxBr)
    }

    private fun probeAudio() = AudioCapability(
        sampleRates = listOf(48000, 44100),
        supportsStereo = true,
        supportsUsbMic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    )
}
