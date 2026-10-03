package com.colorfit.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.colorfit.companion.ble.BlePermissions
import com.colorfit.companion.ui.home.HomeScreen
import com.colorfit.companion.ui.home.HomeViewModel
import com.colorfit.companion.ui.notifications.NotificationAppsScreen
import com.colorfit.companion.ui.permissions.PermissionsScreen
import com.colorfit.companion.ui.theme.ColorfitTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ColorfitTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNav()
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AppNav() {
    val nav = rememberNavController()
    val homeVm: HomeViewModel = hiltViewModel()

    val permsGranted by homeVm.allRequiredPermissionsGranted.collectAsStateWithLifecycle()

    NavHost(
        navController = nav,
        startDestination = if (permsGranted) "home" else "permissions",
    ) {
        composable("permissions") {
            PermissionsScreen(
                current = BlePermissions.snapshot(LocalContext.current),
                onAllGranted = {
                    nav.navigate("home") {
                        popUpTo("permissions") { inclusive = true }
                    }
                },
            )
        }
        composable("home") {
            HomeScreen(
                viewModel = homeVm,
                onOpenNotificationApps = { nav.navigate("notifications") },
            )
        }
        composable("notifications") {
            NotificationAppsScreen(onBack = { nav.popBackStack() })
        }
    }
}
