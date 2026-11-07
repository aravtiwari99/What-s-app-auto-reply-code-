package com.souleven.wap.ai

import android.content.Context

/** Builds the prompt from chat history, calls Groq, and cleans the response. */
object AiReplier {

    private const val HISTORY_LIMIT = 24

    fun generate(
        ctx: Context,
        contact: String,
        history: List<ChatStore.Entry>,
        imageBase64: String? = null
    ): String {
        if (isGreeting(history) && AiPrefs.extraInstructions(ctx).isBlank()) {
            return "Har Har Mahadev🙏🏻"
        }

        val key = AiPrefs.apiKey(ctx)
        if (key.isEmpty()) throw IllegalStateException("API key not set")
        val raw = GroqClient.chat(
            key,
            AiPrefs.model(ctx),
            buildMessages(ctx, contact, history, imageBase64 != null),
            imageBase64
        )
        return clean(raw)
    }

    fun withFooter(reply: String): String = reply.trim()

    private fun isGreeting(history: List<ChatStore.Entry>): Boolean {
        val latestIncoming = history.sortedBy { it.ts }.lastOrNull { !it.fromMe } ?: return false
        val normalized = latestIncoming.text
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), "")
            .trim()
            .replace(Regex("\\s+"), " ")
        return normalized in setOf(
            "good night", "good morning", "hi", "hello", "hey", "bye", "gn", "tc"
        )
    }

    private fun buildMessages(
        ctx: Context,
        contact: String,
        history: List<ChatStore.Entry>,
        hasImage: Boolean
    ): List<Pair<String, String>> {
        val extra = AiPrefs.extraInstructions(ctx)
        val system = buildString {
            append("You are replying on behalf of the owner to \"")
            append(contact)
            append("\".\n")
            append("Rules:\n")
            append("1. If the latest message asks who you are or asks you to tell something about yourself (including phrases like \"kaun ho tum\", \"who are you\", or \"tell me something about yourself\"), reply with exactly one of these lines and nothing else: ")
            append("\"mat jano ab jab janate nahi ho to message kyu kiya?\" for a male contact, or ")
            append("\"mat jano ab jab janati nahi to message kyu ki ho?\" for a female contact. ")
            append("Use the female version only when the contact's name or conversation clearly indicates this; if unclear, use the male version. This rule overrides all other style and identity instructions.\n")
            append("2. Reply in the SAME language and SAME script as the contact's latest message. ")
            append("Examples: Hindi written in Roman letters (Hinglish) -> reply in Hinglish with Roman letters; ")
            append("Hindi in Devanagari -> Devanagari; English -> English; any other language -> that language. ")
            append("Match their tone and formality. Use very informal or familiar address such as \"tu/tera\" only if the contact uses that style toward the owner; otherwise use respectful or neutral address and never become rude or overly familiar.\n")
            append("3. This reply is only for \"$contact\". Use only this contact's conversation history; never mix details or messages from another chat. ")
            append("Use the contact's name naturally and sparingly when it suits the message.\n")
            append("4. Write like a relaxed person texting, not like a formal assistant. Use the owner's wording and level of casualness from their earlier messages when available. ")
            append("Answer only what the contact asked or said, with no more detail than needed. For simple questions, give a direct short answer; for a greeting or acknowledgement, keep it to a brief response. ")
            append("Reply separately to each new incoming message; do not combine distinct messages into one reply. Never answer or repeat the owner's own messages.\n")
            append("Do not add unsolicited advice, explanations, extra questions, offers to help, or unrelated conversation. Keep it concise (usually one short sentence); avoid canned openings, repeated summaries, and excessive politeness. ")
            append("Respond warmly and naturally to the feeling behind the message: be happy and celebratory for good news, caring when they are upset, and playful when the chat is playful. ")
            append("Add an emoji when it genuinely suits the emotion and the owner's usual style; choose one that fits (for example 😊, ❤️, 😔, or 😂,), but don't force emojis into every reply or overuse them. ")
            append("Use natural Indian conversational wording when it fits the contact's language and the owner's chat style—for example, light Hinglish or common words like \"yaar\", \"haan\", or \"achha\". ")
            append("Don't force these words, overuse slang, or switch languages if the contact is using another language. No markdown, lists, or headings.\n")
            append("5. If the contact's name or conversation clearly indicates they are male, you may use \"bhai\" occasionally where it sounds natural; never assume based only on an ambiguous name, and don't add it to every reply.\n")
            append("6. If the message is only a media placeholder (photo, voice message, sticker, etc.), reply briefly and naturally.\n")
            append("7. Never promise money, meetings, or decisions on the owner's behalf; say the owner will get back personally.\n")
            if (hasImage) {
                append("A cropped image from the current WhatsApp conversation is attached to the latest message. Inspect it and answer any question visible in that image. If it contains no question, respond briefly and naturally to the image. Do not guess details that are not visible.\n")
            }
           
            if (extra.isNotBlank()) {
                append("\nFollow these extra instructions from the owner whenever they apply, while keeping the reply relevant to the current contact and conversation:\n")
                append(extra)
            }
        }

        val recent = history.sortedBy { it.ts }.takeLast(HISTORY_LIMIT)
        val merged = mutableListOf<Pair<String, String>>()
        for (e in recent) {
            val role = if (e.fromMe) "assistant" else "user"
            if (merged.isEmpty() && role == "assistant") continue   // must start with user
            if (merged.isNotEmpty() && merged.last().first == role) {
                val last = merged.removeAt(merged.size - 1)
                merged += role to (last.second + "\n" + e.text)
            } else {
                merged += role to e.text
            }
        }

        val out = mutableListOf("system" to system)
        out += merged
        return out
    }

    private fun clean(raw: String): String {
        var s = raw.trim()
        s = s.replace(AiPrefs.AI_FOOTER, "", ignoreCase = true).trim()
        if (s.length >= 2 && s.first() == '"' && s.last() == '"') s = s.substring(1, s.length - 1).trim()
        return s
    }
}
