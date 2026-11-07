package com.souleven.wap.ai

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Minimal Groq (OpenAI-compatible) chat client. No extra dependencies. */
object GroqClient {

    private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"

    class GroqException(val code: Int, message: String) : Exception(message)

    /** messages = list of (role, content). Must be called off the main thread. */
    fun chat(
        apiKey: String,
        model: String,
        messages: List<Pair<String, String>>,
        imageBase64: String? = null
    ): String {
        return try {
            request(apiKey, model, messages, imageBase64, noThinking = true)
        } catch (e: GroqException) {
            // Some models reject "reasoning_effort"; retry once without it.
            if (e.code == 400) request(apiKey, model, messages, imageBase64, noThinking = false) else throw e
        }
    }

    private fun request(
        apiKey: String,
        model: String,
        messages: List<Pair<String, String>>,
        imageBase64: String?,
        noThinking: Boolean
    ): String {
        val body = JSONObject()
        body.put("model", model)
        val arr = JSONArray()
        val imageMessageIndex = if (imageBase64 == null) -1 else messages.indexOfLast { it.first == "user" }
        for ((index, message) in messages.withIndex()) {
            val (role, content) = message
            val item = JSONObject().put("role", role)
            if (index == imageMessageIndex) {
                val parts = JSONArray()
                    .put(JSONObject().put("type", "text").put("text", content))
                    .put(
                        JSONObject()
                            .put("type", "image_url")
                            .put(
                                "image_url",
                                JSONObject().put("url", "data:image/jpeg;base64,$imageBase64")
                            )
                    )
                item.put("content", parts)
            } else {
                item.put("content", content)
            }
            arr.put(item)
        }
        body.put("messages", arr)
        body.put("temperature", 0.7)
        body.put("max_tokens", 1024)
        if (noThinking) body.put("reasoning_effort", "none")

        var attempt = 0
        while (true) {
            val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

                if (code == 429 && attempt == 0) {   // rate limited: wait a bit, retry once
                    attempt++
                    Thread.sleep(4_000)
                    continue
                }
                if (code !in 200..299) {
                    val msg = try {
                        JSONObject(text).getJSONObject("error").getString("message")
                    } catch (_: Throwable) {
                        text.take(200)
                    }
                    throw GroqException(code, "HTTP $code: $msg")
                }

                val content = JSONObject(text)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .optString("content", "")
                return stripThinking(content)
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun stripThinking(s: String): String {
        var out = s
        val close = out.lastIndexOf("</think>")
        if (close >= 0) out = out.substring(close + "</think>".length)
        out = out.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
        return out.trim()
    }
}
