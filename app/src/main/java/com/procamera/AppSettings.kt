package com.procamera

import android.content.Context

object AppSettings {
    private const val PREFS = "procam_settings"
    private const val KEY_RAW_ENABLED = "raw_enabled"

    fun isRawEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_RAW_ENABLED, true)

    fun setRawEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RAW_ENABLED, enabled)
            .apply()
    }
}
