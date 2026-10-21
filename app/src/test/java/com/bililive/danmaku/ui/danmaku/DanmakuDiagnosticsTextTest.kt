package com.bililive.danmaku.ui.danmaku

import com.bililive.danmaku.domain.model.PresentationDiagnostics
import com.bililive.danmaku.domain.model.TransportDiagnostics
import org.junit.Assert.*
import org.junit.Test

class DanmakuDiagnosticsTextTest {
    @Test fun `raw dropped frames never become an invented message count`() {
        val text = transportDiagnosticsText(TransportDiagnostics(droppedMessages = 3, droppedFrames = 7, oversizedFrames = 2))
        assertTrue(text.contains("接收层丢弃 3 条"))
        assertTrue(text.contains("未解析丢弃 7 帧"))
        assertTrue(text.contains("超大帧 2"))
        assertTrue(text.contains("帧内弹幕条数未知"))
    }

    @Test fun `display reductions keep arrival and history row units separate`() {
        val text = presentationDiagnosticsText(PresentationDiagnostics(filteredMessages = 2, foldedMessages = 3,
            oversizedMessages = 4, historyCountEvictedRows = 5, historyTextEvictedRows = 6,
            filterPrunedRows = 7, replacedIdRows = 8))
        assertTrue(text.contains("过滤 2 条"))
        assertTrue(text.contains("折叠 3 条"))
        assertTrue(text.contains("超限拒绝 4 条"))
        assertTrue(text.contains("数量淘汰 5 行"))
        assertTrue(text.contains("文本淘汰 6 行"))
        assertTrue(text.contains("过滤移除 7 行"))
        assertTrue(text.contains("ID替换 8 行"))
    }
}
