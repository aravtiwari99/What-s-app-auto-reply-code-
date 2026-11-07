package com.souleven.wap.ai

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Runs in the background (even when WhatsApp is closed). Reads WhatsApp message notifications,
 * and answers through the notification's own inline "Reply" action.
 */
class WaNotificationListener : NotificationListenerService() {

    private data class Incoming(val text: String, val time: Long, val sender: String?)

    private data class PendingBatch(
        val incoming: MutableList<Incoming> = mutableListOf(),
        var title: String = "",
        var notificationKey: String = "",
        var replyAction: Notification.Action? = null,
        var readAction: Notification.Action? = null,
        var shouldReply: Boolean = false,
        var future: ScheduledFuture<*>? = null,
        var wakeLock: PowerManager.WakeLock? = null
    )

    private val executor: ScheduledExecutorService = java.util.concurrent.Executors.newScheduledThreadPool(8)
    private val batchLock = Any()
    private val pendingBatches = HashMap<String, PendingBatch>()
    private val chatLocks = HashMap<String, Any>()
    private val sentTimes = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val recentSent = ConcurrentHashMap<String, ArrayDeque<String>>()

    companion object {
        private const val TAG = "WAP-AI"
        private val WA_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        private val SELF_NAMES = setOf("you", "me", "आप", "मैं")
        private const val MAX_REPLIES_PER_10_MIN = 800
        private const val MAX_MESSAGE_AGE_MS = 30 * 60_000L
        private const val BATCH_QUIET_MS = 2_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 90_000L
    }

    override fun onListenerDisconnected() {
        try {
            requestRebind(ComponentName(this, WaNotificationListener::class.java))
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        synchronized(batchLock) {
            pendingBatches.values.forEach { batch ->
                batch.future?.cancel(false)
                batch.wakeLock?.let { if (it.isHeld) it.release() }
            }
            pendingBatches.clear()
        }
        executor.shutdown()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            handle(sbn)
        } catch (t: Throwable) {
            Log.e(TAG, "handle failed", t)
        }
    }

    private fun handle(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName !in WA_PACKAGES) return
        if (!AiPrefs.autoRead(this)) return

        val n = sbn.notification ?: return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val extras = n.extras ?: return

        val convTitle = extras.getCharSequence("android.conversationTitle")?.toString()
        val isGroup = extras.getBoolean("android.isGroupConversation", false) || !convTitle.isNullOrBlank()
        if (isGroup && !AiPrefs.replyGroups(this)) return

        val rawTitle = convTitle ?: extras.getCharSequence("android.title")?.toString().orEmpty()
        val title = rawTitle.replace(Regex("\\s*\\(\\d+[^)]*\\)\\s*$"), "").trim()
        if (title.isEmpty()) return

        // Only real chat notifications have a Reply or Mark-as-read action.
        val actions = collectActions(n)
        val replyAction = actions.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
        val readAction = actions.firstOrNull {
            it.remoteInputs.isNullOrEmpty() && it.title?.toString()?.contains("read", ignoreCase = true) == true
        }
        if (replyAction == null && readAction == null) return

        val chatKey = sbn.packageName + "|" + title
        val now = System.currentTimeMillis()
        val last = AiPrefs.lastHandled(this, chatKey)
        val sentBefore = recentSent[chatKey]?.let { synchronized(it) { it.toSet() } }

        val selfName = extras.getCharSequence("android.selfDisplayName")?.toString()
        val fresh = extractMessages(extras, sbn.postTime, selfName).filter {
            it.time > last &&
                now - it.time < MAX_MESSAGE_AGE_MS &&
                (sentBefore == null || !sentBefore.contains(it.text))
        }
        if (fresh.isEmpty()) return

        AiPrefs.setLastHandled(this, chatKey, fresh.maxOf { it.time })

        val shouldReply = AiPrefs.autoReply(this) &&
            replyAction != null &&
            ReplyGuard.acquireMessages(chatKey, fresh.map { it.text to it.time })
        queueBatch(chatKey, sbn.key, title, fresh, replyAction, readAction, shouldReply)
    }

    private fun queueBatch(
        chatKey: String,
        notificationKey: String,
        title: String,
        incoming: List<Incoming>,
        replyAction: Notification.Action?,
        readAction: Notification.Action?,
        shouldReply: Boolean
    ) {
        synchronized(batchLock) {
            val batch = pendingBatches[chatKey] ?: PendingBatch().also {
                val power = getSystemService(POWER_SERVICE) as PowerManager
                it.wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:batch").apply {
                    setReferenceCounted(false)
                    acquire(WAKE_LOCK_TIMEOUT_MS)
                }
                pendingBatches[chatKey] = it
            }
            incoming.forEach { message ->
                if (batch.incoming.none { it.time == message.time && it.text == message.text }) {
                    batch.incoming += message
                }
            }
            batch.title = title
            batch.notificationKey = notificationKey
            batch.replyAction = replyAction ?: batch.replyAction
            batch.readAction = readAction ?: batch.readAction
            batch.shouldReply = batch.shouldReply || shouldReply
            batch.future?.cancel(false)
            batch.future = executor.schedule(
                { processBatch(chatKey, batch) },
                BATCH_QUIET_MS,
                TimeUnit.MILLISECONDS
            )
        }
    }

    private fun processBatch(chatKey: String, batch: PendingBatch) {
        synchronized(batchLock) {
            if (pendingBatches[chatKey] !== batch) return
            pendingBatches.remove(chatKey)
        }
        val wakeLock = batch.wakeLock
        try {
            val chatLock = synchronized(batchLock) { chatLocks.getOrPut(chatKey) { Any() } }
            synchronized(chatLock) {
                val messages = batch.incoming.sortedBy { it.time }
                val hasMediaPlaceholder = messages.any { isMediaPlaceholder(it.text) }
                if (hasMediaPlaceholder) {
                    ChatStore.append(
                        this,
                        chatKey,
                        messages.map { ChatStore.Entry(false, it.text, it.time) }
                    )
                    AiPrefs.setPendingImage(this, chatKey, System.currentTimeMillis())
                }

                if (!batch.shouldReply || !AiPrefs.autoReply(this)) {
                    if (!hasMediaPlaceholder) {
                        ChatStore.append(
                            this,
                            chatKey,
                            messages.map { ChatStore.Entry(false, it.text, it.time) }
                        )
                    }
                    markRead(batch.readAction)
                    return
                }

                val isUnknown = AiPrefs.contactName(this, chatKey).isBlank() &&
                    UnknownContactPolicy.isPhoneNumber(batch.title)
                if (isUnknown) {
                    val name = UnknownContactPolicy.extractName(
                        messages.map { it.text },
                        AiPrefs.unknownContactAsked(this, chatKey) || messages.size > 1
                    )
                    if (name == null) {
                        if (!hasMediaPlaceholder) {
                            ChatStore.append(
                                this,
                                chatKey,
                                messages.map { ChatStore.Entry(false, it.text, it.time) }
                            )
                        }
                        if (!AiPrefs.unknownContactAsked(this, chatKey)) {
                            sendAndRecord(
                                chatKey,
                                batch.title,
                                batch.notificationKey,
                                batch.replyAction,
                                "Yes, who are you?"
                            )
                            AiPrefs.setUnknownContactAsked(this, chatKey)
                            AiPrefs.setStatus(this, stamp("Asked unknown number to identify themselves"))
                        } else {
                            AiPrefs.setStatus(this, stamp("Waiting for unknown number to provide a name"))
                        }
                        return
                    }
                    AiPrefs.setContactName(this, chatKey, name)
                }

                if (hasMediaPlaceholder) {
                    AiPrefs.setStatus(this, stamp("Image received; open the chat with screen mode enabled to analyze it"))
                    return
                }

                val times = sentTimes.getOrPut(chatKey) { ArrayDeque() }
                val action = batch.replyAction
                if (action == null) {
                    markRead(batch.readAction)
                    return
                }
                val contact = AiPrefs.contactName(this, chatKey).ifBlank { batch.title }
                for (message in messages) {
                    ChatStore.append(
                        this,
                        chatKey,
                        listOf(ChatStore.Entry(false, message.text, message.time))
                    )
                    val now = System.currentTimeMillis()
                    while (times.isNotEmpty() && now - times.first() > 10 * 60_000L) times.removeFirst()
                    if (times.size >= MAX_REPLIES_PER_10_MIN) {
                        AiPrefs.setStatus(this, "Rate limit reached for \"${batch.title}\" (loop protection).")
                        break
                    }
                    val history = ChatStore.load(this, chatKey)
                    val reply = AiReplier.generate(this, contact, history)
                    if (reply.isBlank()) {
                        AiPrefs.setStatus(this, stamp("AI returned an empty reply for \"${batch.title}\""))
                        continue
                    }
                    if (!AiPrefs.autoReply(this)) break
                    sendAndRecord(chatKey, batch.title, batch.notificationKey, action, reply)
                    times.addLast(System.currentTimeMillis())
                    AiPrefs.setStatus(this, stamp("Replied to \"${batch.title}\""))
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "reply failed", t)
            AiPrefs.setStatus(this, stamp("Error for \"${batch.title}\": ${t.message}"))
        } finally {
            if (wakeLock?.isHeld == true) wakeLock.release()
        }
    }

    private fun isMediaPlaceholder(text: String): Boolean {
        val value = text.lowercase().trim()
        return value in setOf("photo", "image", "picture", "📷", "🖼️", "<media omitted>") ||
            value.contains("sent a photo") || value.contains("sent an image") ||
            value.contains("sent a picture")
    }

    private fun sendAndRecord(
        chatKey: String,
        title: String,
        notificationKey: String,
        action: Notification.Action?,
        reply: String
    ) {
        val replyAction = action
            ?: throw IllegalStateException("WhatsApp reply action is no longer available for \"$title\"")
        ReplySendDelay.waitForTurn(chatKey)
        val finalText = AiReplier.withFooter(reply)
        val latestAction = findReplyAction(notificationKey) ?: replyAction
        sendReply(latestAction, finalText)
        val ts = System.currentTimeMillis()
        ChatStore.append(this, chatKey, listOf(ChatStore.Entry(true, reply, ts)))
        val q = recentSent.getOrPut(chatKey) { ArrayDeque() }
        synchronized(q) {
            q.addLast(finalText)
            q.addLast(reply)
            while (q.size > 12) q.removeFirst()
        }
    }

    private fun findReplyAction(notificationKey: String): Notification.Action? {
        return activeNotifications
            ?.firstOrNull { it.key == notificationKey }
            ?.notification
            ?.let(::collectActions)
            ?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
    }

    private fun markRead(action: Notification.Action?) {
        try {
            action?.actionIntent?.send()
        } catch (t: Throwable) {
            Log.w(TAG, "mark-as-read action failed", t)
        }
    }

    private fun stamp(msg: String): String =
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()) + " - " + msg

    private fun collectActions(n: Notification): List<Notification.Action> {
        val all = mutableListOf<Notification.Action>()
        n.actions?.let { all.addAll(it) }
        try {
            all.addAll(Notification.WearableExtender(n).actions)
        } catch (_: Throwable) {
        }
        return all
    }

    private fun extractMessages(
        extras: Bundle,
        fallbackTime: Long,
        selfDisplayName: String?
    ): List<Incoming> {
        val out = mutableListOf<Incoming>()
        val selfNames = SELF_NAMES + listOfNotNull(selfDisplayName?.trim()?.lowercase())
        @Suppress("DEPRECATION")
        val arr = extras.getParcelableArray("android.messages")
        if (arr != null) {
            for (p in arr) {
                val b = p as? Bundle ?: continue
                val text = b.getCharSequence("text")?.toString()?.trim().orEmpty()
                if (text.isEmpty()) continue
                val sender = b.getCharSequence("sender")?.toString()?.trim()
                if (sender.isNullOrEmpty() || sender.lowercase() in selfNames) continue
                var time = b.getLong("time", 0L)
                if (time == 0L) time = fallbackTime
                out += Incoming(text, time, sender)
            }
        } else {
            // Without sender metadata, the notification cannot be safely classified as incoming.
        }
        return out
    }

    private fun sendReply(action: Notification.Action, text: String) {
        val inputs = action.remoteInputs
        val intent = Intent()
        val results = Bundle()
        for (ri in inputs) results.putCharSequence(ri.resultKey, text)
        RemoteInput.addResultsToIntent(inputs, intent, results)
        action.actionIntent.send(this, 0, intent)
    }
}
