package com.bililive.danmaku.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bililive.danmaku.BiliLiveApp
import com.bililive.danmaku.ui.danmaku.DanmakuScreen
import com.bililive.danmaku.ui.danmaku.DanmakuViewModel
import com.bililive.danmaku.ui.login.LoginScreen
import com.bililive.danmaku.ui.room.RoomInputScreen
import com.bililive.danmaku.ui.room.RoomInputViewModel
import com.bililive.danmaku.ui.settings.SettingsScreen

object Routes {
    const val ROOM_INPUT = "room_input"
    const val DANMAKU = "danmaku/{roomId}"
    const val SETTINGS = "settings"
    const val LOGIN = "login"

    fun danmaku(roomId: Long) = "danmaku/$roomId"
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val app = LocalContext.current.applicationContext as BiliLiveApp

    NavHost(navController = navController, startDestination = Routes.ROOM_INPUT) {
        composable(Routes.ROOM_INPUT) {
            val viewModel = RoomInputViewModel(
                settingsRepository = app.settingsRepository,
                validateRoomIdUseCase = app.validateRoomIdUseCase
            )
            RoomInputScreen(
                onNavigateToDanmaku = { roomId ->
                    navController.navigate(Routes.danmaku(roomId))
                },
                onNavigateToSettings = {
                    navController.navigate(Routes.SETTINGS)
                },
                viewModel = viewModel
            )
        }

        composable(
            route = Routes.DANMAKU,
            arguments = listOf(navArgument("roomId") { type = NavType.LongType })
        ) { backStackEntry ->
            val roomId = backStackEntry.arguments?.getLong("roomId") ?: return@composable
            val viewModel: DanmakuViewModel = viewModel(
                factory = DanmakuViewModel.Factory(
                    roomId = roomId,
                    connectRoomUseCase = app.connectRoomUseCase,
                    disconnectUseCase = app.disconnectUseCase,
                    danmakuRepository = app.danmakuRepository,
                    settingsRepository = app.settingsRepository
                )
            )
            DanmakuScreen(
                onBack = { navController.popBackStack() },
                viewModel = viewModel
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToLogin = { navController.navigate(Routes.LOGIN) },
                settingsRepository = app.settingsRepository
            )
        }

        composable(Routes.LOGIN) {
            LoginScreen(
                loginManager = app.loginManager,
                onLoginSuccess = { navController.popBackStack() },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
