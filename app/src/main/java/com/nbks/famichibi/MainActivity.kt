package com.nbks.famichibi

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nbks.famichibi.data.PreferencesRepository
import com.nbks.famichibi.ui.channels.ChannelScreen
import com.nbks.famichibi.ui.home.HomeScreen
import com.nbks.famichibi.ui.notebook.NotebookScreen
import com.nbks.famichibi.ui.server.ServerScreen
import com.nbks.famichibi.ui.server.ServerSettingsScreen
import com.nbks.famichibi.ui.settings.SettingsScreen
import com.nbks.famichibi.ui.theme.FamiChibiTheme
import com.nbks.famichibi.ui.voice.VoiceChannelScreen

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        parseDeepLink(intent)
        enableEdgeToEdge()
        val prefs = PreferencesRepository(applicationContext)
        setContent {
            FamiChibiTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route
                val showBottomBar = currentRoute in listOf("home", "settings")

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = {
                                Text("FamiChibi", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            },
                            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                titleContentColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    },
                    bottomBar = {
                        if (showBottomBar) {
                            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                                    label = { Text("ホーム") },
                                    selected = currentRoute == "home",
                                    onClick = { navController.navigate("home") { popUpTo("home") { inclusive = true } } }
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                    label = { Text("設定") },
                                    selected = currentRoute == "settings",
                                    onClick = { navController.navigate("settings") }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "home",
                        modifier = Modifier.padding(innerPadding),
                        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, animationSpec = tween(220)) },
                        exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, animationSpec = tween(220)) },
                        popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, animationSpec = tween(220)) },
                        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, animationSpec = tween(220)) }
                    ) {
                        composable("home") { HomeScreen(navController, prefs, snackbarHostState) }
                        composable("server") { ServerScreen(navController, prefs, snackbarHostState) }
                        composable("server_settings") { ServerSettingsScreen(navController, prefs, snackbarHostState) }
                        composable("channel") { ChannelScreen(navController, prefs, snackbarHostState) }
                        composable("voice") { VoiceChannelScreen(navController, prefs, snackbarHostState) }
                        composable("notebook") { NotebookScreen(navController, prefs, snackbarHostState) }
                        composable("settings") { SettingsScreen(navController, prefs, snackbarHostState) }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        parseDeepLink(intent)
    }

    private fun parseDeepLink(intent: Intent?) {
        val data: Uri? = intent?.data
        if (data?.scheme == "famichibi" && data.host == "join") {
            val host = data.getQueryParameter("host")
            val invite = data.getQueryParameter("invite")
            if (!host.isNullOrBlank() && !invite.isNullOrBlank()) {
                PendingDeepLink.set(host, invite)
            }
        }
    }
}