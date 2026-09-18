package com.procam.core.capability

object DeviceQuirkRegistry {
    private val table: Map<String, Set<DeviceQuirk>> = mapOf(
        "samsung:sm-g991b" to setOf(DeviceQuirk.SLOW_LENS_SWITCH),
        "xiaomi:redmi note 10" to setOf(
            DeviceQuirk.NO_MANUAL_CONTROL,
            DeviceQuirk.REQUIRES_LEGACY_PATH
        ),
        "oppo:cph2451" to setOf(DeviceQuirk.AUDIO_VIDEO_DESYNC)
    )

    fun lookup(brand: String, model: String): Set<DeviceQuirk> =
        table["${brand.lowercase()}:${model.lowercase()}"] ?: emptySet()
}
