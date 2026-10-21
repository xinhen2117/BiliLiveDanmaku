package com.bililive.danmaku.ui.display

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bililive.danmaku.domain.model.DisplaySettings
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DisplaySettingsControls(settings: DisplaySettings, onChange: ((DisplaySettings) -> DisplaySettings) -> Unit) {
    val safe = settings.normalized()
    DisplaySwitch("弹幕页保持常亮", "仅前台弹幕页生效，返回或切到后台即恢复；电源键仍可锁屏。",
        safe.keepScreenOn) { enabled -> onChange { it.copy(keepScreenOn = enabled) } }
    DisplaySwitch("默认使用低功耗展示", "纯黑背景、低亮度、无连续滚动动画；也可在弹幕页临时切换。",
        safe.lowPowerMode) { enabled -> onChange { it.copy(lowPowerMode = enabled) } }
    var brightness by remember(safe.brightnessPercent) { mutableStateOf(safe.brightnessPercent.toFloat()) }
    Text("低功耗窗口亮度：${brightness.roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = brightness,
        onValueChange = { brightness = it },
        valueRange = 5f..100f,
        onValueChangeFinished = { val value = brightness.roundToInt(); onChange { it.copy(brightnessPercent = value) } }
    )
    Text("只调整当前弹幕窗口，退出/后台恢复原亮度，不修改系统亮度。纯黑省电主要适用于 OLED；LCD 收益不同。",
        style = MaterialTheme.typography.bodySmall)
    DisplaySwitch("低频移动，降低烧屏风险", "低功耗模式下每分钟移动整个内容区域，控制自动隐藏；不能保证不烧屏。",
        safe.pixelShiftEnabled) { enabled -> onChange { it.copy(pixelShiftEnabled = enabled) } }
    DisplaySwitch("空闲后渐暗（可选）", "无新弹幕时分两步降低亮度；新弹幕或触摸即恢复，不主动熄屏或清空消息。",
        safe.idleDimmingEnabled) { enabled -> onChange { it.copy(idleDimmingEnabled = enabled) } }
    if (safe.idleDimmingEnabled) {
        Text("空闲 ${safe.idleTimeoutSeconds} 秒后渐暗", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(30, 60, 120).forEach { seconds ->
                FilterChip(selected = safe.idleTimeoutSeconds == seconds,
                    onClick = { onChange { it.copy(idleTimeoutSeconds = seconds) } },
                    label = { Text("${seconds}秒") })
            }
        }
    }
    var textSize by remember(safe.textSizeSp) { mutableStateOf(safe.textSizeSp.toFloat()) }
    Text("弹幕字号：${textSize.roundToInt()} sp（跟随系统字体缩放）", style = MaterialTheme.typography.bodyMedium)
    Slider(value = textSize, onValueChange = { textSize = it }, valueRange = 14f..24f, steps = 9,
        onValueChangeFinished = { val value = textSize.roundToInt(); onChange { it.copy(textSizeSp = value) } })
    DisplaySwitch("紧凑行距", "减少留白，不压小字号；长弹幕仍完整换行，横竖屏都保持时间顺序。",
        safe.compactRows) { enabled -> onChange { it.copy(compactRows = enabled) } }
    Text("常亮仍会耗电。建议选择能看清的低亮度并适时休息；后台没有保活保证，超出缓存上限的旧消息会被淘汰。",
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun DisplaySwitch(title: String, explanation: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(explanation, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
