package com.souleven.wap.ai

/** Shared between the notification path and the screen path so one message never gets two replies. */
object ReplyGuard {
    private val handled = HashMap<String, Long>()

    @Synchronized
    fun acquire(chatKey: String, incomingText: String): Boolean {
        return acquire(chatKey, listOf(incomingText))
    }

    @Synchronized
    fun acquire(chatKey: String, incomingTexts: List<String>): Boolean {
        val now = System.currentTimeMillis()
        handled.entries.removeAll { now - it.value >= 7_000L }
        val newMessages = incomingTexts.map { chatKey + "\u0000" + it.trim() }
            .filter {
                val previous = handled[it]
                it.substringAfter('\u0000').isNotEmpty() &&
                    (previous == null || now - previous >= 7_000L)
            }
        if (newMessages.isEmpty()) return false
        newMessages.forEach { handled[it] = now }
        return true
    }

    @Synchronized
    fun acquireMessages(chatKey: String, incomingMessages: List<Pair<String, Long>>): Boolean {
        return acquireIdentities(
            chatKey,
            incomingMessages.map { (text, timestamp) -> "$timestamp\u0000${text.trim()}" }
        )
    }

    @Synchronized
    fun acquireIdentities(chatKey: String, incomingIdentities: List<String>): Boolean {
        val now = System.currentTimeMillis()
        handled.entries.removeAll { now - it.value >= 7_000L }
        val newMessages = incomingIdentities
            .filter { it.isNotBlank() }
            .map { "$chatKey\u0000$it" }
            .filter { it !in handled }
        if (newMessages.isEmpty()) return false
        newMessages.forEach { handled[it] = now }
        return true
    }
}
