package com.bililive.danmaku.ui.danmaku

import com.bililive.danmaku.domain.model.PresentationDiagnostics
import com.bililive.danmaku.domain.model.TransportDiagnostics

/** Keep units explicit: a discarded undecoded frame can contain an unknown number of messages. */
internal fun transportDiagnosticsText(diagnostics: TransportDiagnostics): String = buildString {
    append("接收层丢弃 ${diagnostics.droppedMessages} 条；未解析丢弃 ${diagnostics.droppedFrames} 帧")
    if (diagnostics.oversizedFrames > 0) append("（含超大帧 ${diagnostics.oversizedFrames}）")
    append("；丢弃帧内弹幕条数未知")
}

internal fun presentationDiagnosticsText(diagnostics: PresentationDiagnostics): String = buildList {
    if (diagnostics.filteredMessages > 0) add("过滤 ${diagnostics.filteredMessages} 条")
    if (diagnostics.foldedMessages > 0) add("折叠 ${diagnostics.foldedMessages} 条")
    if (diagnostics.oversizedMessages > 0) add("超限拒绝 ${diagnostics.oversizedMessages} 条")
    if (diagnostics.historyCountEvictedRows > 0) add("历史数量淘汰 ${diagnostics.historyCountEvictedRows} 行")
    if (diagnostics.historyTextEvictedRows > 0) add("历史文本淘汰 ${diagnostics.historyTextEvictedRows} 行")
    if (diagnostics.filterPrunedRows > 0) add("设置过滤移除 ${diagnostics.filterPrunedRows} 行")
    if (diagnostics.replacedIdRows > 0) add("重用ID替换 ${diagnostics.replacedIdRows} 行")
}.joinToString(" · ", prefix = "显示层：")
