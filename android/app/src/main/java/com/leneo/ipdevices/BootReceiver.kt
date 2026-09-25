package com.leneo.ipdevices

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("ip_devices", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("start_on_boot", false)) return
        // A camera foreground service cannot be created from BOOT_COMPLETED
        // on Android 14+. Wait until the user opens the activity, where the
        // while-in-use CAMERA permission is active.
        if (Build.VERSION.SDK_INT >= 34) return
        val service = Intent(context, BridgeService::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            ContextCompat.startForegroundService(context, service)
        } else {
            context.startService(service)
        }
    }
}
