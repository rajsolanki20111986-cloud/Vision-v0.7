package com.vision.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Text chat: Gemini REST with tool calling, plus the shared memory and history. */
object ChatEngine {
    private const val MODEL = "gemini-flash-latest" // change here for a specific model
    private const val URL = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
    private val client = OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).readTimeout(35, java.util.concurrent.TimeUnit.SECONDS).writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS).retryOnConnectionFailure(true).build()

    /** Answers the newest user message, which must already be in the shared history. */
    suspend fun reply(app: Context): String = withContext(Dispatchers.IO) {
        val turns = MemoryStore.turns(24)
        val lastUser = turns.lastOrNull { it.user }?.text.orEmpty()
        // Plain phone commands (torch, volume, next song, open app, battery) never wait for the model.
        FastPath.tryHandle(app, lastUser)?.let { return@withContext it }
        val lang = LanguageDetector.detectLanguage(lastUser)
        val system = Persona.PROMPT + Persona.TEXT_NOTE + "\n" + LanguageDetector.getLanguagePrompt(lang) +
            "\n\n" + MemoryStore.contextBlock(includeTurns = false, query = lastUser)
        val contents = buildContents(turns)
        val tools = JSONArray().put(JSONObject().put("functionDeclarations", ToolRouter.declarations()))

        for (step in 0 until 4) {
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", contents)
                .put("tools", tools)
            val json = post(body).getOrElse { return@withContext it.message ?: "Error: request failed." }

            val content = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                ?: return@withContext "Error: empty answer from Gemini."
            val parts = content.optJSONArray("parts") ?: JSONArray()

            val calls = ArrayList<JSONObject>()
            val text = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                p.optJSONObject("functionCall")?.let { calls.add(it) }
                p.optString("text", "").takeIf { it.isNotEmpty() }?.let { text.append(it) }
            }
            if (calls.isEmpty()) return@withContext text.toString().ifBlank { "…" }

            // Run the requested tools, then send their results back so the model can finish the answer.
            contents.put(JSONObject().put("role", "model").put("parts", parts))
            val responses = JSONArray()
            val images = ArrayList<String>()
            val executed = HashSet<String>()
            for (c in calls) {
                val name = c.optString("name")
                val args = c.optJSONObject("args") ?: JSONObject()
                val fingerprint = name + ":" + args.toString()
                if (!executed.add(fingerprint)) {
                    responses.put(JSONObject().put("functionResponse", JSONObject()
                        .put("name", name).put("response", JSONObject().put("result", "Skipped duplicate tool call in this turn."))))
                    continue
                }
                var result = try {
                    ToolRouter.run(app, name, args)
                } catch (e: Exception) {
                    "error: ${e.message}"
                }
                if (result.startsWith("IMG:")) {
                    images.add(result.removePrefix("IMG:"))
                    result = "Screenshot attached below. Describe what you see."
                }
                responses.put(JSONObject().put("functionResponse", JSONObject()
                    .put("name", name)
                    .put("response", JSONObject().put("result", result))))
            }
            images.forEach { b64 ->
                responses.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", b64)))
            }
            contents.put(JSONObject().put("role", "user").put("parts", responses))
            if (step == 3) break
        }
        "I used up my tool steps for this message. Please ask again in a shorter way."
    }

    /** Groups consecutive turns of one role, because Gemini expects alternating roles. */
    private fun buildContents(turns: List<Turn>): JSONArray {
        val groups = mutableListOf<Pair<String, StringBuilder>>()
        for (t in turns) {
            val role = if (t.user) "user" else "model"
            val piece = (if (t.source == "voice") "(spoken) " else "") + t.text
            val last = groups.lastOrNull()
            if (last != null && last.first == role) last.second.append('\n').append(piece)
            else groups.add(role to StringBuilder(piece))
        }
        val out = JSONArray()
        groups.forEach { (role, sb) ->
            out.put(JSONObject().put("role", role)
                .put("parts", JSONArray().put(JSONObject().put("text", sb.toString()))))
        }
        // The conversation must open with a user turn.
        while (out.length() > 0 && out.getJSONObject(0).optString("role") != "user") out.remove(0)
        return out
    }

    /**
     * One-shot reply for the agent: no tools, no shared history. history is (mine, text) pairs,
     * oldest first, ending with the other person's message. Returns the text to send.
     */
