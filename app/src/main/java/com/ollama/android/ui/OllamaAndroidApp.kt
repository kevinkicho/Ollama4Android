package com.ollama.android.ui

import android.content.Context
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ollama.android.ui.chat.ChatScreen
import com.ollama.android.ui.models.ModelsScreen
import com.ollama.android.ui.settings.SettingsScreen
import com.ollama.android.ui.setup.SetupScreen

sealed class Screen(val route: String, val title: String) {
    data object Setup : Screen("setup", "Setup")
    data object Chat : Screen("chat", "Chat")
    data object Models : Screen("models", "Models")
    data object Settings : Screen("settings", "Settings")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OllamaAndroidApp() {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("ollama_prefs", Context.MODE_PRIVATE)
    }
    val setupCompleted = remember { prefs.getBoolean("setup_completed", false) }

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val startDestination = if (setupCompleted) Screen.Chat.route else Screen.Setup.route
    val showBottomBar = currentRoute != Screen.Setup.route

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Chat, contentDescription = "Chat") },
                        label = { Text("Chat") },
                        selected = currentRoute == Screen.Chat.route,
                        onClick = {
                            navController.navigate(Screen.Chat.route) {
                                popUpTo(Screen.Chat.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Download, contentDescription = "Models") },
                        label = { Text("Models") },
                        selected = currentRoute == Screen.Models.route,
                        onClick = {
                            navController.navigate(Screen.Models.route) {
                                popUpTo(Screen.Chat.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") },
                        selected = currentRoute == Screen.Settings.route,
                        onClick = {
                            navController.navigate(Screen.Settings.route) {
                                popUpTo(Screen.Chat.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
        ) {
            composable(Screen.Setup.route) {
                SetupScreen(
                    onSetupComplete = {
                        navController.navigate(Screen.Chat.route) {
                            popUpTo(Screen.Setup.route) { inclusive = true }
                        }
                    }
                )
            }
            composable(Screen.Chat.route) { ChatScreen() }
            composable(Screen.Models.route) { ModelsScreen() }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onOpenSetup = {
                        navController.navigate(Screen.Setup.route)
                    }
                )
            }
        }
    }
}
