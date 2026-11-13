package com.vision.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow

/** Shared live state read by the UI. */
object VisionCore {
    val state = MutableStateFlow(LiveState.Disconnected)
    val caption = MutableStateFlow("")
    /** Microphone and speaker switches shown on the Voice screen. */
    val micOn = MutableStateFlow(true)
    val speakerOn = MutableStateFlow(true)
}

/**
 * Foreground (microphone) service that owns the live session, so Vision keeps
 * listening while it operates other apps like YouTube.
 */
class VoiceService : Service() {
    private var session: LiveSession? = null
    private var lastUser: Boolean? = null
    private var buf = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Prefs.init(this)
        val n = notification()
        if (Build.VERSION.SDK_INT >= 30) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)

        session?.stop()
        session = LiveSession(
            this,
            onState = { s ->
                VisionCore.state.value = s
                if (s == LiveState.Disconnected) stopSelf()
            },
            onCaption = { text, user ->
                buf = if (lastUser == user) buf + text else text
                lastUser = user
                VisionCore.caption.value = (if (user) "You: " else "Vision: ") + buf
            }
        ).also { it.connect(Prefs.apiKey, Prefs.voice) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        session?.stop()
        VisionCore.state.value = LiveState.Disconnected
        // Go back to listening for "Jarvis" once the voice session ends.
        if (Config.WAKE_WORD_ENABLED && Config.PICOVOICE_KEY.isNotBlank()) {
            runCatching { startForegroundService(Intent(this, WakeWordService::class.java)) }
        }
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("vision", "Vision", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "vision")
            .setContentTitle("Vision is listening")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
