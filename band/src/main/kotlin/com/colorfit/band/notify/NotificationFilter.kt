package com.colorfit.band.notify

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import com.colorfit.band.protocol.BandMessageType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which apps' notifications go to the band. Messaging apps are on until the
 * user switches them off; anything else appears after its first notification
 * and stays off until switched on.
 */
@Singleton
class NotificationFilter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class AppEntry(
        val packageName: String,
        val label: String,
        val enabled: Boolean,
        /** The band shows this app's own icon rather than the generic one. */
        val hasBandIcon: Boolean,
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    init {
        refresh()
    }

    fun defaultSmsPackage(): String? = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()

    fun isEnabled(packageName: String): Boolean {
        val key = KEY_APP_PREFIX + packageName
        return if (prefs.contains(key)) prefs.getBoolean(key, false) else isMessagingApp(packageName)
    }

    fun setEnabled(packageName: String, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_APP_PREFIX + packageName, enabled).apply()
        refresh()
    }

    fun recordSeen(packageName: String) {
        synchronized(lock) {
            val seen = seen()
            if (packageName in seen) return
            prefs.edit().putStringSet(KEY_SEEN, seen + packageName).apply()
        }
        refresh()
    }

    fun refresh() {
        val pm = context.packageManager
        val sms = defaultSmsPackage()
        val candidates = seen() + (MESSAGING_APPS + listOfNotNull(sms)).filter { installed(pm, it) }
        _apps.value = candidates
            .filter { it != context.packageName }
            .map { pkg ->
                AppEntry(pkg, label(pm, pkg), isEnabled(pkg), BandMessageType.hasOwnIcon(pkg, sms))
            }
            .sortedWith(compareByDescending<AppEntry> { it.enabled }.thenBy { it.label.lowercase() })
    }

    private fun isMessagingApp(pkg: String): Boolean = pkg in MESSAGING_APPS || pkg == defaultSmsPackage()

    private fun seen(): Set<String> = prefs.getStringSet(KEY_SEEN, emptySet())?.toSet() ?: emptySet()

    private fun installed(pm: PackageManager, pkg: String) = runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess

    fun label(pm: PackageManager, pkg: String): String =
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: pkg.substringAfterLast('.')

    companion object {
        private const val PREFS_NAME = "colorfit_band_notification_filter"
        private const val KEY_APP_PREFIX = "app:"
        private const val KEY_SEEN = "seen_packages"

        /** On by default. */
        private val MESSAGING_APPS = setOf(
            "com.whatsapp", "com.whatsapp.w4b",
            "org.telegram.messenger", "org.thoughtcrime.securesms",
            "com.instagram.android", "com.facebook.katana", "com.facebook.orca",
            "com.twitter.android", "com.skype.raider", "com.viber.voip",
            "jp.naver.line.android", "com.kakao.talk", "com.tencent.mm",
            "com.snapchat.android", "com.microsoft.teams",
            "com.google.android.apps.messaging", "com.samsung.android.messaging",
        )
    }
}
