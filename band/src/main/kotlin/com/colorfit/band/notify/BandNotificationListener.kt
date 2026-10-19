package com.colorfit.band.notify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.colorfit.band.BandSession
import com.colorfit.band.protocol.BandMessageType
import com.colorfit.band.protocol.BandText
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * Mirrors phone notifications to the band, for the apps switched on in
 * [NotificationFilter]. Needs "Notification access" for this app.
 */
@AndroidEntryPoint
class BandNotificationListener : NotificationListenerService() {

    @Inject lateinit var session: BandSession
    @Inject lateinit var filter: NotificationFilter

    // onNotificationPosted runs on the main thread, so plain fields are fine.
    private val lastPostedAt = HashMap<String, Long>()
    private val lastText = HashMap<String, String>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching { activeNotifications }.getOrNull()
            ?.map { it.packageName }?.distinct()
            ?.forEach { if (it != packageName) filter.recordSeen(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val pkg = sbn.packageName
        if (pkg == packageName) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = n.extras ?: return
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim().orEmpty()
        if (body.isEmpty()) return
        // The notification title as-is, like NoiseFit Track (`d3/e.b` gets EXTRA_TITLE).
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()

        filter.recordSeen(pkg)
        if (!filter.isEnabled(pkg)) return

        // `d3/b.a` + `d`: the original drops a repeat from the same app within
        // 600 ms (5 s for WhatsApp, which re-posts a lot) and identical text.
        val now = System.currentTimeMillis()
        val window = if (pkg == "com.whatsapp") 5_000L else 600L
        val key = "$title:$body"
        val tooFast = now - (lastPostedAt[pkg] ?: 0L) < window
        val duplicate = lastText[pkg] == key
        if (tooFast || duplicate) return
        lastPostedAt[pkg] = now
        lastText[pkg] = key

        val label = filter.label(packageManager, pkg)
        val text = BandText.forBand(title, body, label)
        val type = BandMessageType.forPackage(pkg, filter.defaultSmsPackage())
        val sent = session.pushMessage(type, text)
        Timber.tag(TAG).d("notification from %s (type %d, %d bytes) sent=%s", pkg, type, text.size, sent)
    }

    companion object {
        private const val TAG = "BandNotify"
    }
}
