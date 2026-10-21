package com.bililive.danmaku.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bililive.danmaku.ui.display.DisplaySettingsControls
import com.bililive.danmaku.BiliLiveApp
import com.bililive.danmaku.domain.model.AppSettings
import com.bililive.danmaku.domain.repository.SettingsRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToLogin: () -> Unit,
    settingsRepository: SettingsRepository
) {
    val settings by settingsRepository.observeSettings().collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    var keywordInput by remember { mutableStateOf("") }
    val app = LocalContext.current.applicationContext as BiliLiveApp
    val loginState by remember { mutableStateOf(app.loginManager.getLoginState()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ===== Account Section =====
            Text("账号", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            // 登录状态
            Card(
                onClick = onNavigateToLogin,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (loginState.isLoggedIn) Icons.Default.Person else Icons.Default.AccountCircle,
                        contentDescription = null,
                        tint = if (loginState.isLoggedIn)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (loginState.isLoggedIn) loginState.username else "未登录",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            if (loginState.isLoggedIn) "已登录 · 获取完整弹幕" else "登录后可获取完整弹幕",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "进入",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 登录提示
            if (!loginState.isLoggedIn) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "未登录时弹幕会被B站限流，建议登录后使用",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            Divider()

            // ===== Connection Section =====
            Text("连接", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            // Mock Mode
            SettingsSwitch(
                title = "Mock 模式",
                subtitle = "使用模拟数据，不连接 B站",
                checked = settings.mockMode,
                onCheckedChange = { scope.launch { settingsRepository.updateMockMode(it) } }
            )

            // Mock Rate
            if (settings.mockMode) {
                Text("Mock 速率: ${settings.mockRate} msg/s", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 100, 300).forEach { rate ->
                        FilterChip(
                            selected = settings.mockRate == rate,
                            onClick = { scope.launch { settingsRepository.updateMockRate(rate) } },
                            label = { Text("$rate/s") }
                        )
                    }
                }
            }

            // Auto Reconnect
            SettingsSwitch(
                title = "自动重连",
                subtitle = "断线后自动尝试重新连接",
                checked = settings.autoReconnect,
                onCheckedChange = { scope.launch { settingsRepository.updateAutoReconnect(it) } }
            )

            Divider()

            // ===== Filter Section =====
            Text("过滤", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            SettingsSwitch(
                title = "关键词过滤",
                subtitle = "屏蔽包含指定关键词的弹幕",
                checked = settings.keywordFilterEnabled,
                onCheckedChange = { scope.launch { settingsRepository.updateKeywordFilterEnabled(it) } }
            )

            if (settings.keywordFilterEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = keywordInput,
                        onValueChange = { keywordInput = it },
                        label = { Text("添加关键词") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            if (keywordInput.isNotBlank()) {
                                val newList = settings.keywordFilterList + keywordInput.trim()
                                scope.launch { settingsRepository.updateKeywordFilterList(newList) }
                                keywordInput = ""
                            }
                        },
                        enabled = keywordInput.isNotBlank()
                    ) { Text("添加") }
                }

                if (settings.keywordFilterList.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        settings.keywordFilterList.forEach { keyword ->
                            InputChip(
                                selected = false,
                                onClick = {
                                    val newList = settings.keywordFilterList - keyword
                                    scope.launch { settingsRepository.updateKeywordFilterList(newList) }
                                },
                                label = { Text(keyword) },
                                trailingIcon = { Icon(Icons.Default.Close, "删除", modifier = Modifier.size(16.dp)) }
                            )
                        }
                    }
                }
            }

            // Duplicate fold
            SettingsSwitch(
                title = "重复弹幕折叠",
                subtitle = "3秒内相同内容只显示一次",
                checked = settings.duplicateFoldEnabled,
                onCheckedChange = { scope.launch { settingsRepository.updateDuplicateFoldEnabled(it) } }
            )

            Divider()

            // ===== Performance Section =====
            Text("性能", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            Text("最大保留弹幕数: ${settings.maxDanmakuCount}", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(300, 500, 1000).forEach { count ->
                    FilterChip(
                        selected = settings.maxDanmakuCount == count,
                        onClick = { scope.launch { settingsRepository.updateMaxDanmakuCount(count) } },
                        label = { Text("$count") }
                    )
                }
            }

            Divider()

            Text("常亮与弹幕显示", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)
            DisplaySettingsControls(settings.display) { updated ->
                scope.launch { settingsRepository.updateDisplaySettings(updated) }
            }
            Divider()

            // ===== Appearance Section =====
            Text("外观", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            SettingsSwitch(
                title = "跟随系统暗色模式",
                subtitle = "自动切换深色/浅色主题",
                checked = settings.followSystemDarkMode,
                onCheckedChange = { scope.launch { settingsRepository.updateFollowSystemDarkMode(it) } }
            )

            if (!settings.followSystemDarkMode) {
                SettingsSwitch(
                    title = "暗色模式",
                    subtitle = "手动切换深色主题",
                    checked = settings.darkMode,
                    onCheckedChange = { scope.launch { settingsRepository.updateDarkMode(it) } }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
