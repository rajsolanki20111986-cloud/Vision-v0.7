package com.vision.app

import android.app.Application

class VisionApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        MemoryStore.init(this)
        AgentStore.init(this)
    }
}
