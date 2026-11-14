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
        }

        val reply: String = if (SafetyRules.isLocationRequest(lastIncoming)) {
            "I can't share that, but I will tell him you asked."
        } else {
            val system = Persona.PROMPT + agentRules(mode, title, contact) + "\n\n" + MemoryStore.contextBlock(includeTurns = false)
            val r = ChatEngine.agentComplete(system, lines.map { it.mine to it.text })
            val text = r.getOrElse {
                alert("I could not answer $title: ${it.message?.take(150)}")
                return
            }.trim()
            if (text.isBlank() || text.contains("NO_REPLY")) {
                unreplied[conv] = 0
                AgentStore.note("$title: no reply needed (${lastIncoming.take(60)})")
                return
            }
            text
        }

        SafetyRules.refusal(reply)?.let {
            alert("I wrote a reply to $title but blocked it ($it). Please answer yourself: ${lastIncoming.take(120)}")
            return
        }
        if (!send(ctx, h.action, reply)) {
            alert("I could not send a reply to $title (the notification is gone or has no reply button). They wrote: ${lastIncoming.take(120)}")
            return
        }
        sentTexts.getOrPut(conv) { java.util.Collections.synchronizedList(ArrayList()) }.also {
            it.add(reply); while (it.size > 10) it.removeAt(0)
        }
        AgentStore.addLine(conv, true, reply)
        AgentStore.markSent(conv)
        unreplied[conv] = 0

        val who = if (mode == Mode.KNOWN) title else "$title (unknown person)"
        AgentStore.note("$who: «${lastIncoming.take(80)}» → «${reply.take(80)}»")
        if (mode == Mode.UNKNOWN_ADDRESSED) {
            alert("Unknown person \"$title\" wrote to me: ${lastIncoming.take(200)}\nI replied: $reply\nTell me if you want me to talk to them.")
        } else {
            MemoryStore.addTurn("text", false, "[Agent] $who: ${lastIncoming.take(200)}\n→ I replied: $reply")
        }
    }

    private fun agentRules(mode: Mode, title: String, contact: AgentContact?): String {
        val who = if (mode == Mode.KNOWN)
            "You are replying to $title, a person he told you about." +
                (contact?.note?.takeIf { it.isNotBlank() }?.let { " What he told you about them: $it" } ?: "")
        else
            "You are replying to \"$title\". He has told you nothing about this person, so you do not know them. " +
                "Be polite. Say you are Vision, his AI assistant, that you have no information about them, ask who they are " +
                "and what they need, and say you will pass the message on to him. Tell them nothing about him."
        return """

AGENT MODE. He is not available and you answer a chat message for him now. You have no tools here.
$who
- Write ONLY the message text to send. One to three short, natural chat sentences, in the language the person used.
- If asked whether you are an AI or a bot, say yes. Never say you are him.
- Do not agree to meetings, promises, money, favours or secrets. Say you will check with him and tell him.
- Never share his location, passwords, codes, bank or private details.
- If the conversation has ended (for example "ok", "thanks", only an emoji) or there is nothing useful to say, answer exactly: NO_REPLY
""".trimIndent()
    }

    private fun alert(text: String) {
        AgentStore.note(text.lineSequence().first())
        MemoryStore.addTurn("text", false, "[Agent] $text")
    }

    private fun addressed(text: String): Boolean {
        val t = text.lowercase()
        return AgentStore.triggers.any { t.contains(it) }
    }

    private fun findReply(n: Notification): Notification.Action? {
        val acts = n.actions ?: return null
        val withInput = acts.filter { a -> a.remoteInputs?.isNotEmpty() == true }
        if (withInput.isEmpty()) return null
        return withInput.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY } ?: withInput.first()
    }

    /**
     * The messages in this notification that are new and not from him. lastTs stops a message
     * from being counted twice when the chat app reposts the notification.
     */
    @Suppress("DEPRECATION")
    private fun newIncoming(extras: Bundle, conv: String, postTime: Long, fallbackText: String?): List<String> {
        val seen = lastTs[conv] ?: 0L
        val out = ArrayList<String>()
        var newest = seen
        val arr = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (arr != null && arr.isNotEmpty()) {
            for (m in Notification.MessagingStyle.Message.getMessagesFromBundleArray(arr)) {
                val t = m.text?.toString()?.trim().orEmpty()
                if (t.isEmpty() || m.timestamp <= seen) continue
                if (m.timestamp > newest) newest = m.timestamp
                if (m.sender == null && wasSentByUs(conv, t)) continue
                out.add(t)
            }
        } else {
            val t = fallbackText?.trim().orEmpty()
