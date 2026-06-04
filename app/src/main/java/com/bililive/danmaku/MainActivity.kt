package com.bililive.danmaku

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.bililive.danmaku.domain.model.AppSettings
import com.bililive.danmaku.ui.navigation.AppNavigation
import com.bililive.danmaku.ui.theme.BiliLiveTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as BiliLiveApp

        setContent {
            val settings by app.settingsRepository.observeSettings()
                .collectAsState(initial = AppSettings())

            val darkTheme = if (settings.followSystemDarkMode) {
                isSystemInDarkTheme()
            } else {
                settings.darkMode
            }

            BiliLiveTheme(darkTheme = darkTheme) {
                AppNavigation()
            }
        }
    }
}
