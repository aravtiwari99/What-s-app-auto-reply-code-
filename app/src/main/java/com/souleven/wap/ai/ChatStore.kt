package com.souleven.wap.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Per-chat history kept locally (last MAX messages) so the AI has context. */
object ChatStore {

    data class Entry(val fromMe: Boolean, val text: String, val ts: Long)

    private const val MAX = 40

    private fun dir(c: Context): File =
        File(c.applicationContext.filesDir, "ai_history").apply { mkdirs() }

    private fun file(c: Context, key: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir(c), "$h.json")
    }

    @Synchronized
    fun load(c: Context, key: String): MutableList<Entry> {
        val out = mutableListOf<Entry>()
        try {
            val f = file(c, key)
            if (!f.exists()) return out
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += Entry(o.getBoolean("me"), o.getString("t"), o.getLong("ts"))
            }
        } catch (_: Throwable) {
        }
        return out
    }

    @Synchronized
    fun append(c: Context, key: String, entries: List<Entry>) {
        try {
            val all = load(c, key)
            for (e in entries) {
                if (all.none { it.fromMe == e.fromMe && it.text == e.text && it.ts == e.ts }) all += e
            }
            all.sortBy { it.ts }
            val trimmed = if (all.size > MAX) all.subList(all.size - MAX, all.size) else all
            val arr = JSONArray()
            for (e in trimmed) {
                arr.put(JSONObject().put("me", e.fromMe).put("t", e.text).put("ts", e.ts))
            }
            file(c, key).writeText(arr.toString())
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    fun clearAll(c: Context) {
        dir(c).listFiles()?.forEach { it.delete() }
    }
}
