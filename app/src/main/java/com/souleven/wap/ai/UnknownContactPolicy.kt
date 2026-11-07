package com.souleven.wap.ai

object UnknownContactPolicy {

    fun isPhoneNumber(title: String): Boolean {
        val digits = title.count { it.isDigit() }
        return digits >= 7 && title.all { it.isDigit() || it in "+-(). " }
    }

    fun extractName(messages: List<String>, allowStandalone: Boolean): String? {
        val patterns = listOf(
            Regex("""(?:my name is|this is|call me|i am|i'm)\s+([A-Za-z][A-Za-z'-]{1,29})""", RegexOption.IGNORE_CASE),
            Regex("""(?:mera naam)\s+([A-Za-z][A-Za-z'-]{1,29})""", RegexOption.IGNORE_CASE)
        )
        for (message in messages.asReversed()) {
            for (pattern in patterns) {
                pattern.find(message)?.groupValues?.getOrNull(1)?.let { candidate ->
                    if (candidate.lowercase() !in NON_NAME_REPLIES) return candidate
                }
            }
            if (patterns.any { it.containsMatchIn(message) }) continue
            if (!allowStandalone) continue
            val shortName = message.trim().removeSuffix(".").takeIf {
                it.length in 2..30 &&
                    it.split(Regex("\\s+")).size <= 2 &&
                    it.all { ch -> ch.isLetter() || ch.isWhitespace() || ch == '-' || ch == '\'' } &&
                    it.lowercase() !in NON_NAME_REPLIES
            }
            if (shortName != null) return shortName
        }
        return null
    }

    private val NON_NAME_REPLIES = setOf(
        "yes", "no", "hello", "hi", "hey", "good morning", "good night",
        "who are you", "how are you", "what", "why", "ok", "okay", "thanks",
        "thank you", "fine", "good", "great", "i am fine", "im fine",
        "photo", "image", "picture", "media", "attachment", "file",
        "good morning", "good night", "good evening", "howdy",
        "i'm fine", "i'm good", "i am good", "i'm okay", "i am okay",
        "coming", "busy", "available", "free", "speaking"
    )
}
