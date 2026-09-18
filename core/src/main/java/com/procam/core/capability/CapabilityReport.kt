package com.procam.core.capability

data class CapabilityReport(
    val brand: String,
    val model: String,
    val androidSdk: Int,
    val camera2Level: Camera2Level,
    val cameras: List<CameraCapability>,
    val encoders: EncoderCapability,
    val audio: AudioCapability,
    val quirks: Set<DeviceQuirk>,
    val tier: DeviceTier
)

enum class Camera2Level { LEGACY, LIMITED, FULL, LEVEL_3 }
enum class DeviceTier { BASIC, STANDARD, PRO }

data class CameraCapability(
    val id: String,
    val level: Camera2Level,
    val facing: Int,
    val focalLengths: List<Float>,
    val sensorSize: Pair<Float, Float>?,
    val supportsRaw: Boolean,
    val supportsManualSensor: Boolean,
    val supportsManualPost: Boolean,
    val supportedSizes: List<Pair<Int, Int>>,
    val supportedFps: List<IntRange>
)

data class EncoderCapability(
    val hevcHardware: Boolean,
    val avcHardware: Boolean,
    val av1Hardware: Boolean,
    val supports10BitHevc: Boolean,
    val maxHevcBitrate: Int
)

data class AudioCapability(
    val sampleRates: List<Int>,
    val supportsStereo: Boolean,
    val supportsUsbMic: Boolean
)

enum class DeviceQuirk {
    NO_MANUAL_CONTROL,
    HEVC_CRASH_AT_4K,
    AUDIO_VIDEO_DESYNC,
    SLOW_LENS_SWITCH,
    REQUIRES_LEGACY_PATH
}
