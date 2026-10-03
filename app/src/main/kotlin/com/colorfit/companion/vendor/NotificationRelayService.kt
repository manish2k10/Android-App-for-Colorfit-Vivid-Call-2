package com.colorfit.companion.vendor

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Listens to system notifications and pushes a summarised version to the
 * connected watch via [VendorConnection].
 *
 * Requires the user to grant notification access via
 * `Settings → Notifications → Notification access` (we surface this from
 * the Permissions screen).
 *
 * On Android 13+, only the apps the user has explicitly opted in to
 * (matching `PackageManager.PERMISSION_GRANTED` for `POST_NOTIFICATIONS`)
 * will produce notifications for us to mirror, which is the correct
 * default.
 */
@AndroidEntryPoint
class NotificationRelayService : NotificationListenerService() {

    @Inject lateinit var vendor: VendorConnection
    @Inject lateinit var filter: NotificationFilter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // onNotificationPosted runs on the main thread, so plain fields are fine.
    private var lastPostedAt = 0L
    private var lastPackage: String? = null
    private var lastContent: String? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        Timber.tag(TAG).i("Notification listener connected")
        // Seed the app list from whatever is in the shade right now, so the
        // settings screen isn't empty on first use.
        runCatching { activeNotifications }.getOrNull()
            ?.map { it.packageName }
            ?.distinct()
            ?.forEach { if (it != applicationContext.packageName) filter.recordSeen(it) }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Timber.tag(TAG).i("Notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        // Skip our own notifications (the foreground service).
        if (sbn.packageName == applicationContext.packageName) return
        // Skip ongoing notifications (music playback, navigation, etc.).
        val flags = sbn.notification.flags
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return
        // Skip group summaries — they don't carry useful text.
        if ((sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0 &&
            isGroupSummary(sbn)
        ) return

        val title = extractTitle(sbn.notification)
        val body = extractBody(sbn.notification)
        if (body.isBlank()) return

        // List the app in "Watch notifications", then honour its switch.
        filter.recordSeen(sbn.packageName)
        if (!filter.isEnabled(sbn.packageName)) return

        // Same throttle as the original listener: apps like Telegram re-post
        // a burst of notifications at once, and each push takes seconds.
        val now = System.currentTimeMillis()
        val content = "$title:$body"
        val tooFast = sbn.packageName == lastPackage && now - lastPostedAt < MIN_INTERVAL_MS
        val duplicate = sbn.packageName == lastPackage && content == lastContent
        lastPostedAt = now
        lastPackage = sbn.packageName
        lastContent = content
        if (tooFast || duplicate) return

        val appName = NotificationAppId.nameForPackage(sbn.packageName)
        val alertType = NotificationAppId.alertTypeForPackage(sbn.packageName)
        val appType = NotificationAppId.typeForPackage(sbn.packageName)
        // The type byte already gives known apps their icon on the watch, so
        // only unrecognised apps need their name spelled out in the text.
        val finalTitle = when {
            appType != NotificationAppId.TYPE_OTHER -> title.ifBlank { appName }
            title.isNotBlank() -> "$appName · $title"
            else -> appName
        }

        scope.launch {
            // The text is capped and chunked inside pushNotificationText.
            vendor.pushNotification(finalTitle, body, alertType, appType)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // We don't clear watch-side notifications on removal — the watch's
        // own UI handles dismissal.
        super.onNotificationRemoved(sbn)
    }

    private fun extractTitle(n: Notification): String {
        val extras = n.extras ?: return ""
        // Telegram and WhatsApp put the conversation / group name in
        // EXTRA_CONVERSATION_TITLE (for group chats) and the sender name
        // in EXTRA_TITLE. Prefer the conversation title when it's set —
        // it tells the user *which* chat a notification came from, even
        // when several are firing at once.
        val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?.toString()
            ?.takeIf { it.isNotBlank() }
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getString(Notification.EXTRA_TITLE)?.toString()
            ?: ""
        return when {
            conversation != null && sender.isNotBlank() -> "$conversation · $sender"
            conversation != null -> conversation
            else -> sender
        }
    }

    private fun extractBody(n: Notification): String {
        val extras = n.extras ?: return ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getString(Notification.EXTRA_TEXT)?.toString()
            ?: ""
        // BigTextStyle messages have their full text under EXTRA_BIG_TEXT.
        if (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) != null) {
            return extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: text
        }
        return text
    }

    private fun isGroupSummary(sbn: StatusBarNotification): Boolean {
        val extras = sbn.notification.extras ?: return false
        return extras.getString(Notification.EXTRA_TEMPLATE) != null
    }

    companion object {
        private const val TAG = "NotifRelay"

        /** `beginTime - lastBeginTime < 200` in the original listener. */
        private const val MIN_INTERVAL_MS = 200L
    }
}
