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
