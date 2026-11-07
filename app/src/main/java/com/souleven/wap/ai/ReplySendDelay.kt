package com.souleven.wap.ai

import android.os.SystemClock

/** Spaces outgoing replies across notification and screen-mode sending. */
object ReplySendDelay {

    private const val INTERVAL_MS = 2_000L
    private val nextSendAt = HashMap<String, Long>()

    fun waitForTurn(chatKey: String) {
        val now = SystemClock.elapsedRealtime()
        val waitMs = synchronized(nextSendAt) {
            val sendAt = maxOf(now, nextSendAt[chatKey] ?: 0L)
            nextSendAt[chatKey] = sendAt + INTERVAL_MS
            sendAt - now
        }
        if (waitMs > 0) {
            try {
                Thread.sleep(waitMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Interrupted while waiting to send reply", e)
            }
        }
    }
}
