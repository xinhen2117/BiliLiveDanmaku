package com.bililive.danmaku.ui.danmaku

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bililive.danmaku.BiliLiveApp
import com.bililive.danmaku.domain.model.*
import com.bililive.danmaku.ui.display.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DanmakuScreen(onBack: () -> Unit, viewModel: DanmakuViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val repository = (LocalContext.current.applicationContext as BiliLiveApp).settingsRepository
    val settingsFlow = remember(repository) { repository.observeSettings() }
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = AppSettings())
    val display = settings.display.normalized()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var lowPowerOverride by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val lowPower = (lowPowerOverride ?: display.lowPowerMode) && !showSettings
    val currentShowSettings = rememberUpdatedState(showSettings)
    val listState = rememberLazyListState()
    val configuration = LocalConfiguration.current
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var interactionVersion by remember { mutableLongStateOf(0L) }
    var idleStage by remember { mutableIntStateOf(0) }
    var shiftStep by rememberSaveable { mutableLongStateOf(0L) }
    val accessibility = LocalAccessibilityManager.current
    val controlTimeout = accessibility?.calculateRecommendedTimeoutMillis(
        DisplayPolicy.CONTROLS_TIMEOUT_MS, containsText = true, containsIcons = true, containsControls = true
    ) ?: DisplayPolicy.CONTROLS_TIMEOUT_MS
    fun interact() { interactionVersion++; controlsVisible = true; idleStage = 0 }

    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            // Stop synchronously on PAUSE; do not rely on a background recomposition.
            viewModel.setUiVisible(resumed && !currentShowSettings.value)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); viewModel.setUiVisible(false) }
    }
    LaunchedEffect(resumed, showSettings, viewModel) { viewModel.setUiVisible(resumed && !showSettings) }
    LaunchedEffect(lowPower) { viewModel.setLowPowerMode(lowPower); interact() }
    DisplayWindowEffect(
        keepScreenOn = display.keepScreenOn && !showSettings,
        brightness = DisplayPolicy.brightness(display, lowPower, idleStage),
        immersive = lowPower
    )

    // These are bounded deadlines, not polling. Every new visible arrival or touch cancels idle dimming.
    LaunchedEffect(lifecycle, lowPower, display.idleDimmingEnabled, display.idleTimeoutSeconds,
        uiState.receivedVersion, interactionVersion) {
        idleStage = 0
        if (lowPower && display.idleDimmingEnabled) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                idleStage = 0
                delay(DisplayPolicy.idleDelayMillis(display))
                idleStage = 1
                delay(15_000L)
                idleStage = 2
            }
        }
    }
    LaunchedEffect(lifecycle, lowPower, interactionVersion, controlTimeout) {
        controlsVisible = true
        if (lowPower) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            controlsVisible = true
            delay(controlTimeout)
            controlsVisible = false
        }
    }
    LaunchedEffect(lifecycle, lowPower, display.pixelShiftEnabled) {
        if (lowPower && display.pixelShiftEnabled) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) { delay(DisplayPolicy.SHIFT_INTERVAL_MS); shiftStep++ }
            }
        }
    }
    BackHandler(enabled = lowPowerOverride ?: display.lowPowerMode) {
        lowPowerOverride = false
        interact()
    }

    // Index alone stops changing when a bounded history fills; version continues to advance.
    LaunchedEffect(uiState.receivedVersion, uiState.autoScroll, resumed, controlsVisible, lowPower,
        configuration.screenWidthDp, configuration.screenHeightDp) {
        if (resumed && uiState.autoScroll && uiState.messages.isNotEmpty()) {
            listState.scrollToItem(uiState.messages.lastIndex) // No continuous autoscroll animation.
        }
    }
    LaunchedEffect(listState, viewModel) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) viewModel.setAutoScroll(false)
        }
    }

    val offset = if (lowPower && display.pixelShiftEnabled) DisplayPolicy.offset(shiftStep) else 0 to 0
    val colors = if (lowPower) darkColorScheme(
        background = Color.Black, surface = Color.Black, surfaceVariant = Color.Black,
        onBackground = Color(0xFFB8B8B8), onSurface = Color(0xFFB8B8B8),
        onSurfaceVariant = Color(0xFF999999), primary = Color(0xFFAAAAAA),
        onPrimary = Color.Black, primaryContainer = Color(0xFF242424)
    ) else MaterialTheme.colorScheme

    MaterialTheme(colorScheme = colors) {
        BoxWithConstraints(Modifier.fillMaxSize().background(if (lowPower) Color.Black else colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .semantics {
                customActions = listOf(CustomAccessibilityAction("显示弹幕控制") { interact(); true })
            }
            .pointerInput(Unit) {
                // Observe presses without consuming gestures: list scroll and child controls stay usable.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.pressed && !it.previousPressed }) interact()
                    }
                }
            }) {
            val compactToolbar = DisplayPolicy.compactToolbar(maxWidth.value.toInt(), maxHeight.value.toInt())
            val wideMessages = maxWidth >= 600.dp
            val shiftPadding = if (lowPower) DisplayPolicy.SHIFT_PADDING_DP.dp else 0.dp
            // Shift the WHOLE app-owned foreground, including labels, buttons, empty/error states.
            Column(Modifier.fillMaxSize().padding(shiftPadding).offset(offset.first.dp, offset.second.dp)
                .alpha(DisplayPolicy.contentAlpha(idleStage))) {
                if (!lowPower || controlsVisible) {
                    DisplayToolbar(
                        roomId = uiState.roomId, lowPower = lowPower,
                        onBack = { if (lowPower) lowPowerOverride = false else onBack() },
                        onTogglePower = { lowPowerOverride = !lowPower; interact() },
                        onSettings = { showSettings = true; interact() },
                        onClear = viewModel::clearScreen,
                        status = uiState.status, onConnect = viewModel::connect, onDisconnect = viewModel::disconnect
                    )
                    if (!lowPower) {
                        Text(
                            "${statusText(uiState.status)} · ${uiState.totalCount} 条 · ${uiState.messagesPerSecond}/秒" +
                                if (uiState.overflowCount > 0) " · 缓存淘汰 ${uiState.overflowCount}" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = if (compactToolbar) 0.dp else 4.dp)
                        )
                    } else {
                        Text("轻触显示控制 · 返回退出低功耗 · ${statusText(uiState.status)}" +
                                if (uiState.overflowCount > 0) " · 缓存淘汰 ${uiState.overflowCount}" else "",
                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
                // Errors remain discoverable even when chrome is hidden; they move with the content.
                if (uiState.errorMessage != null) {
                    TextButton(onClick = { viewModel.dismissError(); interact() }) {
                        Text("${uiState.errorMessage}（点此关闭）", color = colors.onSurface)
                    }
                } else if (lowPower && uiState.status != ConnectionStatus.Connected) {
                    Text(statusText(uiState.status), style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                }
                if (uiState.messages.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("等待弹幕 · 轻触显示控制", color = colors.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(if (display.compactRows) 2.dp else 6.dp)
                    ) {
                        items(uiState.messages, key = { it.id }, contentType = { "danmaku" }) { item ->
                            DanmakuMessageItem(item, display, wide = wideMessages, lowPower = lowPower)
                        }
                    }
                }
                if (!uiState.autoScroll && (!lowPower || controlsVisible)) {
                    TextButton(onClick = { viewModel.scrollToBottom(); interact() }, Modifier.align(Alignment.CenterHorizontally)) {
                        Text(if (uiState.unreadCount > 0) "${uiState.unreadCount} 条新弹幕 · 回到最新" else "回到最新弹幕")
                    }
                }
            }
        }
        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false; interact() },
                title = { Text("显示设置") },
                text = {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DisplaySettingsControls(display) { updated ->
                            scope.launch { repository.updateDisplaySettings(updated) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showSettings = false; interact() }) { Text("完成") } }
            )
        }
    }
}

@Composable
private fun DisplayToolbar(
    roomId: Long?, lowPower: Boolean, onBack: () -> Unit, onTogglePower: () -> Unit,
    onSettings: () -> Unit, onClear: () -> Unit, status: ConnectionStatus,
    onConnect: () -> Unit, onDisconnect: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val canDisconnect = status == ConnectionStatus.Connected || status == ConnectionStatus.Connecting ||
        status == ConnectionStatus.Reconnecting
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, if (lowPower) "退出低功耗" else "返回房间输入") }
        Text("房间 ${roomId ?: ""}", style = MaterialTheme.typography.titleSmall, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconButton(onClick = onTogglePower) { Icon(Icons.Default.BrightnessLow, if (lowPower) "退出低功耗" else "低功耗展示") }
        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "显示设置") }
        Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "更多操作") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text(if (canDisconnect) "断开 / 取消连接" else "重新连接") },
                    onClick = { menuOpen = false; if (canDisconnect) onDisconnect() else onConnect() })
                DropdownMenuItem(text = { Text("清空弹幕") }, onClick = { menuOpen = false; onClear() })
            }
        }
    }
}

private fun statusText(status: ConnectionStatus) = when (status) {
    ConnectionStatus.Idle -> "未连接"
    ConnectionStatus.Connecting -> "连接中"
    ConnectionStatus.Connected -> "已连接"
    ConnectionStatus.Reconnecting -> "重连中"
    ConnectionStatus.Disconnected -> "已断开"
    ConnectionStatus.Failed -> "连接失败"
}

@Composable
private fun DanmakuMessageItem(item: DanmakuItem, settings: DisplaySettings, wide: Boolean, lowPower: Boolean) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val time = remember(item.timestamp, timeFormat) { timeFormat.format(Date(item.timestamp)) }
    val textColor = if (lowPower) Color(0xFFB8B8B8) else MaterialTheme.colorScheme.onSurface
    val metaColor = if (lowPower) Color(0xFF999999) else MaterialTheme.colorScheme.onSurfaceVariant
    val textStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = settings.textSizeSp.sp, lineHeight = (settings.textSizeSp * 1.4f).sp
    )
    // Wide layouts use a bounded sender column. The body always owns the remaining width and wraps.
    // Compact windows place metadata above the body so long names never squeeze the message to zero.
    if (wide) {
        Row(Modifier.fillMaxWidth().padding(vertical = if (settings.compactRows) 1.dp else 3.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.width(156.dp)) {
                Text(senderLabel(item), style = textStyle.copy(fontWeight = FontWeight.Medium),
                    maxLines = 2, overflow = TextOverflow.Ellipsis, color = metaColor)
                Text(time, style = MaterialTheme.typography.labelSmall, color = metaColor)
            }
            Text(item.content + if (item.repeatCount > 1) "  ×${item.repeatCount}" else "",
                style = textStyle, color = textColor, modifier = Modifier.weight(1f))
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(vertical = if (settings.compactRows) 1.dp else 3.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(senderLabel(item), style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = metaColor)
                Text(time, style = MaterialTheme.typography.labelSmall, color = metaColor)
            }
            Text(item.content + if (item.repeatCount > 1) "  ×${item.repeatCount}" else "",
                style = textStyle, color = textColor)
        }
    }
}

private fun senderLabel(item: DanmakuItem): String = buildString {
    append(item.username)
    item.badgeName?.let { badge ->
        append(" · ")
        append(badge)
        item.badgeLevel?.let { append(" $it") }
    }
}
