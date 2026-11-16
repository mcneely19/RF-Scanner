package com.rfscout.app

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional: ask Claude to write the session debrief. Only used when you paste your own API key in
 * Settings and tap Ask Claude. The text that is sent is shown to you first.
 */
object ClaudeApi {
    const val MODEL = "claude-sonnet-5-5"
    private const val URL_MESSAGES = "https://api.anthropic.com/v1/messages"
    private val main = Handler(Looper.getMainLooper())

    /** Calls [done] on the main thread with (reply, null) or (null, error message). */
    fun ask(apiKey: String, prompt: String, done: (String?, String?) -> Unit) {
        Thread({
            var reply: String? = null
            var error: String? = null
            try {
                val body = JSONObject()
                    .put("model", MODEL)
                    .put("max_tokens", 700)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                    .toString()
                val c = URL(URL_MESSAGES).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 15_000
                c.readTimeout = 60_000
                c.doOutput = true
                c.setRequestProperty("x-api-key", apiKey)
                c.setRequestProperty("anthropic-version", "2023-06-01")
                c.setRequestProperty("content-type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code in 200..299) {
                    val blocks = JSONObject(text).getJSONArray("content")
                    val sb = StringBuilder()
                    for (i in 0 until blocks.length()) {
                        val b = blocks.getJSONObject(i)
                        if (b.optString("type") == "text") sb.append(b.optString("text"))
                    }
                    reply = sb.toString().trim().ifEmpty { null }
                    if (reply == null) error = "Claude returned an empty answer"
                } else {
                    val msg = try {
                        JSONObject(text).getJSONObject("error").optString("message")
                    } catch (_: Exception) {
                        ""
                    }
                    error = when (code) {
                        401 -> "Claude rejected the API key (401). Check it in Settings."
                        429 -> "Claude says too many requests (429). Try again in a minute."
                        else -> "Claude error $code" + if (msg.isNotEmpty()) ": $msg" else ""
                    }
                }
            } catch (e: Exception) {
                error = "Could not reach Claude: ${e.message ?: "network error"}"
            }
            main.post { done(reply, error) }
        }, "rfscout-claude").start()
    }
}
