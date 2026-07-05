package de.heimai.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.heimai.app.core.settings.AppSettings
import de.heimai.app.features.chat.ChatScreen
import de.heimai.app.features.home.HomeScreen
import de.heimai.app.features.settings.AccountSettingsScreen
import de.heimai.app.features.settings.AiSettingsScreen
import de.heimai.app.features.settings.AppearanceSettingsScreen
import de.heimai.app.features.settings.SettingsScreen
import de.heimai.app.features.setup.LoginScreen
import de.heimai.app.features.setup.ServerScreen
import de.heimai.app.features.talk.TalkScreen
import de.heimai.app.ui.theme.HeimAiTheme
import de.heimai.app.wakeword.WakeWordService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = HeimAiApp.from(application).container
        val initial = container.settings.currentBlocking()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Karten-Layouts + zentrale Client-Config (Wake-Word-Key) vom Server aktualisieren
        lifecycleScope.launch {
            if (initial.isLoggedIn) {
                container.cardLayouts.sync()
                container.api.syncClientConfig()
            }
        }
        if (initial.wakeWordEnabled && initial.effectiveWakeWordKey.isNotBlank()) {
            WakeWordService.start(this)
        }

        val startDestination = when {
            !initial.isConfigured -> "server"
            !initial.isLoggedIn -> "login"
            else -> "home"
        }

        setContent {
            val settings by container.settings.settings.collectAsState(initial = initial)
            HeimAiTheme(settings.themeName, settings.darkMode) {
                AppNavHost(startDestination, settings)
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun AppNavHost(startDestination: String, settings: AppSettings) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = startDestination, modifier = Modifier) {
        composable("server") {
            ServerScreen(onConnected = { navController.navigate("login") })
        }
        composable("login") {
            LoginScreen(
                onChangeServer = { navController.navigate("server") },
                onLoggedIn = {
                    navController.navigate("home") { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable("home") {
            HomeScreen(
                onNewChat = { navController.navigate("chat/0") },
                onOpenChat = { id -> navController.navigate("chat/$id") },
                onTalk = { navController.navigate("talk") },
                onSettings = { navController.navigate("settings") },
            )
        }
        composable(
            "chat/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            ChatScreen(
                conversationId = entry.arguments?.getLong("id") ?: 0L,
                onBack = { navController.popBackStack() },
            )
        }
        composable("talk") {
            TalkScreen(onBack = { navController.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onAccount = { navController.navigate("settings/account") },
                onAppearance = { navController.navigate("settings/appearance") },
                onAi = { navController.navigate("settings/ai") },
            )
        }
        composable("settings/account") {
            AccountSettingsScreen(
                onBack = { navController.popBackStack() },
                onLoggedOut = {
                    navController.navigate("login") { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable("settings/appearance") {
            AppearanceSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable("settings/ai") {
            AiSettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
