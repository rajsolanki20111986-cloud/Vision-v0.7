package com.vision.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/**
 * Makes Vision appear in Android's "Digital assistant app" list, so it can be chosen for the
 * long-press on the power button (or the home button / gesture). Choosing it replaces Google there.
 */
class VisionAssistService : VoiceInteractionService()

class VisionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = VisionSession(this)
}

/** Runs when the button is long-pressed: starts the live voice session, or opens the app if Android refuses. */
class VisionSession(private val ctx: Context) : VoiceInteractionSession(ctx) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val started = try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, VoiceService::class.java))
            true
        } catch (e: Exception) {
            false
        }
        if (!started) {
            try {
                startAssistantActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                // nothing else to do
            }
        }
        hide()
    }
}

/** Android requires an assistant to declare a recognizer. This one does nothing; Vision uses Gemini Live for speech. */
class VisionRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
