package com.bharatdroid.agent

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * NotificationRelay — listens to every notification the phone receives and
 * forwards it to the authorized Telegram chat, with two-way reply support.
 *
 * The Android phone becomes a full-time companion:
 *   - WhatsApp / SMS / Gmail / Slack / any app → instantly on Telegram
 *   - Reply to a Telegram-forwarded message → RemoteInput fires back into the source app
 *
 * Architecture:
 *   onNotificationPosted → filter (ongoing/summary/muted/dup) → forward → store map
 *   user Telegram reply with reply_to_message_id → sendReply() → RemoteInput.send()
 */
class NotificationRelay : NotificationListenerService() {

    companion object {
        private const val MAX_TRACKED = 200
        private const val DEDUP_WINDOW_MS = 4_000L

        // Packages to never forward — reduces spam. Includes ourselves (loop guard).
        private val IGNORED_PACKAGES = setOf(
            "com.bharatdroid.agent",
            "android",
            "com.android.systemui",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.providers.downloads",
            "com.android.bluetooth",
            "com.android.vending", // Play Store downloads
        )

        @Volatile var poller: TelegramPoller? = null
        @Volatile var muteStore: MuteStore? = null
        @Volatile var chatId: Long = -1L
        private val bootstrapped = AtomicBoolean(false)

        // telegram outgoing message_id → notification we forwarded (for reply routing)
        private val notifMap = ConcurrentHashMap<Long, NotifRecord>()

        // content hash → last forward timestamp (in-memory dedup)
        private val recentHashes = ConcurrentHashMap<Int, Long>()

        /**
         * Wire the relay into the running agent. Called once from AgentOrchestrator.start().
         * Safe to call before the service itself has bound — it stores the config statically
         * and requestRebind() kicks the service to connect.
         */
        fun attach(p: TelegramPoller, m: MuteStore, authorizedChatId: Long) {
            poller = p
            muteStore = m
            chatId = authorizedChatId
        }

        /** Ask Android to (re)bind the listener. Needs POST-boot + user grant. */
        fun rebind(context: Context) {
            try {
                val comp = ComponentName(context, NotificationRelay::class.java)
                NotificationListenerService.requestRebind(comp)
            } catch (_: Throwable) { /* not granted yet or unsupported */ }
        }

        /** True if the user has granted notification-access to BharatDroid. */
        fun isPermissionGranted(context: Context): Boolean {
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            return enabled.split(":").any { it.contains(context.packageName) }
        }

        /**
         * Called by AgentOrchestrator when a user replies (Telegram reply_to) to a
         * forwarded notification. Fires the source app's RemoteInput if available,
         * otherwise returns false so the caller can send an explanation.
         */
        fun sendReply(telegramMsgId: Long, text: String): Boolean {
            val rec = notifMap[telegramMsgId] ?: return false
            val remoteInput = rec.remoteInput ?: return false
            val pending = rec.replyPendingIntent ?: return false
            return try {
                val intent = Intent()
                val bundle = Bundle().apply { putCharSequence(remoteInput.resultKey, text) }
                RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, bundle)
                pending.send(rec.context, 0, intent)
                true
            } catch (_: Throwable) { false }
        }

        /** Expose the tracked record for a given Telegram message id (for fallback routing). */
        fun getRecord(telegramMsgId: Long): NotifRecord? = notifMap[telegramMsgId]

        fun labelFor(context: Context, pkg: String): String {
            return try {
                val pm = context.packageManager
                val info = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(info).toString()
            } catch (_: PackageManager.NameNotFoundException) { pkg }
        }
    }

    data class NotifRecord(
        val context: Context,
        val pkg: String,
        val key: String,
        val remoteInput: RemoteInput?,
        val replyPendingIntent: PendingIntent?,
        val lastTitle: String = "",
        val lastBody: String = "",
        val timestampMs: Long = System.currentTimeMillis(),
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Self-bootstrap: if the AgentForegroundService hasn't started yet (e.g. on boot,
        // or after a crash), read the saved credentials and create a send-only poller so
        // notifications still get forwarded without the full agent running.
        if (bootstrapped.compareAndSet(false, true)) {
            tryBootstrapFromPrefs()
        }
    }

    private fun tryBootstrapFromPrefs() {
        if (poller != null && chatId > 0) return  // already wired by orchestrator
        val prefs = applicationContext.getSharedPreferences("bharatdroid", 0)
        val token = prefs.getString("bot_token", null) ?: return
        val cid = prefs.getLong("chat_id", -1L)
        if (cid <= 0) return
        chatId = cid
        muteStore = MuteStore(applicationContext)
        // Send-only poller — no message polling, just Telegram API sender
        poller = TelegramPoller(token, emptySet(), applicationContext.cacheDir) { "" }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val p = poller ?: return
        val cid = chatId.takeIf { it > 0 } ?: return
        val pkg = sbn.packageName ?: return

        if (pkg in IGNORED_PACKAGES) return
        if (muteStore?.isMuted(pkg) == true) return

        val n = sbn.notification ?: return
        val flags = n.flags
        // Skip media players, ongoing progress, group summaries
        if ((flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        if ((flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if ((flags and Notification.FLAG_FOREGROUND_SERVICE) != 0) return

        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val body = (if (bigText.isNotEmpty()) bigText else text).take(1500)

        if (title.isEmpty() && body.isEmpty()) return

        // Dedup: some apps fire the same notification on update (e.g. progress ticks)
        val hash = (pkg + "|" + title + "|" + body).hashCode()
        val now = System.currentTimeMillis()
        val last = recentHashes[hash]
        if (last != null && now - last < DEDUP_WINDOW_MS) return
        recentHashes[hash] = now
        if (recentHashes.size > 500) {
            val cutoff = now - 30_000L
            recentHashes.entries.removeAll { it.value < cutoff }
        }

        // Extract RemoteInput for reply (first action that has one).
        val (remoteInput, replyPi) = extractReplyAction(n)

        val label = labelFor(this, pkg)
        val message = buildString {
            append("📩 *").append(escapeMd(label)).append("*")
            if (title.isNotEmpty()) { append("\n*").append(escapeMd(title)).append("*") }
            if (body.isNotEmpty()) { append("\n").append(escapeMd(body)) }
            if (remoteInput != null) append("\n\n↩️ *Long\\-press this message → tap Reply* to respond inside ${escapeMd(label)}")
        }

        scope.launch {
            val msgId = p.sendMessage(cid, message) ?: return@launch
            // Track for reply routing
            notifMap[msgId] = NotifRecord(
                context = applicationContext,
                pkg = pkg,
                key = sbn.key,
                remoteInput = remoteInput,
                replyPendingIntent = replyPi,
                lastTitle = title,
                lastBody = body,
            )
            // LRU prune
            if (notifMap.size > MAX_TRACKED) {
                val oldest = notifMap.entries.sortedBy { it.value.timestampMs }
                    .take(notifMap.size - MAX_TRACKED)
                oldest.forEach { notifMap.remove(it.key) }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // keep mapping around for reply window; size-bounded LRU handles cleanup
    }

    private fun extractReplyAction(n: Notification): Pair<RemoteInput?, PendingIntent?> {
        val actions = n.actions ?: return null to null
        for (action in actions) {
            val inputs = action.remoteInputs ?: continue
            val textInput = inputs.firstOrNull { it.allowFreeFormInput }
            if (textInput != null) return textInput to action.actionIntent
        }
        return null to null
    }

    private fun escapeMd(s: String): String =
        s.replace("_", "\\_").replace("*", "\\*").replace("[", "\\[").replace("`", "\\`")
}
