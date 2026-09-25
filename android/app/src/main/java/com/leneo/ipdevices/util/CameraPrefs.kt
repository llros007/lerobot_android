package com.leneo.ipdevices.util

import android.content.Context

object CameraPrefs {
    private const val FILE = "camera_selection"
    const val KEY_BACK = "enable_back"
    const val KEY_FRONT = "enable_front"

    fun backEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BACK, true)

    fun frontEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FRONT, false)

    fun setFacingEnabled(context: Context, facing: String, enabled: Boolean) {
        val key = when (facing) {
            "front" -> KEY_FRONT
            else -> KEY_BACK
        }
        prefs(context).edit().putBoolean(key, enabled).apply()
    }

    fun isFacingEnabled(context: Context, facing: String): Boolean = when (facing) {
        "front" -> frontEnabled(context)
        else -> backEnabled(context)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
