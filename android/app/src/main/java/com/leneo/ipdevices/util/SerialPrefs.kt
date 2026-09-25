package com.leneo.ipdevices.util

import android.content.Context

object SerialPrefs {
    private const val FILE = "serial_ports"
    const val MIN_PORT = 9000
    const val MAX_PORT = 9010
    val PORTS: IntArray = (MIN_PORT..MAX_PORT).toList().toIntArray()

    fun getPort(context: Context, serialKey: String): Int? {
        val raw = prefs(context).getInt(serialKey, -1)
        return raw.takeIf { it in MIN_PORT..MAX_PORT }
    }

    fun setPort(context: Context, serialKey: String, port: Int) {
        if (port !in MIN_PORT..MAX_PORT) return
        prefs(context).edit().putInt(serialKey, port).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
