package com.bililive.danmaku.data.remote

import com.bililive.danmaku.domain.model.ConnectionStatus
import com.bililive.danmaku.domain.model.DanmakuItem
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicLong

class MockDanmakuDataSource {
    private var mockJob: Job? = null
    private val _danmaku = Channel<DanmakuItem>(Channel.BUFFERED)
    val danmaku: Flow<DanmakuItem> = _danmaku.receiveAsFlow()

    private val _status = MutableStateFlow(ConnectionStatus.Idle)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val msgCounter = AtomicLong(0)

    private val mockUsernames = listOf(
        "小明同学", "弹幕达人", "直播爱好者", "路过的大佬", "萌新报到",
        "摸鱼冠军", "熬夜选手", "快乐肥宅", "技术宅", "吃瓜群众",
        "up主粉丝", "潜水员", "课代表", "前排围观", "彩虹屁制造机"
    )

    private val mockContents = listOf(
        "666", "好厉害", "主播加油", "哈哈哈", "太强了",
        "awsl", "来了来了", "冲冲冲", "yyds", "牛逼",
        "好好看", "下次还来", "主播辛苦了", "可以的", "厉害了",
        "前排", "打卡", "蹲一个", "学到了", "太秀了",
        "啊啊啊啊", "笑死", "好可爱", "绝了", "无敌",
        "我来了", "大家好", "签到", "支持", "继续"
    )

    fun connect(roomId: Long, rate: Int, scope: CoroutineScope) {
        disconnect()
        msgCounter.set(0)
        _status.value = ConnectionStatus.Connecting

        mockJob = scope.launch {
            delay(500) // Simulate connection time
            _status.value = ConnectionStatus.Connected

            val intervalMs = when {
                rate <= 10 -> 1000L / rate
                rate <= 100 -> 1000L / rate
                else -> 1000L / rate
            }

            while (isActive) {
                val item = generateDanmaku(roomId)
                _danmaku.trySend(item)
                delay(intervalMs)
            }
        }
    }

    private fun generateDanmaku(roomId: Long): DanmakuItem {
        val count = msgCounter.incrementAndGet()
        return DanmakuItem(
            id = "mock_${roomId}_${count}_${System.nanoTime()}",
            roomId = roomId,
            uid = 10000L + (count % 100L),
            username = mockUsernames[(count % mockUsernames.size.toLong()).toInt()],
            content = mockContents[(count % mockContents.size.toLong()).toInt()],
            timestamp = System.currentTimeMillis(),
            badgeName = if (count % 5L == 0L) "粉丝" else null,
            badgeLevel = if (count % 5L == 0L) (count % 20L + 1L).toInt() else null
        )
    }

    fun disconnect() {
        mockJob?.cancel()
        mockJob = null
        _status.value = ConnectionStatus.Disconnected
    }

    fun isConnected(): Boolean = _status.value == ConnectionStatus.Connected
}
