package com.vision.app

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class LiveState { Disconnected, Connecting, Listening, Speaking }

/** Direct phone <-> Gemini Live session: audio in and out, tool calls, and transcripts saved to memory. */
class LiveSession(
    private val ctx: Context,
    private val onState: (LiveState) -> Unit,
    private val onCaption: (text: String, fromUser: Boolean) -> Unit,
) {
    companion object { const val MODEL = "gemini-3.1-flash-live-preview" }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ws: WebSocket? = null
    private val audio = AudioStreamer(ctx) { pcm -> if (VisionCore.micOn.value) ws?.send(audioMsg(pcm)) }
    private var notifyJob: Job? = null
    private val userBuf = StringBuilder()
    private val modelBuf = StringBuilder()
    @Volatile private var closed = false

    fun connect(apiKey: String, voice: String) {
        onState(LiveState.Connecting)
        val url = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(setup(voice)) }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (reason.isNotBlank()) onCaption("Closed ($code): $reason", false)
                webSocket.close(1000, null)
                shutdown()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onCaption("Connection error: ${t.message}", false)
                shutdown()
            }
        })
    }

    /** The system prompt is rebuilt on every connect, so the voice always starts with current memory. */
    private fun setup(voice: String): String {
        val system = Persona.PROMPT + "\n\n" + MemoryStore.contextBlock(includeTurns = true)
        return JSONObject().put("setup", JSONObject()
            .put("model", "models/$MODEL")
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig",
                    JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", ToolRouter.declarations())))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
        ).toString()
    }

    private fun audioMsg(pcm: ByteArray): String = JSONObject().put("realtimeInput", JSONObject()
        .put("audio", JSONObject()
            .put("data", Base64.encodeToString(pcm, Base64.NO_WRAP))
            .put("mimeType", "audio/pcm;rate=16000"))).toString()

    private fun handle(raw: String) {
        runCatching {
            val m = JSONObject(raw)
            if (m.has("setupComplete")) {
                audio.start()
                onState(LiveState.Listening)
                startNotificationFeed()
            }

            m.optJSONObject("serverContent")?.let { sc ->
                if (sc.optBoolean("interrupted")) {
                    audio.clear()
                    flushTurns()
                    onState(LiveState.Listening)
                }
                sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                    for (i in 0 until parts.length()) {
                        val data = parts.getJSONObject(i).optJSONObject("inlineData")?.optString("data") ?: continue
                        if (VisionCore.speakerOn.value) audio.play(Base64.decode(data, Base64.DEFAULT))
                        onState(LiveState.Speaking)
                    }
                }
                sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
                    flushModel()
                    userBuf.append(it)
                    onCaption(it, true)
                }
                sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
                    flushUser()
                    modelBuf.append(it)
                    onCaption(it, false)
                }
                if (sc.optBoolean("turnComplete")) {
                    flushTurns()
                    onState(LiveState.Listening)
                }
            }

            m.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { runTools(it) }
        }
    }

    private fun flushUser() {
        val t = userBuf.toString().trim()
        userBuf.setLength(0)
        if (t.isNotEmpty()) MemoryStore.addTurn("voice", true, t)
    }

    private fun flushModel() {
        val t = modelBuf.toString().trim()
        modelBuf.setLength(0)
        if (t.isNotEmpty()) MemoryStore.addTurn("voice", false, t)
    }

    private fun flushTurns() {
        flushUser()
        flushModel()
    }

    private fun runTools(calls: JSONArray) {
        scope.launch {
            val out = JSONArray()
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                var result = try {
                    ToolRouter.run(ctx, c.getString("name"), c.optJSONObject("args") ?: JSONObject())
                } catch (e: Exception) {
                    "error: ${e.message}"
                }
                if (result.startsWith("IMG:")) {
                    // Screenshots go to the Live model as a video frame; the tool result just confirms it.
                    ws?.send(JSONObject().put("realtimeInput", JSONObject().put("video", JSONObject()
                        .put("mimeType", "image/jpeg")
                        .put("data", result.removePrefix("IMG:")))).toString())
                    result = "Screenshot sent to you as an image. Describe what you see."
                }
                out.put(JSONObject()
                    .put("id", c.getString("id"))
                    .put("name", c.getString("name"))
                    .put("response", JSONObject().put("result", result)))
            }
            ws?.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", out)).toString())
        }
    }

    /** Announces each new phone notification; drop(1) skips the value that was already there. */
    private fun startNotificationFeed() {
        if (notifyJob != null || !Prefs.notifyAlerts) return
        notifyJob = scope.launch {
            VisionNotificationListener.newMessage.drop(1).filterNotNull().collect { msg ->
                sendText("[Phone notification] ${msg.app}: ${msg.sender} wrote: ${msg.text}. " +
                    "Tell him briefly and ask if he wants to reply.")
            }
        }
    }

    private fun sendText(text: String) {
        ws?.send(JSONObject().put("clientContent", JSONObject()
            .put("turns", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", text)))))
            .put("turnComplete", true)).toString())
    }

    fun stop() {
        ws?.close(1000, "bye")
        shutdown()
    }

    private fun shutdown() {
        if (closed) return
        closed = true
        flushTurns()
        audio.stop()
        scope.cancel()
        onState(LiveState.Disconnected)
    }
}
