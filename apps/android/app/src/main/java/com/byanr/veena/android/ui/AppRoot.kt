package com.byanr.veena.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.byanr.veena.android.AppViewModel
import com.byanr.veena.android.data.ModelCatalog
import com.byanr.veena.android.ui.dictate.DictateScreen
import com.byanr.veena.android.ui.history.HistoryDetailScreen
import com.byanr.veena.android.ui.history.HistoryScreen
import com.byanr.veena.android.ui.onboarding.LanguagePickerScreen
import com.byanr.veena.android.ui.onboarding.ModelDownloadScreen
import com.byanr.veena.android.ui.settings.SettingsScreen
import com.byanr.veena.android.ui.theme.veenaColors

private data class TabSpec(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val TABS = listOf(
    TabSpec("dictate", "Dictate", Icons.Filled.Mic, Icons.Outlined.Mic),
    TabSpec("history", "History", Icons.Filled.History, Icons.Outlined.History),
    TabSpec("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
)

@Composable
fun AppRoot(appViewModel: AppViewModel = viewModel()) {
    val settings by appViewModel.settings.collectAsState()
    val colors = veenaColors

    val languageCode = settings?.languageCode

    // Load (or reload) the engine whenever the selected language changes.
    LaunchedEffect(languageCode) {
        if (languageCode != null) appViewModel.warmEngine()
    }

    val current = settings
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // Recreate the graph once onboarding finishes so "dictate" becomes home.
    key(current.languageCode == null) {
        val onboarding = current.languageCode == null
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route
        val showBottomBar =
            !onboarding && currentRoute in TABS.map { it.route }

        Scaffold(
            containerColor = colors.background,
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar(containerColor = colors.background) {
                        TABS.forEach { tab ->
                            val selected = currentRoute == tab.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label,
                                    )
                                },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = if (onboarding) "picker/onboarding" else "dictate",
                modifier = Modifier
                    .fillMaxSize()
                    // Edge-to-edge: keep every screen below the status bar / camera cutout.
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(bottom = padding.calculateBottomPadding()),
            ) {
                composable("dictate") {
                    DictateScreen(
                        appViewModel = appViewModel,
                        onOpenLanguagePicker = {
                            navController.navigate("picker/dictate")
                        },
                    )
                }
                composable("history") {
                    HistoryScreen(
                        onOpenEntry = { id ->
                            navController.navigate("detail/$id")
                        },
                    )
                }
                composable("settings") {
                    SettingsScreen(
                        appViewModel = appViewModel,
                        onChangeLanguage = {
                            navController.navigate("picker/settings")
                        },
                    )
                }
                composable("detail/{id}") { entry ->
                    val id = entry.arguments?.getLong("id") ?: return@composable
                    HistoryDetailScreen(
                        entryId = id,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable("picker/{mode}") { entry ->
                    val mode = entry.arguments?.getString("mode") ?: "onboarding"
                    LanguagePickerScreen(
                        title = if (mode == "onboarding") {
                            "Welcome to Veena"
                        } else {
                            "Choose your language"
                        },
                        subtitle = if (mode == "onboarding") {
                            "Pick the language you'll dictate in. Its voice model downloads next, and you can switch languages anytime in Settings."
                        } else {
                            "A new language downloads its voice model the first time you pick it."
                        },
                        selectedCode = current.languageCode,
                        onPick = { language ->
                            val def = ModelCatalog.modelForLanguage(language.code)
                            if (appViewModel.models.isDownloaded(def)) {
                                appViewModel.setLanguage(language.code)
                                if (mode == "onboarding") {
                                    navController.navigate("dictate") {
                                        popUpTo(0) { inclusive = true }
                                    }
                                } else {
                                    navController.popBackStack()
                                }
                            } else {
                                navController.navigate("download/$mode/${language.code}")
                            }
                        },
                        onBack = if (mode == "settings" || mode == "dictate") {
                            { navController.popBackStack() }
                        } else null,
                    )
                }
                composable(
                    route = "download/{mode}/{lang}",
                    arguments = listOf(
                        navArgument("mode") { type = NavType.StringType },
                        navArgument("lang") { type = NavType.StringType },
                    ),
                ) { entry ->
                    val mode = entry.arguments?.getString("mode") ?: "onboarding"
                    val lang = entry.arguments?.getString("lang") ?: "te"
                    val def = ModelCatalog.modelForLanguage(lang)
                    ModelDownloadScreen(
                        appViewModel = appViewModel,
                        def = def,
                        headline = if (mode == "onboarding") {
                            "Downloading your voice model"
                        } else {
                            "Downloading language model"
                        },
                        onSuccess = {
                            appViewModel.setLanguage(lang)
                            when (mode) {
                                "onboarding" -> {
                                    navController.navigate("dictate") {
                                        popUpTo(0) { inclusive = true }
                                    }
                                }
                                // Opened from the home-page language chip: pop the
                                // download screen and the picker, back to Dictate.
                                "dictate" -> navController.popBackStack("dictate", inclusive = false)
                                else -> navController.popBackStack("settings", inclusive = false)
                            }
                        },
                        onCancel = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}
