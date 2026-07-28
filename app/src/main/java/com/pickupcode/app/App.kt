package com.pickupcode.app

import android.app.Application
import com.pickupcode.app.notification.CodeNotificationManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        CodeNotificationManager.createChannels(this)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
