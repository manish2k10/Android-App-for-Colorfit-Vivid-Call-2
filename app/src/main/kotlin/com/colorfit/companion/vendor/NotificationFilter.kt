package com.colorfit.companion.vendor

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which apps' notifications are mirrored to the watch.
 *
 * The original app keeps one on/off switch per app on the phone
 * (`StatusbarMsgNotificationListener.isRemindSwitch()`); this is the same idea.
 * An app the user hasn't touched is on if the watch has an icon for it
 * ([NotificationAppId.typeForPackage]) — messaging and social apps — and off
 * otherwise, so system and promotional notifications stay off the watch until
 * the user opts them in.
 *
 * Apps show up in the list once they have posted a notification (recorded by
 * [NotificationRelayService]), plus any app with a watch icon that is installed.
 */
@Singleton
class NotificationFilter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class AppEntry(
        val packageName: String,
        val label: String,
        val enabled: Boolean,
        /** True when the watch shows a dedicated icon for this app. */
        val hasWatchIcon: Boolean,
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    init {
        refresh()
    }

    /** Whether a notification from [packageName] should go to the watch. */
    fun isEnabled(packageName: String): Boolean {
        val key = KEY_APP_PREFIX + packageName
        return if (prefs.contains(key)) prefs.getBoolean(key, false) else defaultFor(packageName)
    }

    fun setEnabled(packageName: String, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_APP_PREFIX + packageName, enabled).apply()
        refresh()
    }

    /** Remember that [packageName] posts notifications so it appears in the list. */
    fun recordSeen(packageName: String) {
        synchronized(lock) {
            val seen = seenPackages()
            if (packageName in seen) return
            prefs.edit().putStringSet(KEY_SEEN, seen + packageName).apply()
        }
        refresh()
    }

    /** Rebuild [apps] — call when the list screen opens to pick up new installs. */
    fun refresh() {
        val pm = context.packageManager
        val candidates = seenPackages() + NotificationAppId.packagesWithWatchIcon
            .filter { isInstalled(pm, it) }
        _apps.value = candidates
            .filter { it != context.packageName }
            .map { pkg ->
                AppEntry(
                    packageName = pkg,
                    label = labelFor(pm, pkg),
                    enabled = isEnabled(pkg),
                    hasWatchIcon = NotificationAppId.typeForPackage(pkg) != NotificationAppId.TYPE_OTHER,
                )
            }
            .sortedWith(compareByDescending<AppEntry> { it.enabled }.thenBy { it.label.lowercase() })
    }

    private fun seenPackages(): Set<String> =
        prefs.getStringSet(KEY_SEEN, emptySet())?.toSet() ?: emptySet()

    private fun defaultFor(packageName: String): Boolean =
        NotificationAppId.typeForPackage(packageName) != NotificationAppId.TYPE_OTHER

    private fun isInstalled(pm: PackageManager, pkg: String): Boolean =
        runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess

    private fun labelFor(pm: PackageManager, pkg: String): String =
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: NotificationAppId.nameForPackage(pkg)

    companion object {
        private const val PREFS_NAME = "colorfit_notification_filter"
        private const val KEY_APP_PREFIX = "app:"
        private const val KEY_SEEN = "seen_packages"
    }
}
