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
    suspend fun agentComplete(system: String, history: List<Pair<Boolean, String>>): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val groups = ArrayList<Pair<String, StringBuilder>>()
                for ((mine, text) in history) {
                    val role = if (mine) "model" else "user"
                    val last = groups.lastOrNull()
                    if (last != null && last.first == role) last.second.append('\n').append(text)
                    else groups.add(role to StringBuilder(text))
                }
                val contents = JSONArray()
                groups.forEach { (role, sb) ->
                    contents.put(JSONObject().put("role", role)
                        .put("parts", JSONArray().put(JSONObject().put("text", sb.toString()))))
                }
                while (contents.length() > 0 && contents.getJSONObject(0).optString("role") != "user") contents.remove(0)
                if (contents.length() == 0) throw IllegalStateException("no message to answer")
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    .put("contents", contents)
                val json = post(body).getOrThrow()
                val parts = json.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                    ?: throw IllegalStateException("empty answer from the model")
                val out = StringBuilder()
                for (i in 0 until parts.length()) out.append(parts.getJSONObject(i).optString("text", ""))
                out.toString()
            }
        }

    /**
     * One task step for the live-chat agent: the model may call the phone tools several times
     * and then gives a final text. Separate from the shared chat history. onTool sees each call.
     */
    suspend fun agentLoop(
        app: Context, system: String, userText: String, maxRounds: Int,
        onTool: (String, JSONObject) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val contents = JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", userText))))
            val tools = JSONArray().put(JSONObject().put("functionDeclarations", ToolRouter.declarations()))
            for (step in 0 until maxRounds.coerceIn(1, 4)) {
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    .put("contents", contents)
                    .put("tools", tools)
                val json = post(body).getOrThrow()
                val content = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                    ?: throw IllegalStateException("empty answer from the model")
                val parts = content.optJSONArray("parts") ?: JSONArray()
                val calls = ArrayList<JSONObject>()
                val text = StringBuilder()
                for (i in 0 until parts.length()) {
                    val p = parts.getJSONObject(i)
                    p.optJSONObject("functionCall")?.let { calls.add(it) }
                    p.optString("text", "").takeIf { it.isNotEmpty() }?.let { text.append(it) }
                }
                if (calls.isEmpty()) return@runCatching text.toString().ifBlank { "WAIT" }
                contents.put(JSONObject().put("role", "model").put("parts", parts))
                val responses = JSONArray()
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
                    if (!result.startsWith("error:", ignoreCase = true)) onTool(name, args)
                    if (result.startsWith("IMG:")) result = "Screenshots are not available here. Use read_screen."
                    responses.put(JSONObject().put("functionResponse", JSONObject()
                        .put("name", name)
                        .put("response", JSONObject().put("result", result))))
                }
                contents.put(JSONObject().put("role", "user").put("parts", responses))
            }
            "WAIT"
        }
    }

    /** Set to false the first time the model rejects thinkingConfig; after that it is not sent again. */
    @Volatile private var thinkingOk = true

    /**
     * Low thinking makes replies and every tool step much faster (the default level is medium).
     * If the chosen model refuses the setting, the same request is repeated once without it.
     */
    private fun post(body: JSONObject): Result<JSONObject> {
        val gc = body.optJSONObject("generationConfig") ?: JSONObject().also { body.put("generationConfig", it) }
        if (thinkingOk && !gc.has("thinkingConfig")) {
            gc.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
        }
        val first = rawPost(body)
        val msg = first.exceptionOrNull()?.message.orEmpty()
        if (first.isFailure && thinkingOk && msg.startsWith("Error 400") && msg.contains("think", ignoreCase = true)) {
            thinkingOk = false
            gc.remove("thinkingConfig")
            return rawPost(body)
        }
        return first
    }

    /** One-shot call that returns JSON text; used by the memory keeper. No tools, no history. */
    suspend fun extract(system: String, userText: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", userText)))))
                .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
            val json = post(body).getOrThrow()
            val parts = json.optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
                ?: throw IllegalStateException("empty answer from the model")
            val out = StringBuilder()
            for (i in 0 until parts.length()) out.append(parts.getJSONObject(i).optString("text", ""))
            out.toString()
        }
    }

    private fun rawPost(body: JSONObject): Result<JSONObject> = runCatching {
        val req = Request.Builder()
            .url(URL)
            .header("x-goog-api-key", Prefs.apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IllegalStateException("Error ${r.code}: ${s.take(500)}")
            JSONObject(s)
        }
    }
}
