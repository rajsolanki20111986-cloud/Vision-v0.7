package com.vision.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Live conversation in ANY chat app. Nothing is written per app: every step the screen is read,
 * each text line is marked as his side [ME] or the other person's [THEM], and the model decides
 * what to tap or type. The loop checks the screen every few seconds, answers new messages,
 * confirms that its own message appeared, and ends the conversation by itself (done, goodbye,
 * pointless, risky) or when a limit is reached. Limits are settings he can change by chat.
 */
object ScreenAgent {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    @Volatile private var task = ""
    @Volatile private var startedAt = 0L
    @Volatile private var lastActivityAt = 0L
    private val sent = java.util.Collections.synchronizedList(ArrayList<String>())

    private val LINE = Regex("""^(\[\w+\] )?(.*) @(\d+),(\d+)$""")
    private val HEAD = Regex("""screen=(\d+)x(\d+)""")

    fun running(): Boolean = job?.isActive == true

    fun start(ctx: Context, taskText: String): String {
        if (taskText.isBlank()) return "Tell me who to talk to, in which app, and what for."
        if (VisionAccessibilityService.inst == null)
            return "Accessibility service is not running. Ask him to enable Vision Control in Android Accessibility settings."
        if (running()) return "I am already in a conversation: $task. End it first."
        task = taskText.trim()
        sent.clear()
        startedAt = System.currentTimeMillis()
        lastActivityAt = startedAt
        val app = ctx.applicationContext
        job = scope.launch { runLoop(app) }
        return "Started. I open the app, talk, watch for replies and end the conversation myself."
    }

    fun stop(reason: String): String {
        if (!running()) return "No conversation is running."
        job?.cancel()
        finish("Conversation stopped: $reason")
        return "Stopped."
    }

    fun status(): String =
        if (!running()) "No conversation is running."
        else "Running: $task. I have sent ${sent.size} message(s) so far."

    private suspend fun runLoop(app: Context) {
        var lastSig = ""
        var first = true
        var summary = "Conversation ended."
        try {
            while (currentCoroutineContext().isActive) {
                val now = System.currentTimeMillis()
                if (now - startedAt > AgentStore.maxMinutes * 60_000L) { summary = "Stopped after ${AgentStore.maxMinutes} minutes (limit)."; break }
                if (sent.size >= AgentStore.maxMessages) { summary = "Stopped after ${sent.size} messages (limit)."; break }
                if (!first && now - lastActivityAt > AgentStore.idleMinutes * 60_000L) {
                    summary = "Nobody wrote for ${AgentStore.idleMinutes} minutes, so I ended the conversation."; break
                }

                val raw = ToolRouter.run(app, "read_screen", JSONObject())
                if (raw.startsWith("Blocked") || raw.startsWith("Phone control")) { summary = "Stopped: $raw"; break }
                val screenOk = !(raw.startsWith("No screen") || raw.startsWith("Accessibility"))

                if (!first) {
                    if (!screenOk) { delay(AgentStore.watchSeconds * 1000L); continue }
                    val sig = signature(raw)
                    if (sig == lastSig) { delay(AgentStore.watchSeconds * 1000L); continue }
                    lastSig = sig
                    lastActivityAt = System.currentTimeMillis()
                }

                val annotated = if (screenOk) annotate(raw) else "(screen not available yet)"
                val theirs = annotated.lines().filter { it.startsWith("THEM| ") }.takeLast(3)
                    .joinToString("\n") { it.removePrefix("THEM| ").replace(Regex(" @\\d+,\\d+$"), "") }
                if (!first && SafetyRules.refusal(theirs) != null) {
                    summary = "Stopped: they wrote about money or a code. Please look at the chat yourself."
                    break
                }

                val prompt = buildString {
                    append("TASK FROM HIM: ").append(task).append('\n')
                    append(if (first) "FIRST STEP: open the app and the right chat, then send the opening message for the task.\n"
                           else "A change happened on the screen. Decide what to do.\n")
                    append("MESSAGES YOU SENT SO FAR (").append(sent.size).append("):\n")
                    synchronized(sent) { sent.takeLast(8).forEach { append("- ").append(it.take(200)).append('\n') } }
                    append("\nSCREEN NOW:\n").append(annotated)
                }
                val res = ChatEngine.agentLoop(app, system(), prompt, 8) { name, args ->
                    if (name == "type_text") {
                        val t = args.optString("text").trim()
                        if (t.isNotEmpty()) { sent.add(t); lastActivityAt = System.currentTimeMillis() }
                    }
                }
                if (res.isFailure) {
                    summary = "Stopped because of an error: ${res.exceptionOrNull()?.message?.take(150)}"
                    break
                }
                val out = res.getOrNull().orEmpty().trim()
                first = false

                when {
                    out.startsWith("DONE") -> { summary = out; break }
                    out.startsWith("STOP") -> { summary = out; break }
                }
                delay(AgentStore.watchSeconds * 1000L)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            summary = "Stopped because of an error: ${e.message?.take(150)}"
        }
        finish(summary)
    }

    private fun finish(summary: String) {
        AgentStore.note("Conversation: ${summary.take(160)}")
        MemoryStore.addTurn("text", false, "[Agent] $summary (messages sent: ${sent.size})\nTask: ${task.take(160)}")
    }

    private fun system(): String = Persona.PROMPT + """

CONVERSATION MODE. You are talking, as Vision, his AI assistant, with a real person in a chat app on his phone, using the screen tools. You decide everything yourself. He will not answer questions.
- If the right chat is not open, open the app (open_app), find the person (search or the chat list) and open their chat. Work out the app from what the screen shows. Do not assume any app.
- Screen lines are marked THEM| (the other person) or ME| (my side; also anything in the list of messages you sent) or MID| (unclear). A line you did not send is theirs. Never answer your own message.
- To write: tap the message input, type_text, then send with the send button (tap it) or press_enter. After sending, read_screen and check that your text is now the newest ME| line. If it is not, try sending once more, then check again. If it still is not there, answer STOP.
- Do not look for ticks, read receipts or seen marks.
- Answer only the newest THEM| messages. Short, natural, in the language they use. One message per step. If their last message is already answered, send nothing.
- Keep talking while it serves the task. End it yourself when: the task is done; they said ok, thanks or bye; the talk goes nowhere, off-topic, or too long; or they ask for money, codes, passwords, location, or something only he can decide. If it fits, send a short polite goodbye first, then end.
- Never agree to money, meetings or promises for him. Never share private details. If asked, say you are his AI assistant.
- Do not change agent settings or contacts and do not start another conversation.
Your final text for every step must be exactly one of:
WAIT
DONE: <one line summary for him>
STOP: <reason, and what he must do>
WAIT means nothing to do now, waiting for their reply.
""".trimIndent()

    private fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun isMine(text: String): Boolean {
        val t = norm(text).removeSuffix("…")
        if (t.length < 2) return false
        return synchronized(sent) {
            sent.any { val s = norm(it); s == t || (t.length >= 6 && s.startsWith(t)) || (s.length >= 6 && t.contains(s)) }
        }
    }

    /** Marks plain text lines as ME| / THEM| / MID| using what Vision sent and where the text sits on the screen. */
    private fun annotate(raw: String): String {
        val w = HEAD.find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 1080
        val out = StringBuilder()
        for (line in raw.lines()) {
            val m = LINE.find(line)
            if (m == null) { out.append(line).append('\n'); continue }
            val tag = m.groupValues[1]
            val text = m.groupValues[2]
            val x = m.groupValues[3].toIntOrNull() ?: 0
            if (tag.isNotEmpty() || text.startsWith("id:") || text.startsWith("unlabeled")) {
                out.append(line).append('\n'); continue
            }
            val side = when {
                isMine(text) -> "ME"
                x * 100 / w >= 55 -> "ME"
                x * 100 / w <= 45 -> "THEM"
                else -> "MID"
            }
            out.append(side).append("| ").append(text).append(" @").append(x).append(',').append(m.groupValues[4]).append('\n')
        }
        return out.toString()
    }

    /** Changes whenever visible text changes; coordinates and buttons are ignored. */
    private fun signature(raw: String): String =
        raw.lines().mapNotNull { l -> LINE.find(l)?.takeIf { it.groupValues[1].isEmpty() }?.groupValues?.get(2) }
            .joinToString("|").hashCode().toString()
}
