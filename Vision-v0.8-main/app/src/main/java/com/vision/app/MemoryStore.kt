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
