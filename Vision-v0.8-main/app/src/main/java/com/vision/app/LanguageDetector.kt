package com.vision.app

import android.icu.util.ULocale

/**
 * Detects the language of incoming text and tells Vision to reply in that language.
 * If friend sends in Bengali, Vision replies in Bengali.
 * If in Hindi, replies in Hindi.
 */
object LanguageDetector {

    fun detectLanguage(text: String): String {
        if (text.isEmpty()) return "en"
        val first = text.take(200) // First 200 chars
        val hindiMarks = first.count { it in '\u0900'..'\u097F' } // Devanagari
        val bengaliMarks = first.count { it in '\u0980'..'\u09FF' } // Bengali
        val gujaratiMarks = first.count { it in '\u0A80'..'\u0AFF' } // Gujarati
        val englishChars = first.count { it.isLetter() && it in 'a'..'z' || it in 'A'..'Z' }

        return when {
            hindiMarks > first.length * 0.3 -> "hi" // 30% Devanagari = Hindi
            bengaliMarks > first.length * 0.3 -> "bn" // Bengali
            gujaratiMarks > first.length * 0.3 -> "gu" // Gujarati
            englishChars > first.length * 0.5 -> "en" // 50% English
            else -> "hi" // Default to Hindi
        }
    }

    fun getLanguagePrompt(lang: String): String = when (lang) {
        "hi" -> "Reply in Hindi/Hinglish."
        "bn" -> "Reply in Bengali."
        "gu" -> "Reply in Gujarati."
        "ta" -> "Reply in Tamil."
        "te" -> "Reply in Telugu."
        "ml" -> "Reply in Malayalam."
        "mr" -> "Reply in Marathi."
        else -> "Reply in English."
    }
}
