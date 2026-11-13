package com.vision.app

import android.content.Context
import org.json.JSONObject
import java.io.File

/** One message in the shared history. source is "voice" or "text". */
data class Turn(val ts: Long, val source: String, val user: Boolean, val text: String)

/**
 * Vision's memory, kept as plain files so you can read and edit them:
 *   profile.md          who you are (only what you told Vision)
 *   facts/<name>.md     important facts
 *   routines/<name>.md  habits and routines
 *   projects/<name>.md  project notes
 *   skills/<name>.md    things you taught Vision to do (always read)
 *   conversation.jsonl  one shared log of voice and text messages
 */
object MemoryStore {
    private const val MAX_LOG_LINES = 2000
    private const val MAX_FILE_CHARS = 200_000
    private val lock = Any()
    private lateinit var root: File
    private lateinit var log: File

    fun init(ctx: Context) {
        if (::root.isInitialized) return
        root = File(ctx.filesDir, "memory").apply { mkdirs() }
        listOf("facts", "routines", "projects", "skills").forEach { File(root, it).mkdirs() }
        log = File(root, "conversation.jsonl")
        val profile = File(root, "profile.md")
        if (!profile.exists()) profile.writeText("# Profile\n")
    }

    fun list(): List<String> {
        synchronized(lock) {
            if (!::root.isInitialized) return emptyList()
            return root.walkTopDown()
                .filter { it.isFile && it != log }
                .map { it.relativeTo(root).path.replace('\\', '/') }
                .sorted()
                .toList()
        }
    }

    fun read(path: String): String? {
        synchronized(lock) {
            val f = resolve(path) ?: return null
            return if (f.isFile) f.readText() else null
        }
    }

    fun write(path: String, content: String, append: Boolean = false): String {
        synchronized(lock) {
            val f = resolve(path) ?: return "Refused: use a path such as facts/name.md, with no '..'."
            if (content.length > MAX_FILE_CHARS) return "Refused: content is too long."
            f.parentFile?.mkdirs()
            if (append && f.exists() && f.length() > 0) f.appendText("\n" + content) else f.writeText(content)
            return "Saved ${f.relativeTo(root).path}"
        }
    }

    fun delete(path: String): String {
        synchronized(lock) {
            val f = resolve(path) ?: return "Refused: bad path."
            return if (f.delete()) "Deleted $path" else "Not found: $path"
        }
    }

    /** Only .md and .txt files inside the memory folder can be touched. */
    private fun resolve(path: String): File? {
        val clean = path.trim().trimStart('/')
        if (clean.isEmpty() || clean.contains("..")) return null
        if (!(clean.endsWith(".md") || clean.endsWith(".txt"))) return null
        val f = File(root, clean).canonicalFile
        return if (f.path.startsWith(root.canonicalPath + File.separator)) f else null
    }

    fun addTurn(source: String, user: Boolean, text: String) {
        if (text.isBlank() || !::log.isInitialized) return
        synchronized(lock) {
            val line = JSONObject()
                .put("ts", System.currentTimeMillis())
                .put("src", source)
                .put("user", user)
                .put("text", text)
                .toString()
            log.appendText(line + "\n")
            if (log.length() > 4_000_000) {
                val keep = log.readLines().filter { it.isNotBlank() }.takeLast(MAX_LOG_LINES)
                log.writeText(keep.joinToString("\n", postfix = "\n"))
            }
        }
    }

    /** Broken or half-written lines are skipped, so one bad line never breaks the chat. */
    fun turns(limit: Int = 200): List<Turn> {
        synchronized(lock) {
            if (!::log.isInitialized || !log.exists()) return emptyList()
            val parsed = log.readLines().mapNotNull { line ->
                runCatching {
                    val o = JSONObject(line)
                    Turn(o.getLong("ts"), o.optString("src", "text"), o.optBoolean("user"), o.getString("text"))
                }.getOrNull()
            }
            return parsed.takeLast(limit)
        }
    }

    /** Memory text that is added to the system prompt of both voice and text. */
    fun contextBlock(includeTurns: Boolean): String {
        if (!Prefs.memoryOn || !::root.isInitialized) return ""
        val sb = StringBuilder()
        sb.append("MEMORY: these files are what you already know about him. ")
        sb.append("When he tells you something lasting, save it with memory_append or memory_write. ")
        sb.append("Files under skills/ are procedures he taught you; follow them.\n")
        var budget = 16_000
        for (p in list()) {
            if (budget <= 0) break
            val body = read(p).orEmpty().take(budget)
            budget -= body.length
            sb.append("\n--- ").append(p).append(" ---\n").append(body).append('\n')
        }
        if (includeTurns) {
            sb.append("\nRECENT CONVERSATION (voice and text share this history; [voice] means it was spoken):\n")
            turns(40).forEach { t ->
                sb.append(if (t.user) "User" else "Vision")
                if (t.source == "voice") sb.append(" [voice]")
                sb.append(": ").append(t.text.take(500)).append('\n')
            }
        }
        return sb.toString()
    }
}
