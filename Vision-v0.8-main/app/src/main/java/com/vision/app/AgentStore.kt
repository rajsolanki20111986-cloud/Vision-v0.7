package com.vision.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A person he told Vision about. Vision may reply to this person by itself. */
data class AgentContact(val names: List<String>, val note: String, val minMessages: Int, val enabled: Boolean)

/** One line of a conversation that Vision keeps so a reply knows what was said before. */
data class AgentLine(val ts: Long, val mine: Boolean, val text: String)

/**
 * Who Vision may talk to for him, plus the agent settings. Nothing here is hard-coded per person:
 * he adds people and changes numbers by telling Vision (tools agent_contact_set, agent_settings).
 * Contacts and settings survive restarts (agent.json); conversation lines stay in memory only.
 */
object AgentStore {
    private val lock = Any()
    private lateinit var file: File
    private val contacts = LinkedHashMap<String, AgentContact>()
    private val history = HashMap<String, MutableList<AgentLine>>()
    private val sent = HashMap<String, MutableList<Long>>()
    private val lastReply = HashMap<String, Long>()
    private val activity = ArrayList<String>()

    /** Messages from someone NOT in the list before Vision joins in. 0 = never (only when addressed by name). */
    var unknownAfter = 0
    /** If an unknown person writes "hello Vision", Vision answers politely and tells him. */
    var replyOnName = true
    var maxPerHour = 20
    /** Seconds to wait before an automatic notification reply. He sets it; 0 = answer at once. */
    var delaySec = 0
    /** Live chat loop: how often the screen is checked, and when a conversation ends by itself at the latest. */
    var watchSeconds = 2
    var maxMessages = 30
    var maxMinutes = 30
    var idleMinutes = 10
    var triggers: List<String> = listOf("vision", "jarvis", "hello ai", "hi ai", "hey ai", "ai assistant")

    fun init(ctx: Context) {
        synchronized(lock) {
            if (::file.isInitialized) return
            file = File(ctx.filesDir, "agent.json")
            runCatching {
                if (!file.exists()) return
                val o = JSONObject(file.readText())
                unknownAfter = o.optInt("unknownAfter", 0)
                replyOnName = o.optBoolean("replyOnName", true)
                maxPerHour = o.optInt("maxPerHour", 20)
                delaySec = o.optInt("delaySec", 0)
                watchSeconds = o.optInt("watchSeconds", 2)
                maxMessages = o.optInt("maxMessages", 30)
                maxMinutes = o.optInt("maxMinutes", 30)
                idleMinutes = o.optInt("idleMinutes", 10)
                o.optJSONArray("triggers")?.let { a -> triggers = (0 until a.length()).map { a.getString(it) } }
                val arr = o.optJSONArray("contacts") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    val n = c.getJSONArray("names")
                    val names = (0 until n.length()).map { n.getString(it) }
                    if (names.isEmpty()) continue
                    contacts[names[0].lowercase()] = AgentContact(
                        names, c.optString("note"), c.optInt("min", 1), c.optBoolean("enabled", true)
                    )
                }
            }
        }
    }

    private fun save() {
        if (!::file.isInitialized) return
        val arr = JSONArray()
        contacts.values.forEach { c ->
            arr.put(JSONObject().put("names", JSONArray(c.names)).put("note", c.note)
                .put("min", c.minMessages).put("enabled", c.enabled))
        }
        val o = JSONObject().put("unknownAfter", unknownAfter).put("replyOnName", replyOnName)
            .put("maxPerHour", maxPerHour).put("triggers", JSONArray(triggers)).put("contacts", arr)
            .put("delaySec", delaySec).put("watchSeconds", watchSeconds).put("maxMessages", maxMessages)
            .put("maxMinutes", maxMinutes).put("idleMinutes", idleMinutes)
        runCatching { file.writeText(o.toString()) }
    }

    // ---- contacts ----

    fun setContact(namesText: String, note: String, minMessages: Int): String {
        val names = namesText.split(',').map { it.trim() }.filter { it.length >= 2 }
        if (names.isEmpty()) return "Need the person's name as it shows in the chat app."
        synchronized(lock) {
            contacts[names[0].lowercase()] = AgentContact(names, note.trim().take(400), minMessages.coerceIn(1, 20), true)
            save()
        }
        return "Saved. I will reply by myself to ${names.joinToString(" / ")} (after ${minMessages.coerceIn(1, 20)} message(s))."
    }

    fun removeContact(name: String): String {
        synchronized(lock) {
            val key = contacts.keys.firstOrNull { it == name.trim().lowercase() }
                ?: contacts.entries.firstOrNull { e -> e.value.names.any { it.equals(name.trim(), true) } }?.key
                ?: return "No such person in the agent list: $name"
            contacts.remove(key)
            save()
            return "Removed $name. I will not reply to them by myself any more."
        }
    }

    /** Finds the person whose chat-app name matches the notification title. */
    fun match(title: String): AgentContact? {
        val t = title.trim().lowercase()
        if (t.isEmpty()) return null
        synchronized(lock) {
            return contacts.values.firstOrNull { c ->
                c.names.any { n -> val m = n.lowercase(); t == m || (m.length >= 3 && t.contains(m)) }
            }
        }
    }

    fun describe(): String {
        synchronized(lock) {
            val sb = StringBuilder()
            sb.append("Agent mode is ").append(if (Prefs.agentOn) "ON" else "OFF").append(".\n")
            sb.append("Unknown people: reply when they say ").append(triggers.joinToString("/"))
                .append(if (replyOnName) "" else " (switched off)").append("; ")
                .append(if (unknownAfter > 0) "or after $unknownAfter unanswered messages" else "no reply just because of message count")
                .append(". Max replies per person per hour: $maxPerHour. Reply delay: $delaySec s. ")
                .append("Live chat: screen check every $watchSeconds s, ends after $maxMessages messages, $maxMinutes min, or $idleMinutes min of silence.\n")
            if (contacts.isEmpty()) sb.append("No people added yet.")
            contacts.values.forEach {
                sb.append("- ").append(it.names.joinToString(" / ")).append(" (min ").append(it.minMessages)
                    .append(", ").append(if (it.enabled) "on" else "off").append("): ").append(it.note).append('\n')
            }
            return sb.toString().trim()
        }
    }

    // ---- settings ----

    fun setSetting(key: String, value: String): String {
        val v = value.trim()
        val on = v.lowercase() in listOf("on", "true", "yes", "1", "haan", "हाँ", "चालू")
        synchronized(lock) {
            val msg = when (key.trim().lowercase()) {
                "agent" -> { Prefs.agentOn = on; "Agent mode is now ${if (on) "ON" else "OFF"}." }
                "unknown_after" -> {
                    val n = v.toIntOrNull() ?: return "Give a number. 0 means never."
                    unknownAfter = n.coerceIn(0, 50)
                    if (unknownAfter == 0) "Unknown people: no reply because of message count." else "Unknown people: I join in after $unknownAfter unanswered messages."
                }
                "reply_on_name" -> { replyOnName = on; "Replying to unknown people who address me by name: ${if (on) "ON" else "OFF"}." }
                "max_per_hour" -> {
                    val n = v.toIntOrNull() ?: return "Give a number."
                    maxPerHour = n.coerceIn(1, 200); "Max replies per person per hour: $maxPerHour."
                }
                "reply_delay" -> {
                    val n = v.toIntOrNull() ?: return "Give a number of seconds. 0 means answer at once."
                    delaySec = n.coerceIn(0, 600); "Wait before an automatic reply: $delaySec seconds."
                }
                "watch_seconds" -> {
                    val n = v.toIntOrNull() ?: return "Give a number of seconds."
                    watchSeconds = n.coerceIn(1, 30); "In a live chat I check the screen every $watchSeconds seconds."
                }
                "max_messages" -> {
                    val n = v.toIntOrNull() ?: return "Give a number."
                    maxMessages = n.coerceIn(1, 500); "A live chat ends by itself after $maxMessages messages from me."
                }
                "max_minutes" -> {
                    val n = v.toIntOrNull() ?: return "Give a number of minutes."
                    maxMinutes = n.coerceIn(1, 600); "A live chat ends by itself after $maxMinutes minutes."
                }
                "idle_minutes" -> {
                    val n = v.toIntOrNull() ?: return "Give a number of minutes."
                    idleMinutes = n.coerceIn(1, 600); "A live chat ends if nobody writes for $idleMinutes minutes."
                }
                "triggers" -> {
                    val list = v.split(',').map { it.trim().lowercase() }.filter { it.length >= 2 }
                    if (list.isEmpty()) return "Give words separated by commas."
                    triggers = list; "Name words are now: ${list.joinToString(", ")}."
                }
                else -> return "Unknown setting. Use agent, unknown_after, reply_on_name, max_per_hour, reply_delay, watch_seconds, max_messages, max_minutes, idle_minutes or triggers."
            }
            save()
            return msg
        }
    }

    // ---- conversation lines, limits, activity ----

    fun addLine(conv: String, mine: Boolean, text: String) {
        synchronized(lock) {
            val l = history.getOrPut(conv) { ArrayList() }
            l.add(AgentLine(System.currentTimeMillis(), mine, text.take(1000)))
            while (l.size > 30) l.removeAt(0)
        }
    }

    fun lines(conv: String, n: Int): List<AgentLine> = synchronized(lock) { history[conv].orEmpty().takeLast(n) }

    fun markSent(conv: String) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            sent.getOrPut(conv) { ArrayList() }.add(now)
            lastReply[conv] = now
        }
    }

    fun engagedRecently(conv: String): Boolean = synchronized(lock) {
        System.currentTimeMillis() - (lastReply[conv] ?: 0L) < 30 * 60 * 1000L
    }

    /** At most maxPerHour per person and 3x that over all people, so a chat loop can never run away. */
    fun canSend(conv: String): Boolean {
        synchronized(lock) {
            val cut = System.currentTimeMillis() - 3_600_000L
            sent.values.forEach { it.removeAll { t -> t < cut } }
            val mine = sent[conv]?.size ?: 0
            val all = sent.values.sumOf { it.size }
            return mine < maxPerHour && all < maxPerHour * 3
        }
    }

    fun note(text: String) {
        synchronized(lock) {
            val stamp = java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
            activity.add("$stamp  $text")
            while (activity.size > 100) activity.removeAt(0)
        }
    }

    fun recent(n: Int): String = synchronized(lock) {
        activity.takeLast(n).joinToString("\n").ifBlank { "No agent activity yet." }
    }
}
