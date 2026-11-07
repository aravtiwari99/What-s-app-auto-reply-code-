package com.souleven.wap.ai

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Screen mode": works while WhatsApp is open (no notification is posted then).
 *  - chat list: sees a chat whose unread badge increased -> opens it
 *  - chat screen: reads the visible history, generates a reply, types it and presses send
 *  - goes back to the list if it opened the chat itself
 * View ids ("message_text", "entry", "send", ...) are WhatsApp internals and can change between versions.
 */
class WaAccessibilityService : AccessibilityService() {

    private data class Bubble(val text: String, val mine: Boolean, val top: Int)

    companion object {
        private const val TAG = "WAP-AI-A11Y"
        private val WA_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        private const val BATCH_QUIET_MS = 2_000L
        private const val SCREEN_TYPE_DELAY_MS = 2_000L
        private const val MAX_PENDING_IMAGE_AGE_MS = 30 * 60_000L
    }

    private val ui = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    private var curChat: String? = null
    private var lastSeen: String? = null
    private var pendingOpen: String? = null
    private var pendingAt = 0L
    private var autoOpened = false
    private var rerunAfterBusy = false
    private var badgeBaselineReady = false
    private var badgeSeen = linkedMapOf<String, Int>()
    private val pendingChats = ArrayDeque<String>()
    private val openedAt = HashMap<String, Long>()
    private val seenMedia = HashMap<String, String>()

    private val runner = Runnable {
        try {
            process()
        } catch (t: Throwable) {
            Log.e(TAG, "process failed", t)
        }
    }

    private fun enabled(): Boolean =
        AiPrefs.autoRead(this) && AiPrefs.autoReply(this) && AiPrefs.screenMode(this)

    override fun onInterrupt() {}

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in WA_PACKAGES) return
        if (!enabled()) {
            badgeBaselineReady = false
            return
        }
        ui.removeCallbacks(runner)
        ui.postDelayed(runner, BATCH_QUIET_MS)
    }

    // ---------------------------------------------------------------- main loop

    private fun process() {
        if (!enabled()) return
        if (busy.get()) {
            rerunAfterBusy = true
            return
        }
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg !in WA_PACKAGES) return

        val title = findById(root, "conversation_contact_name")?.text?.toString()?.trim()
        if (!title.isNullOrEmpty()) processChat(root, pkg, title) else processList(root, pkg)
    }

    private fun processList(root: AccessibilityNodeInfo, pkg: String) {
        val now = System.currentTimeMillis()
        if (pendingOpen != null) {
            if (now - pendingAt < 6_000L) return
            pendingOpen = null
        }
        curChat = null

        val current = linkedMapOf<String, Int>()
        val rows = linkedMapOf<String, AccessibilityNodeInfo>()
        walk(root, 0) { n ->
            val id = n.viewIdResourceName
            if (id != null && id.endsWith(":id/conversations_row_message_count")) {
                val t = n.text?.toString()?.trim().orEmpty()
                if (t.isNotEmpty()) {
                    val row = clickableAncestor(n)
                    val name = row?.let { findIn(it, "conversations_row_contact_name") }
                        ?.text?.toString()?.trim().orEmpty()
                    if (row != null && name.isNotEmpty()) {
                        val key = "$pkg|$name"
                        current[key] = t.toIntOrNull() ?: 1
                        rows[key] = row
                    }
                }
            }
        }

        if (!badgeBaselineReady) {          // existing unread chats are NOT answered
            badgeSeen = current
            badgeBaselineReady = true
            return
        }

        val increased = mutableListOf<String>()
        for ((key, count) in current) {
            val before = badgeSeen[key] ?: 0
            val cool = openedAt[key] ?: 0L
            if (count > before && now - cool > 60_000L) {
                increased += key
            }
        }
        badgeSeen = current
        for (key in increased) {
            if (key != pendingOpen && key !in pendingChats) pendingChats.addLast(key)
        }

        while (pendingChats.isNotEmpty()) {
            val key = pendingChats.removeFirst()
            val row = rows[key] ?: continue
            pendingOpen = key
            pendingAt = now
            if (row.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                openedAt[key] = now
                return
            }
            pendingOpen = null
        }
    }

    private fun processChat(root: AccessibilityNodeInfo, pkg: String, title: String) {
        val chatKey = "$pkg|$title"
        val bubbles = collect(root)
        val imageBounds = latestIncomingImageBounds(root)
        val imageSig = imageBounds?.let { "${it.left},${it.top},${it.right},${it.bottom}" }
        val pendingImageAt = AiPrefs.pendingImage(this, chatKey)
        val pendingImageIsFresh = pendingImageAt > 0L &&
            System.currentTimeMillis() - pendingImageAt < MAX_PENDING_IMAGE_AGE_MS
        if (pendingImageAt > 0L && !pendingImageIsFresh) AiPrefs.clearPendingImage(this, chatKey)

        if (curChat != chatKey) {
            curChat = chatKey
            val fromList = pendingOpen == chatKey
            if (fromList) pendingOpen = null
            autoOpened = fromList
            if (!fromList) {                 // user opened it: only react to messages arriving from now on
                lastSeen = bubbles.lastOrNull()?.let { sigOf(bubbles, it) }
                val hasUnansweredNotification = bubbles.lastOrNull()?.let {
                    hasUnansweredRecentIncoming(chatKey, it.text)
                } == true
                if ((!pendingImageIsFresh || imageBounds == null) && !hasUnansweredNotification) {
                    if (imageSig != null) seenMedia[chatKey] = imageSig
                    return
                }
                lastSeen = null
            }
            lastSeen = null
        }
        if (bubbles.isEmpty() && imageBounds == null) return

        val last = bubbles.lastOrNull()
        val newImage = imageBounds != null &&
            (imageSig != seenMedia[chatKey] || pendingImageIsFresh)
        if (last == null && !newImage) return
        if (!newImage && (last == null || last.mine || last.text.contains(AiPrefs.AI_FOOTER, ignoreCase = true))) return
        val sig = if (newImage) "image:$imageSig" else sigOf(bubbles, last!!)
        if (!newImage && sig == lastSeen) return
        if (newImage) seenMedia[chatKey] = imageSig!!

        if (hasId(root, "name_in_group_tv") && !AiPrefs.replyGroups(this)) {
            lastSeen = sig
            openedAt[chatKey] = System.currentTimeMillis() + 9 * 60_000L
            if (autoOpened) backOut()
            return
        }

        // do not talk over the owner while he is typing
        val entry = findById(root, "entry") ?: findEditable(root)
        val typed = entry?.text?.toString().orEmpty()
        val hint = Build.VERSION.SDK_INT >= 26 && entry?.isShowingHintText == true
        if (typed.isNotEmpty() && !hint) return

        val lastMineIndex = bubbles.indexOfLast { it.mine }
        val incomingIndices = bubbles.indices
            .filter { it > lastMineIndex && !bubbles[it].mine }
            .ifEmpty { if (newImage) listOf(-1) else emptyList() }
        val incomingIdentities = incomingIndices.map { index ->
            if (index >= 0) {
                "${sigOf(bubbles, bubbles[index])}#${index}"
            } else {
                sig
            }
        }
        val incomingTexts = incomingIndices.map { index ->
            if (index >= 0) bubbles[index].text else sig
        }
        if (!ReplyGuard.acquireIdentities(chatKey, incomingIdentities)) {
            lastSeen = sig
            return
        }
        lastSeen = sig
        busy.set(true)

        val footer = AiPrefs.AI_FOOTER
        fun historyThrough(index: Int): MutableList<ChatStore.Entry> {
            val visible = if (index >= 0) bubbles.take(index + 1) else bubbles
            return visible.takeLast(23).mapIndexed { i, bubble ->
                ChatStore.Entry(
                    bubble.mine,
                    bubble.text.replace(footer, "", ignoreCase = true).trim(),
                    i.toLong()
                )
            }.filter { it.text.isNotEmpty() }.toMutableList()
        }

        val isUnknown = AiPrefs.contactName(this, chatKey).isBlank() &&
            UnknownContactPolicy.isPhoneNumber(title)
        if (isUnknown) {
            val name = UnknownContactPolicy.extractName(
                incomingTexts,
                AiPrefs.unknownContactAsked(this, chatKey)
            )
            if (name == null) {
                if (!AiPrefs.unknownContactAsked(this, chatKey)) {
                    AiPrefs.setUnknownContactAsked(this, chatKey)
                    ui.post { sendReplySequence(title, chatKey, listOf("Yes, who are you?")) }
                } else {
                    finish()
                }
                return
            }
            AiPrefs.setContactName(this, chatKey, name)
        }

        val sendGeneration: (String?) -> Unit = { imageBase64 ->
            executor.execute {
                try {
                    val replies = mutableListOf<String>()
                    val firstIncoming = incomingIndices.first()
                    val history = historyThrough(
                        if (firstIncoming >= 0) firstIncoming - 1 else bubbles.lastIndex
                    )
                    var sequenceTimestamp = System.currentTimeMillis()
                    for (index in incomingIndices) {
                        if (index >= 0) {
                            history += ChatStore.Entry(
                                false,
                                bubbles[index].text.replace(footer, "", ignoreCase = true).trim(),
                                ++sequenceTimestamp
                            )
                        }
                        if (newImage && index == incomingIndices.last()) {
                            history += ChatStore.Entry(false, "[Image attached]", ++sequenceTimestamp)
                        }
                        val reply = AiReplier.generate(
                            this,
                            title,
                            history,
                            if (newImage && index == incomingIndices.last()) imageBase64 else null
                        )
                        if (reply.isBlank()) throw IllegalStateException("empty AI reply")
                        replies += reply
                        history += ChatStore.Entry(true, reply, ++sequenceTimestamp)
                    }
                    ui.post { sendReplySequence(title, chatKey, replies) }
                } catch (t: Throwable) {
                    Log.e(TAG, "generate failed", t)
                    ui.post { fail(title, t.message ?: "error") }
                }
            }
        }
        if (newImage) {
            val cropBounds = imageBounds ?: throw IllegalStateException("image bounds disappeared")
            captureImageCrop(cropBounds) { imageBase64 ->
                if (imageBase64 == null) {
                    seenMedia.remove(chatKey)
                    ui.post { fail(title, "could not capture the visible image; check Android version and WhatsApp screen") }
                } else {
                    AiPrefs.clearPendingImage(this, chatKey)
                    sendGeneration(imageBase64)
                }
            }
        } else {
            sendGeneration(null)
        }
    }

    private fun hasUnansweredRecentIncoming(chatKey: String, visibleText: String): Boolean {
        val history = ChatStore.load(this, chatKey)
        val latestIncoming = history.lastOrNull { !it.fromMe } ?: return false
        if (latestIncoming.text != visibleText) return false
        if (System.currentTimeMillis() - latestIncoming.ts > 30 * 60_000L) return false
        val latestOutgoing = history.lastOrNull { it.fromMe }
        return latestOutgoing == null || latestOutgoing.ts < latestIncoming.ts
    }

    // ---------------------------------------------------------------- sending

    private fun sendReplySequence(title: String, chatKey: String, replies: List<String>, index: Int = 0) {
        if (index >= replies.size) {
            finish()
            return
        }
        sendInChat(title, chatKey, replies[index]) {
            ui.postDelayed({ sendReplySequence(title, chatKey, replies, index + 1) }, 0L)
        }
    }

    private fun sendInChat(
        title: String,
        chatKey: String,
        reply: String,
        onSent: () -> Unit = { finish() }
    ) {
        if (!enabled()) { finish(); return }
        val root = rootInActiveWindow
        val now = root?.let { findById(it, "conversation_contact_name")?.text?.toString()?.trim() }
        if (root == null || now != title) { fail(title, "chat changed before sending"); return }

        val entry = findById(root, "entry") ?: findEditable(root)
        if (entry == null) { fail(title, "message box not found"); return }

        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            AiReplier.withFooter(reply)
        )
        entry.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        ui.postDelayed({
            executor.execute {
                try {
                    ReplySendDelay.waitForTurn(chatKey)
                    ui.post {
                        val r2 = rootInActiveWindow
                        val currentTitle = r2?.let {
                            findById(it, "conversation_contact_name")?.text?.toString()?.trim()
                        }
                        if (r2 == null || currentTitle != title) {
                            fail(title, "chat changed before sending")
                            return@post
                        }
                        val send = clickableAncestor(findById(r2, "send"))
                        if (send == null) {
                            fail(title, "send button not found")
                        } else {
                            send.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            ChatStore.append(this, chatKey, listOf(ChatStore.Entry(true, reply, System.currentTimeMillis())))
                            AiPrefs.setStatus(this, stamp("Replied (screen mode) to \"$title\""))
                            ui.postDelayed(onSent, 1500)
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "send delay failed", t)
                    ui.post { fail(title, t.message ?: "send failed") }
                }
            }
        }, SCREEN_TYPE_DELAY_MS)
    }

    private fun fail(title: String, msg: String) {
        AiPrefs.setStatus(this, stamp("Screen mode error for \"$title\": $msg"))
        finish()
    }

    private fun finish() {
        if (autoOpened) {
            autoOpened = false
            curChat = null
            backOut()
        }
        busy.set(false)
        if (rerunAfterBusy) {
            rerunAfterBusy = false
            ui.postDelayed(runner, BATCH_QUIET_MS)
        }
    }

    private fun backOut() {
        performGlobalAction(GLOBAL_ACTION_BACK)          // closes keyboard or the chat
        ui.postDelayed({
            val r = rootInActiveWindow
            if (r != null && findById(r, "conversation_contact_name") != null) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }, 700)
    }

    // ---------------------------------------------------------------- helpers

    private fun stamp(msg: String): String =
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()) + " - " + msg

    private fun sigOf(all: List<Bubble>, b: Bubble): String = b.text + "#" + all.count { it.text == b.text }

    private fun latestIncomingImageBounds(root: AccessibilityNodeInfo): Rect? {
        val width = resources.displayMetrics.widthPixels
        val minSide = dp(80)
        val candidates = mutableListOf<Rect>()
        walk(root, 0) { node ->
            val id = node.viewIdResourceName.orEmpty().lowercase()
            val className = node.className?.toString().orEmpty()
            if (!className.contains("ImageView", ignoreCase = true) ||
                id.contains("avatar") || id.contains("profile")
            ) return@walk
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() < minSide || bounds.height() < minSide) return@walk
            if (bounds.left >= width / 2 || bounds.bottom <= 0) return@walk
            candidates += bounds
        }
        return candidates.maxByOrNull { it.bottom }?.let(::Rect)
    }

    private fun captureImageCrop(bounds: Rect, callback: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback(null)
            return
        }
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hardware = screenshot.hardwareBuffer
                        try {
                            val source = Bitmap.wrapHardwareBuffer(hardware, screenshot.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                                ?: run {
                                    callback(null)
                                    return
                                }
                            val left = bounds.left.coerceIn(0, source.width - 1)
                            val top = bounds.top.coerceIn(0, source.height - 1)
                            val right = bounds.right.coerceIn(left + 1, source.width)
                            val bottom = bounds.bottom.coerceIn(top + 1, source.height)
                            val crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
                            source.recycle()
                            val bytes = ByteArrayOutputStream().use { stream ->
                                crop.compress(Bitmap.CompressFormat.JPEG, 82, stream)
                                stream.toByteArray()
                            }
                            crop.recycle()
                            callback(Base64.encodeToString(bytes, Base64.NO_WRAP))
                        } finally {
                            hardware.close()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "Screenshot failed with code $errorCode")
                        callback(null)
                    }
                }
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Could not capture image crop", t)
            callback(null)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun collect(root: AccessibilityNodeInfo): List<Bubble> {
        val width = resources.displayMetrics.widthPixels
        val out = ArrayList<Bubble>()
        val r = Rect()
        walk(root, 0) { n ->
            val id = n.viewIdResourceName
            if (id != null && id.endsWith(":id/message_text")) {
                val t = n.text?.toString()?.trim().orEmpty()
                if (t.isNotEmpty()) {
                    n.getBoundsInScreen(r)
                    val mine = r.left > (width - r.right)    // outgoing bubbles sit on the right
                    out += Bubble(t, mine, r.top)
                }
            }
        }
        out.sortBy { it.top }
        return out
    }

    private fun walk(n: AccessibilityNodeInfo?, depth: Int, f: (AccessibilityNodeInfo) -> Unit) {
        if (n == null || depth > 60) return
        f(n)
        for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1, f)
    }

    private fun findIn(from: AccessibilityNodeInfo, name: String): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        walk(from, 0) { n ->
            if (found == null && n.viewIdResourceName?.endsWith(":id/$name") == true) found = n
        }
        return found
    }

    private fun findById(root: AccessibilityNodeInfo, name: String) = findIn(root, name)

    private fun hasId(root: AccessibilityNodeInfo, name: String) = findIn(root, name) != null

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        walk(root, 0) { n -> if (found == null && n.isEditable) found = n }
        return found
    }

    private fun clickableAncestor(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var c = n
        var d = 0
        while (c != null && d < 8) {
            if (c.isClickable) return c
            c = c.parent
            d++
        }
        return null
    }
}
