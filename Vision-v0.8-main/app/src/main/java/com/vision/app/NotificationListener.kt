package com.vision.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow

/** One incoming notification from any app. id makes every item distinct, so the flow always emits. */
data class IncomingMessage(val app: String, val sender: String, val text: String, val id: Long = System.nanoTime())

/** Forwards notifications from all apps to the voice session, except Vision itself and money apps. */
class VisionNotificationListener : NotificationListenerService() {

    companion object {
        val newMessage = MutableStateFlow<IncomingMessage?>(null)
        private val BLOCK = listOf("paisa", "phonepe", "paytm", "paypal", "bank", "wallet", "upi", "razorpay")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return // never announce Vision's own notifications
        if (BLOCK.any { pkg.contains(it, ignoreCase = true) }) return
        if (sbn.isOngoing) return // music players, navigation, downloads
        // Agent mode first: it works in the background, even when message alerts are off.
        if (Prefs.agentOn && AgentBrain.offer(applicationContext, sbn)) return
        if (!Prefs.notifyAlerts) return

        val extras = sbn.notification?.extras ?: return
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT) ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
            ?.toString()?.trim()
        if (text.isNullOrBlank()) return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val sender = if (title.isNullOrBlank()) pkg else title

        val app = runCatching {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)

        newMessage.value = IncomingMessage(app, sender, text)
    }
}
