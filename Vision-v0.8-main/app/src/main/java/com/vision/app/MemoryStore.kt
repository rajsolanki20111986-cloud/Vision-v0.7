package com.vision.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import kotlin.math.ln

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
 *
 * v0.8: the app itself picks what the model sees. profile.md and skills/ are always sent;
 * everything else is ranked (BM25-style keyword score, line by line) against what you are
 * talking about right now, and only the best lines go into the prompt. Recent turns are
 * cached in RAM so reading history no longer re-reads the whole log file.
 */
object MemoryStore {
    private const val MAX_LOG_LINES = 2000
    private const val MAX_FILE_CHARS = 200_000
    private const val CACHE_TURNS = 400
    private val lock = Any()
    private lateinit var root: File
    private lateinit var log: File
    private val cache = ArrayDeque<Turn>()
    private var cacheLoaded = false

    private val SPLIT = Regex("[^\\p{L}\\p{M}\\p{N}]+")
    private val STOP = setOf(
        "the", "an", "is", "are", "was", "to", "of", "and", "or", "in", "on", "for", "with", "me", "my",
        "you", "it", "this", "that", "what", "how", "do", "does", "can", "please", "about", "tell",
        "hai", "hain", "ka", "ki", "ke", "ko", "se", "mein", "par", "aur", "kya", "kaise", "tum",
        "mera", "meri", "mere", "hum", "ek", "bata", "batao",
        "है", "हैं", "का", "की", "के", "को", "से", "में", "पर", "और", "क्या", "कैसे", "तुम",
        "मेरा", "मेरी", "मेरे", "एक", "यह", "वो", "ये", "बताओ", "बता"
    )

    private class Chunk(val path: String, val text: String, val toks: List<String>)

    fun init(ctx: Context) {
        if (::root.isInitialized) return
        root = File(ctx.filesDir, "memory").apply { mkdirs() }
        listOf("facts", "routines", "projects", "skills").forEach { File(root, it).mkdirs() }
        log = File(root, "conversation.jsonl")
        val profile = File(root, "profile.md")
        if (!profile.exists()) profile.writeText("# Profile\n")
        synchronized(lock) { loadCacheLocked() }
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
            if (append && f.exists() && f.length() > 0) {
                // The same line is never saved twice.
                val want = content.trim().removePrefix("-").trim().lowercase()
                val dup = want.isNotEmpty() && f.readLines().any {
                    it.trim().removePrefix("-").trim().lowercase() == want
                }
                if (dup) return "Already saved in ${f.relativeTo(root).path}"
                f.appendText("\n" + content)
            } else {
                f.writeText(content)
            }
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

    // ------------------------------------------------------------------ history

    private fun parseTurn(line: String): Turn? = runCatching {
        val o = JSONObject(line)
        Turn(o.getLong("ts"), o.optString("src", "text"), o.optBoolean("user"), o.getString("text"))
    }.getOrNull()

    private fun loadCacheLocked() {
        cache.clear()
        if (::log.isInitialized && log.exists()) {
            log.readLines().takeLast(CACHE_TURNS).forEach { line -> parseTurn(line)?.let { cache.addLast(it) } }
        }
        cacheLoaded = true
    }

    fun addTurn(source: String, user: Boolean, text: String) {
        if (text.isBlank() || !::log.isInitialized) return
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val line = JSONObject()
                .put("ts", now)
                .put("src", source)
                .put("user", user)
                .put("text", text)
                .toString()
            log.appendText(line + "\n")
            if (!cacheLoaded) {
                loadCacheLocked()
            } else {
                cache.addLast(Turn(now, source, user, text))
                while (cache.size > CACHE_TURNS) cache.removeFirst()
            }
            if (log.length() > 4_000_000) {
                val keep = log.readLines().filter { it.isNotBlank() }.takeLast(MAX_LOG_LINES)
                log.writeText(keep.joinToString("\n", postfix = "\n"))
            }
        }
    }

    /** Newest turns, oldest first. Served from RAM; broken log lines were skipped when it loaded. */
    fun turns(limit: Int = 200): List<Turn> {
        synchronized(lock) {
            if (!::log.isInitialized) return emptyList()
            if (!cacheLoaded) loadCacheLocked()
            return cache.toList().takeLast(limit)
        }
    }

    // ------------------------------------------------------------------ search

    private fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC).lowercase()

    private fun tokens(s: String): List<String> =
        norm(s).split(SPLIT).filter { it.length >= 2 && it !in STOP }

    /** Every non-empty line of every memory file is one searchable chunk. Call inside the lock. */
    private fun chunks(): List<Chunk> {
        val out = ArrayList<Chunk>()
        for (p in list()) {
            val body = read(p) ?: continue
            val nameToks = tokens(p)
            var heading: List<String> = emptyList()
            for (raw in body.lines()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (line.startsWith("#")) { heading = tokens(line); continue }
                val text = line.removePrefix("-").removePrefix("*").trim()
                if (text.length < 3) continue
                val toks = tokens(text) + heading + nameToks
                if (toks.isEmpty()) continue
                out.add(Chunk(p, text.take(400), toks))
            }
        }
        return out
    }

    /** BM25-style ranking with light prefix matching (project/projects, Hindi endings). Call inside the lock. */
    private fun rank(query: String, limit: Int): List<Pair<Chunk, Double>> {
        val q = tokens(query).distinct()
        if (q.isEmpty()) return emptyList()
        val cs = chunks()
        if (cs.isEmpty()) return emptyList()
        val n = cs.size.toDouble()
        val avg = cs.sumOf { it.toks.size }.toDouble() / n
        val df = HashMap<String, Int>()
        for (c in cs) for (t in c.toks.toSet()) df[t] = (df[t] ?: 0) + 1
        val scored = ArrayList<Pair<Chunk, Double>>()
        for (c in cs) {
            var s = 0.0
            val len = c.toks.size.toDouble()
            for (t in q) {
                val tf = c.toks.count { w ->
                    w == t || (t.length >= 4 && w.length >= 4 && (w.startsWith(t) || t.startsWith(w)))
                }
                if (tf == 0) continue
                val d = df[t] ?: 1
                val idf = ln(1.0 + (n - d + 0.5) / (d + 0.5))
                s += idf * (tf * 2.2) / (tf + 1.2 * (0.25 + 0.75 * len / avg))
            }
            if (s > 0.0) scored.add(Pair(c, s))
        }
        return scored.sortedByDescending { it.second }.take(limit)
    }

    /** Tool memory_search: best matching lines in one call. */
    fun search(query: String, limit: Int = 6): String {
        if (!::root.isInitialized) return "No memory yet."
        return synchronized(lock) {
            val hits = rank(query, limit.coerceIn(1, 12))
            if (hits.isEmpty()) "Nothing in memory matches: $query"
            else hits.joinToString("\n") { "[${it.first.path}] ${it.first.text}" }
        }
    }

    private fun recentUserText(): String =
        turns(12).filter { it.user }.takeLast(3).joinToString(" ") { it.text.take(300) }

    /** A short picture of what is already known; the keeper uses it so it does not repeat facts. */
    fun knownSummary(): String {
        if (!::root.isInitialized) return ""
        return synchronized(lock) {
            val sb = StringBuilder()
            read("profile.md")?.let { sb.append(it.take(1000)).append('\n') }
            for (p in list()) {
                if (p == "profile.md") continue
                val first = (read(p) ?: "").lines().map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }.take(4)
                sb.append('[').append(p).append("] ").append(first.joinToString(" | ").take(300)).append('\n')
                if (sb.length > 3500) break
            }
            sb.toString()
        }
    }

    /**
     * Memory text added to the system prompt of voice and text. About 7,000 characters at most:
     * profile and skills always, then only the lines that match the current topic, then a file index.
     */
    fun contextBlock(includeTurns: Boolean, query: String = ""): String {
        if (!Prefs.memoryOn || !::root.isInitialized) return ""
        val q = query.ifBlank { recentUserText() }
        val sb = StringBuilder()
        sb.append("MEMORY (already loaded for you, so do not call memory_list or memory_read for what is shown here). ")
        sb.append("The lines below were picked by topic from the current conversation. For anything else call memory_search once. ")
        sb.append("A background keeper saves lasting facts by itself; when he says remember this, also save it with memory_append. ")
        sb.append("Files under skills/ are procedures he taught you; follow them.\n")
        synchronized(lock) {
            val shown = HashSet<String>()
            shown.add("profile.md")
            read("profile.md")?.trim()?.takeIf { it.length > 10 }?.let {
                sb.append("\n--- profile.md ---\n").append(it.take(1500)).append('\n')
            }
            var skillBudget = 2500
            for (p in list().filter { it.startsWith("skills/") }) {
                if (skillBudget <= 0) break
                val body = read(p).orEmpty().take(skillBudget)
                skillBudget -= body.length
                sb.append("\n--- ").append(p).append(" ---\n").append(body).append('\n')
                shown.add(p)
            }
            val hits = rank(q, 14).filter { it.first.path !in shown }
            if (hits.isNotEmpty()) {
                sb.append("\nRELEVANT MEMORY (matched to the current topic):\n")
                var b = 2200
                for (h in hits) {
                    val line = "- [${h.first.path}] ${h.first.text}\n"
                    if (line.length > b) break
                    b -= line.length
                    sb.append(line)
                }
            }
            val others = list().filter { it !in shown }
            if (others.isNotEmpty()) {
                sb.append("\nOTHER MEMORY FILES (find with memory_search, open with memory_read):\n")
                var b = 1200
                for (p in others) {
                    val line = "- $p\n"
                    if (line.length > b) break
                    b -= line.length
                    sb.append(line)
                }
            }
        }
        if (includeTurns) {
            sb.append("\nRECENT CONVERSATION (voice and text share this history; [voice] means it was spoken):\n")
            turns(16).forEach { t ->
                sb.append(if (t.user) "User" else "Vision")
                if (t.source == "voice") sb.append(" [voice]")
                sb.append(": ").append(t.text.take(300)).append('\n')
            }
        }
        return sb.toString()
    }
}
