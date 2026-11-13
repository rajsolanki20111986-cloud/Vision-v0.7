package com.vision.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Encrypted settings: API key, voice, feature switches and the Gmail token. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(c: Context) {
        if (::sp.isInitialized) return
        val app = c.applicationContext
        val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        sp = EncryptedSharedPreferences.create(
            app, "vision_prefs", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var apiKey: String
        get() = sp.getString("api", "") ?: ""
        set(v) = sp.edit().putString("api", v.trim()).apply()

    var voice: String
        get() = sp.getString("voice", "Charon") ?: "Charon"
        set(v) = sp.edit().putString("voice", v.trim()).apply()

    /** Phone control switch: when false, every screen tool refuses to act. */
    var controlEnabled: Boolean
        get() = sp.getBoolean("control", true)
        set(v) = sp.edit().putBoolean("control", v).apply()

    /** Memory switch: when false, memory files are not sent to the model and memory tools refuse. */
    var memoryOn: Boolean
        get() = sp.getBoolean("memory", true)
        set(v) = sp.edit().putBoolean("memory", v).apply()

    /** Tell the user about incoming messages from the notification listener. */
    var notifyAlerts: Boolean
        get() = sp.getBoolean("notify", true)
        set(v) = sp.edit().putBoolean("notify", v).apply()

    /** Agent mode: Vision answers chat messages by itself for the people he named. Off until he turns it on. */
    var agentOn: Boolean
        get() = sp.getBoolean("agent_on", false)
        set(v) = sp.edit().putBoolean("agent_on", v).apply()

    var gmailEnabled: Boolean
        get() = sp.getBoolean("gmail_on", false)
        set(v) = sp.edit().putBoolean("gmail_on", v).apply()

    var gmailToken: String
        get() = sp.getString("gmail_token", "") ?: ""
        set(v) = sp.edit().putString("gmail_token", v.trim()).apply()
}
