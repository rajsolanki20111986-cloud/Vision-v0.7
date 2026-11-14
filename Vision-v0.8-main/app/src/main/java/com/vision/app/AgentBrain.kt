package com.vision.app

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The agent: it watches incoming chat notifications (also in the background), decides if Vision
 * may answer that person, writes the reply with Gemini and sends it through the notification's
 * own Reply button, so no chat app has to be opened.
 *
 * Who gets a reply:
 *  - a person he added with agent_contact_set: after that person's min_messages unanswered messages (default 1)
 *  - an unknown person who writes "hello Vision" (or another name word): a polite "I don't know you, who
 *    are you?" reply, and he is told in the chat
 *  - an unknown person after unknown_after unanswered messages, only if he set that number (default 0 = off)
 *  - everybody else is ignored and handled by the old "tell me about it" flow
 * Group chats are never answered. Messages about money or codes are never answered (SafetyRules).
 */
object AgentBrain {
    private enum class Mode { KNOWN, UNKNOWN_ADDRESSED, UNKNOWN_ENGAGED, UNKNOWN_PERSISTENT }

    private class Handle(val action: Notification.Action)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handles = ConcurrentHashMap<String, Handle>()
    private val lastTs = ConcurrentHashMap<String, Long>()
    private val unreplied = ConcurrentHashMap<String, Int>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val sentTexts = ConcurrentHashMap<String, MutableList<String>>()
    private val limitAlert = ConcurrentHashMap<String, Long>()

    /** Returns true when the agent took the notification (handled or an echo of its own reply). */
    fun offer(ctx: Context, sbn: StatusBarNotification): Boolean {
        if (!Prefs.agentOn) return false
        val n = sbn.notification ?: return false
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        val extras = n.extras ?: return false
        if (extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)) return false
        if (n.category != Notification.CATEGORY_MESSAGE && !extras.containsKey(Notification.EXTRA_MESSAGES)) return false
        val action = findReply(n) ?: return false

        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val title = rawTitle.replace(Regex("""\s*\(\d+ (new )?messages?\)$"""), "").trim()
        if (title.isEmpty()) return false
        val conv = sbn.packageName + "|" + title.lowercase()
        handles[conv] = Handle(action)

        val incoming = newIncoming(extras, conv, sbn.postTime, extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        if (incoming.isEmpty()) return true // a repost or the echo of our own reply
        incoming.forEach { AgentStore.addLine(conv, false, it) }
        val count = unreplied.merge(conv, incoming.size) { a, b -> a + b } ?: incoming.size

        val contact = AgentStore.match(title)
        val joined = incoming.joinToString("\n")
        val mode: Mode? = when {
            contact != null -> if (contact.enabled && count >= contact.minMessages) Mode.KNOWN else null
            AgentStore.engagedRecently(conv) -> Mode.UNKNOWN_ENGAGED
            AgentStore.replyOnName && addressed(joined) -> Mode.UNKNOWN_ADDRESSED
            AgentStore.unknownAfter > 0 && count >= AgentStore.unknownAfter -> Mode.UNKNOWN_PERSISTENT
            else -> null
        }
        if (mode == null) return false

        jobs[conv]?.cancel()
        jobs[conv] = scope.launch {
            if (AgentStore.delaySec > 0) delay(AgentStore.delaySec * 1000L) // he sets this; default 0
            process(ctx.applicationContext, conv, title, mode, contact)
        }
        return true
    }

    private suspend fun process(ctx: Context, conv: String, title: String, mode: Mode, contact: AgentContact?) {
        val h = handles[conv] ?: return
        val lines = AgentStore.lines(conv, 14)
        val lastIncoming = lines.takeLastWhile { !it.mine }.joinToString("\n") { it.text }

        // Hard limits first, before any model is asked.
        SafetyRules.refusal(lastIncoming)?.let {
            alert("$title wrote about money or a code, so I did not reply by myself: ${lastIncoming.take(120)}")
            return
        }
        if (!AgentStore.canSend(conv)) {
            val now = System.currentTimeMillis()
            if (now - (limitAlert[conv] ?: 0L) > 3_600_000L) {
                limitAlert[conv] = now
                alert("I reached the reply limit for $title this hour, so I stopped answering. Last message: ${lastIncoming.take(120)}")
            }
            return
