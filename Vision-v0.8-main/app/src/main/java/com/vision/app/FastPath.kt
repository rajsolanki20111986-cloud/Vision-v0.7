package com.vision.app

import android.content.Context
import org.json.JSONObject
import java.text.Normalizer

/**
 * Plain commands that need no thinking: torch, volume, music keys, battery, open app.
 * They run straight on the phone without asking the model, so they answer almost at once.
 *
 * It is deliberately strict: EVERY word of the message must belong to the command's own vocabulary.
 * Questions, "don't ...", names of songs or people, and long sentences contain other words, so they
 * are not matched here and go to the model as before.
 */
object FastPath {
    private fun n(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC).lowercase()
    private fun words(vararg w: String): Set<String> = w.map { n(it) }.toSet()
    private val SPLIT = Regex("[^\\p{L}\\p{M}\\p{N}]+")

    private val FILL = words(
        "please", "plz", "pls", "the", "a", "my", "karo", "kar", "do", "kardo", "dena", "de", "zara", "jara",
        "phone", "vision", "hey", "hi", "ok", "okay", "ab", "jaldi", "turn", "switch", "set", "put", "make", "it",
        "ko", "ka", "ki", "करो", "कर", "दो", "दे", "देना", "कृपया", "प्लीज", "फोन", "फ़ोन", "विजन", "अब", "जल्दी", "ज़रा", "जरा"
    )

    private val TORCH = words("torch", "flashlight", "flash", "tourch", "टॉर्च", "टार्च", "फ्लैशलाइट", "फ्लैश")
    private val ON = words("on", "chalu", "chalao", "jalao", "jala", "ऑन", "चालू", "चलाओ", "जलाओ", "जला")
    private val OFF = words("off", "band", "bujhao", "bujha", "ऑफ", "बंद", "बुझाओ", "बुझा")

    private val VOL = words("volume", "वॉल्यूम", "awaz", "avaaz", "आवाज़", "आवाज")
    private val UP = words("up", "increase", "raise", "badhao", "badha", "zyada", "jyada", "louder", "बढ़ाओ", "बढ़ा", "ज़्यादा", "ज्यादा", "तेज़", "तेज")
    private val DOWN = words("down", "decrease", "lower", "kam", "ghatao", "ghata", "quieter", "कम", "घटाओ", "घटा", "धीमा")
    private val VOL_FILL = words("thoda", "थोड़ा", "aur", "और", "more", "little", "bit", "bahut", "बहुत")

    private val NEXT = words("next", "skip", "agla", "agle", "अगला", "अगले", "अगली")
    private val PREV = words("previous", "prev", "pichla", "pichle", "पिछला", "पिछले")
    private val PAUSE = words("pause", "roko", "ruko", "रोको", "रुको", "पॉज़", "पॉज")
    private val PLAY = words("play", "resume", "chalao", "chala", "चलाओ", "चला")
    private val MEDIA_NOUN = words("song", "songs", "music", "gana", "gaana", "gane", "track", "गाना", "गाने", "संगीत", "म्यूजिक", "वीडियो", "video")

    private val BATTERY = words("battery", "बैटरी")
    private val BATTERY_FILL = words(
        "kitni", "kitna", "hai", "level", "percent", "status", "check", "how", "much", "what", "is", "whats", "s",
        "tell", "me", "batao", "bata", "कितनी", "कितना", "है", "लेवल", "प्रतिशत", "बताओ", "बता"
    )

    private val OPEN_W = words("open", "launch", "start", "kholo", "khol", "खोलो", "खोल")
    private val APP_W = words("app", "application", "ऐप", "एप", "ऐप्लिकेशन")

    /** Returns the reply text when the message was a plain command and it ran, otherwise null. */
    suspend fun tryHandle(ctx: Context, text: String): String? {
        val toks = n(text).split(SPLIT).filter { it.isNotEmpty() }
        if (toks.isEmpty() || toks.size > 7) return null
        val hi = LanguageDetector.detectLanguage(text) == "hi"

        // Torch
        if (toks.any { it in TORCH } && toks.all { it in FILL || it in TORCH || it in ON || it in OFF }) {
            val on = toks.any { it in ON }
            val off = toks.any { it in OFF }
            if (on == off) return null
            val r = ToolRouter.run(ctx, "torch", JSONObject().put("state", if (on) "on" else "off"))
            if (!r.startsWith("Torch on") && !r.startsWith("Torch off")) return r
            return if (hi) (if (on) "टॉर्च चालू कर दी।" else "टॉर्च बंद कर दी।") else (if (on) "Torch on." else "Torch off.")
        }

        // Volume
        if (toks.any { it in VOL } && toks.all { it in FILL || it in VOL || it in UP || it in DOWN || it in VOL_FILL }) {
            val up = toks.any { it in UP }
            val down = toks.any { it in DOWN }
            if (up == down) return null
            ToolRouter.run(ctx, "volume", JSONObject().put("direction", if (up) "up" else "down"))
            return if (hi) (if (up) "आवाज़ बढ़ा दी।" else "आवाज़ कम कर दी।") else (if (up) "Volume up." else "Volume down.")
        }

        // Battery
        if (toks.any { it in BATTERY } && toks.all { it in FILL || it in BATTERY || it in BATTERY_FILL }) {
            return ToolRouter.run(ctx, "battery", JSONObject())
        }

        // Music keys
        if (toks.any { it in NEXT || it in PREV || it in PAUSE || it in PLAY } &&
            toks.all { it in FILL || it in NEXT || it in PREV || it in PAUSE || it in PLAY || it in MEDIA_NOUN }
        ) {
            val next = toks.any { it in NEXT }
            val prev = toks.any { it in PREV }
            val toggle = toks.any { it in PAUSE || it in PLAY }
            val kinds = listOf(next, prev, toggle).count { it }
            if (kinds != 1) return null
            val action = if (next) "next" else if (prev) "previous" else "play_pause"
            ToolRouter.run(ctx, "media", JSONObject().put("action", action))
            return when (action) {
                "next" -> if (hi) "अगला गाना।" else "Next."
                "previous" -> if (hi) "पिछला गाना।" else "Previous."
                else -> if (hi) "हो गया।" else "Done."
            }
        }

        // Open an app: "open youtube", "youtube kholo"
        if (toks.any { it in OPEN_W }) {
            val core = toks.filter { it !in FILL && it !in OPEN_W && it !in APP_W }
            val atEdge = toks.first() in OPEN_W || toks.last() in OPEN_W || toks.last() in FILL
            if (core.isNotEmpty() && core.size <= 3 && atEdge) {
                val r = ToolRouter.run(ctx, "open_app", JSONObject().put("name", core.joinToString(" ")))
                if (r.startsWith("Opened")) {
                    val label = r.removePrefix("Opened ").substringBefore(". Call read_screen")
                    return if (hi) "$label खोल दिया।" else "Opened $label."
                }
                if (r.startsWith("Launch blocked")) return r
            }
        }
        return null
    }
}
