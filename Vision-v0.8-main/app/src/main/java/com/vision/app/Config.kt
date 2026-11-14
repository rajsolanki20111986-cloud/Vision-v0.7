package com.vision.app

/**
 * Settings you edit by hand.
 * PICOVOICE_KEY: free AccessKey from console.picovoice.ai. Needed for the "Jarvis" wake word.
 * Leave it blank and the wake word stays off; the rest of Vision still works.
 */
object Config {
    const val PICOVOICE_KEY = ""
    const val WAKE_WORD_ENABLED = true
}
