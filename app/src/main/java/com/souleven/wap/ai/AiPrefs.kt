package com.souleven.wap.ai

import android.content.Context
import android.content.SharedPreferences

/** All settings of the AI auto-reply feature live here (private SharedPreferences of this module app). */
object AiPrefs {

    const val DEFAULT_MODEL = "qwen/qwen3.8-27b"
    const val AI_FOOTER = ""

    private const val FILE = "wap_ai_prefs"
    private const val K_API_KEY = "groq_api_key"
    private const val K_MODEL = "groq_model"
    private const val K_AUTO_READ = "auto_read"
    private const val K_AUTO_REPLY = "auto_reply"
    private const val K_GROUPS = "reply_groups"
    private const val K_SCREEN = "screen_mode"
    private const val K_EXTRA = "extra_instructions"
    private const val K_STATUS = "last_status"
    private const val P_LAST = "last_handled:"
    private const val P_CONTACT_NAME = "contact_name:"
    private const val P_UNKNOWN_ASKED = "unknown_asked:"
    private const val P_PENDING_IMAGE = "pending_image:"

    private fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun apiKey(c: Context): String = sp(c).getString(K_API_KEY, "")?.trim().orEmpty()
    fun setApiKey(c: Context, v: String) = sp(c).edit().putString(K_API_KEY, v.trim()).apply()

    fun model(c: Context): String =
        sp(c).getString(K_MODEL, DEFAULT_MODEL)?.trim().orEmpty().ifEmpty { DEFAULT_MODEL }
    fun setModel(c: Context, v: String) = sp(c).edit().putString(K_MODEL, v.trim()).apply()

    fun autoRead(c: Context): Boolean = sp(c).getBoolean(K_AUTO_READ, false)
    fun setAutoRead(c: Context, v: Boolean) = sp(c).edit().putBoolean(K_AUTO_READ, v).apply()

    fun autoReply(c: Context): Boolean = sp(c).getBoolean(K_AUTO_REPLY, false)
    fun setAutoReply(c: Context, v: Boolean) = sp(c).edit().putBoolean(K_AUTO_REPLY, v).apply()

    fun replyGroups(c: Context): Boolean = sp(c).getBoolean(K_GROUPS, false)
    fun setReplyGroups(c: Context, v: Boolean) = sp(c).edit().putBoolean(K_GROUPS, v).apply()

    fun screenMode(c: Context): Boolean = sp(c).getBoolean(K_SCREEN, false)
    fun setScreenMode(c: Context, v: Boolean) = sp(c).edit().putBoolean(K_SCREEN, v).apply()

    fun extraInstructions(c: Context): String = sp(c).getString(K_EXTRA, "").orEmpty()
    fun setExtraInstructions(c: Context, v: String) = sp(c).edit().putString(K_EXTRA, v.trim()).apply()

    fun status(c: Context): String = sp(c).getString(K_STATUS, "").orEmpty()
    fun setStatus(c: Context, v: String) = sp(c).edit().putString(K_STATUS, v).apply()

    fun lastHandled(c: Context, chatKey: String): Long = sp(c).getLong(P_LAST + chatKey, 0L)
    fun setLastHandled(c: Context, chatKey: String, t: Long) =
        sp(c).edit().putLong(P_LAST + chatKey, t).apply()

    fun contactName(c: Context, chatKey: String): String =
        sp(c).getString(P_CONTACT_NAME + chatKey, "").orEmpty()

    fun setContactName(c: Context, chatKey: String, name: String) =
        sp(c).edit().putString(P_CONTACT_NAME + chatKey, name.trim()).apply()

    fun unknownContactAsked(c: Context, chatKey: String): Boolean =
        sp(c).getBoolean(P_UNKNOWN_ASKED + chatKey, false)

    fun setUnknownContactAsked(c: Context, chatKey: String) =
        sp(c).edit().putBoolean(P_UNKNOWN_ASKED + chatKey, true).apply()

    fun setPendingImage(c: Context, chatKey: String, timestamp: Long) =
        sp(c).edit().putLong(P_PENDING_IMAGE + chatKey, timestamp).apply()

    fun pendingImage(c: Context, chatKey: String): Long =
        sp(c).getLong(P_PENDING_IMAGE + chatKey, 0L)

    fun clearPendingImage(c: Context, chatKey: String) =
        sp(c).edit().remove(P_PENDING_IMAGE + chatKey).apply()

    fun maskedKey(c: Context): String {
        val k = apiKey(c)
        return when {
            k.isEmpty() -> ""
            k.length <= 8 -> "••••"
            else -> k.take(4) + "••••" + k.takeLast(4)
        }
    }
}
