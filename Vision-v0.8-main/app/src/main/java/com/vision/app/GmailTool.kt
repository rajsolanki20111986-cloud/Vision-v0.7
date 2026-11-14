package com.vision.app

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Read-only Gmail search through the Gmail REST API.
 * Needs an OAuth access token with the gmail.readonly scope, pasted in Settings.
 * Tokens expire (about an hour for playground tokens); a full OAuth login flow is a later step.
 */
object GmailTool {
    private val client = OkHttpClient()

    suspend fun search(query: String, max: Int = 5): String = withContext(Dispatchers.IO) {
        if (!Prefs.gmailEnabled || Prefs.gmailToken.isBlank()) {
            return@withContext "Gmail is not set up. Ask him to turn on Gmail API and paste a token in Settings."
        }
        val token = Prefs.gmailToken
        val list = get("https://gmail.googleapis.com/gmail/v1/users/me/messages?maxResults=$max&q=" + Uri.encode(query), token)
            ?: return@withContext "Gmail request failed. The token may have expired."
        val ids = list.optJSONArray("messages") ?: return@withContext "No matching emails."
        val out = StringBuilder()
        for (i in 0 until minOf(ids.length(), max)) {
            val id = ids.getJSONObject(i).getString("id")
            val m = get(
                "https://gmail.googleapis.com/gmail/v1/users/me/messages/$id" +
                    "?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date",
                token
            ) ?: continue
            out.append("- From: ").append(header(m, "From"))
                .append(" | Subject: ").append(header(m, "Subject"))
                .append(" | ").append(header(m, "Date")).append('\n')
                .append("  ").append(m.optString("snippet")).append('\n')
        }
        out.toString().ifBlank { "No matching emails." }
    }

    private fun header(m: JSONObject, name: String): String {
        val headers = m.optJSONObject("payload")?.optJSONArray("headers") ?: return ""
        for (j in 0 until headers.length()) {
            val h = headers.getJSONObject(j)
            if (h.optString("name").equals(name, ignoreCase = true)) return h.optString("value")
        }
        return ""
    }

    private fun get(url: String, token: String): JSONObject? = try {
        client.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build())
            .execute().use { r -> if (r.isSuccessful) JSONObject(r.body?.string().orEmpty()) else null }
    } catch (e: Exception) {
        null
    }
}
