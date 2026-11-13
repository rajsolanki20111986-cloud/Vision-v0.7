package com.vision.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback

/**
 * Always-on "Jarvis" listener. It runs on the phone only, so nothing is sent to the
 * internet until the word is heard. When it hears the word, it starts VoiceService.
 */
class WakeWordService : Service() {
    private var manager: PorcupineManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Prefs.init(this)
        val n = notification()
        if (Build.VERSION.SDK_INT >= 30) startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(2, n)

        if (!Config.WAKE_WORD_ENABLED || Config.PICOVOICE_KEY.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (manager != null) return START_STICKY

        try {
            manager = PorcupineManager.Builder()
                .setAccessKey(Config.PICOVOICE_KEY)
                .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                .build(this, PorcupineManagerCallback { _ -> onWake() })
            manager?.start()
        } catch (e: Exception) {
            manager = null
            stopSelf()
        }
        return START_STICKY
    }

    private fun onWake() {
        // Release the mic first: the voice session needs it.
        runCatching { manager?.stop() }
        manager?.delete(); manager = null
        val i = Intent(this, VoiceService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { manager?.stop() }
        manager?.delete(); manager = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("vision_wake", "Vision wake word", NotificationManager.IMPORTANCE_MIN))
        return Notification.Builder(this, "vision_wake")
            .setContentTitle("Vision is waiting for \"Jarvis\"")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }
}
