package com.leneo.ipdevices

import android.app.Application

class IpDevicesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: IpDevicesApp
            private set
    }
}
