package com.colorfit.companion.ui.notifications

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.colorfit.companion.vendor.NotificationFilter
import com.colorfit.companion.vendor.NotificationRelayService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class NotificationAppsViewModel @Inject constructor(
    private val filter: NotificationFilter,
) : ViewModel() {
    val apps = filter.apps

    fun refresh() = filter.refresh()

    fun setEnabled(packageName: String, enabled: Boolean) = filter.setEnabled(packageName, enabled)
}

/** Per-app switches for which notifications are mirrored to the watch. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationAppsScreen(
    onBack: () -> Unit,
    viewModel: NotificationAppsViewModel = hiltViewModel(),
) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listenerEnabled = isListenerEnabled(context)

    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Watch notifications") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!listenerEnabled) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Notification access is off", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Nothing reaches the watch until Colorfit Companion is allowed to read notifications.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            FilledTonalButton(onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }) { Text("Open notification access") }
                        }
                    }
                }
            }
            item {
                Text(
                    "Messaging and social apps are on by default. Other apps appear here after " +
                        "they post a notification and stay off until you switch them on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (apps.isEmpty()) {
                item {
                    Text(
                        "No apps yet — they'll show up once they post a notification.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        apps.forEachIndexed { i, app ->
                            if (i > 0) HorizontalDivider()
                            AppRow(app) { viewModel.setEnabled(app.packageName, it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: NotificationFilter.AppEntry, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (app.hasWatchIcon) "Own icon on the watch" else "Generic icon on the watch",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = app.enabled, onCheckedChange = onToggle)
    }
}

private fun isListenerEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
    val me = ComponentName(context, NotificationRelayService::class.java).flattenToString()
    return flat.split(':').any { it == me }
}
