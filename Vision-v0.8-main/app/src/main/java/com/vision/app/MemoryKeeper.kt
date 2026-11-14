package com.vision.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The memory keeper. It runs by itself in the background, so Vision does not have to remember to save.
 * After a voice session ends, or after every 6 of his text messages, it reads the new part of the
 * shared history, asks Gemini (no tools, low thinking) for lasting facts only, and appends them to the
 * memory files. Nothing is rewritten or deleted, repeated lines are skipped, and anything that looks like
 * a code, PIN, bank or money detail is dropped before saving (SafetyRules).
 */
object MemoryKeeper {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = AtomicBoolean(false)
    @Volatile private var doneUntil = 0L

    private const val SYSTEM = """You keep the long-term memory of a personal phone assistant called Vision.
From the conversation, extract ONLY durable facts about the user: who he is, preferences, people in his life, projects and the decisions he made, routines, and standing instructions.
Skip small talk, one-off questions, tool results, and anything Vision said that he did not confirm.
Never include passwords, PINs, OTPs, card or account numbers, or any bank or money detail.
Do not repeat anything listed under ALREADY KNOWN.
Write each fact as one short sentence in the language he used.
Answer with a JSON array of at most 5 objects like {"path":"facts/topic.md","line":"one short sentence"}, or [] if there is nothing new.
Allowed paths: profile.md (who he is), facts/<topic>.md, routines/<topic>.md, projects/<topic>.md. Use short lowercase topic names with hyphens."""

    fun maybeRun(force: Boolean = false) {
        if (!Prefs.memoryOn || Prefs.apiKey.isBlank()) return
        val fresh = MemoryStore.turns(60).filter { it.ts > doneUntil && !it.text.startsWith("[Agent]") }
        val userCount = fresh.count { it.user }
        if (userCount == 0) return
        if (!force && userCount < 6) return
        if (!busy.compareAndSet(false, true)) return
        scope.launch {
            try {
                val convo = fresh.joinToString("\n") { (if (it.user) "User: " else "Vision: ") + it.text.take(400) }
                val prompt = "ALREADY KNOWN:\n" + MemoryStore.knownSummary() + "\n\nCONVERSATION:\n" + convo
                val raw = ChatEngine.extract(SYSTEM, prompt).getOrNull()
                if (raw != null) {
                    apply(raw)
                    doneUntil = fresh.last().ts
                }
            } catch (e: Exception) {
                // Best effort: the next run tries again with the same turns.
            } finally {
                busy.set(false)
            }
        }
    }

    private fun apply(raw: String) {
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val arr = runCatching { JSONArray(clean) }.getOrNull() ?: return
        var saved = 0
        for (i in 0 until arr.length()) {
            if (saved >= 5) break
            val o = arr.optJSONObject(i) ?: continue
            val path = o.optString("path").trim()
            val line = o.optString("line").trim().take(300)
            if (line.length < 6) continue
            if (SafetyRules.refusal(line) != null) continue
            val okPath = path == "profile.md" || path.startsWith("facts/") ||
                path.startsWith("routines/") || path.startsWith("projects/")
            if (!okPath) continue
            if (MemoryStore.write(path, "- $line", append = true).startsWith("Saved")) saved++
        }
    }
}
