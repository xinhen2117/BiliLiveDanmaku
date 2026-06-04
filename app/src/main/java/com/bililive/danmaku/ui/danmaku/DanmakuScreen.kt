package com.bililive.danmaku.ui.danmaku

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bililive.danmaku.domain.model.ConnectionStatus
import com.bililive.danmaku.domain.model.DanmakuItem
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DanmakuScreen(
    onBack: () -> Unit,
    viewModel: DanmakuViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Auto-scroll effect
    LaunchedEffect(uiState.messages.size, uiState.autoScroll) {
        if (uiState.autoScroll && uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    // Detect user scroll
    val isScrolledUp by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            totalItems > 0 && lastVisible < totalItems - 3
        }
    }

    LaunchedEffect(isScrolledUp) {
        if (isScrolledUp && uiState.autoScroll) {
            viewModel.toggleAutoScroll()
        }
    }

    // Error snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.dismissError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "房间 ${uiState.roomId ?: ""}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        StatusChip(uiState.status)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // Disconnect/Reconnect
                    if (uiState.status == ConnectionStatus.Connected) {
                        IconButton(onClick = viewModel::disconnect) {
                            Icon(Icons.Default.LinkOff, contentDescription = "断开")
                        }
                    } else if (uiState.status in listOf(
                            ConnectionStatus.Disconnected, ConnectionStatus.Failed, ConnectionStatus.Idle
                        )
                    ) {
                        IconButton(onClick = viewModel::connect) {
                            Icon(Icons.Default.Link, contentDescription = "连接")
                        }
                    }
                    // Clear screen
                    IconButton(onClick = viewModel::clearScreen) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "清屏")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Stats bar
                StatsBar(
                    totalCount = uiState.totalCount,
                    msgPerSec = uiState.messagesPerSecond,
                    highSpeedMode = uiState.highSpeedMode
                )

                // Danmaku list
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(
                        count = uiState.messages.size,
                        key = { index -> uiState.messages[index].id }
                    ) { index ->
                        DanmakuMessageItem(item = uiState.messages[index])
                    }
                }
            }

            // New messages floating button
            AnimatedVisibility(
                visible = !uiState.autoScroll && uiState.unreadCount > 0,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
            ) {
                FilledTonalButton(onClick = viewModel::scrollToBottom) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("有 ${uiState.unreadCount} 条新弹幕")
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: ConnectionStatus) {
    val (color, text) = when (status) {
        ConnectionStatus.Idle -> MaterialTheme.colorScheme.onSurfaceVariant to "未连接"
        ConnectionStatus.Connecting -> MaterialTheme.colorScheme.tertiary to "连接中..."
        ConnectionStatus.Connected -> MaterialTheme.colorScheme.primary to "已连接"
        ConnectionStatus.Reconnecting -> MaterialTheme.colorScheme.error to "重连中..."
        ConnectionStatus.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant to "已断开"
        ConnectionStatus.Failed -> MaterialTheme.colorScheme.error to "连接失败"
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatsBar(
    totalCount: Long,
    msgPerSec: Int,
    highSpeedMode: Boolean
) {
    Surface(
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "总数: $totalCount",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (highSpeedMode) {
                    Text(
                        "⚡高速模式",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    "$msgPerSec msg/s",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (msgPerSec > 100) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DanmakuMessageItem(item: DanmakuItem) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Time
        Text(
            text = timeFormat.format(Date(item.timestamp)),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.width(56.dp)
        )

        // Badge
        if (item.badgeName != null) {
            Surface(
                shape = RoundedCornerShape(3.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.padding(end = 4.dp)
            ) {
                Text(
                    text = "${item.badgeName}${item.badgeLevel?.let { " $it" } ?: ""}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                )
            }
        }

        // Username
        Text(
            text = "${item.username}: ",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp
            ),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1
        )

        // Content
        Text(
            text = item.content,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        // Repeat count
        if (item.repeatCount > 1) {
            Text(
                text = " x${item.repeatCount}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
