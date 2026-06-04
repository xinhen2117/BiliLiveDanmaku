# BiliLive 弹幕监控 (BiliLive Danmaku Monitor)

一个 Android 应用，用于实时监看 Bilibili 直播间弹幕。

## 📦 下载安装

- **Debug APK** (17MB): `BiliLiveDanmaku-debug.apk` — 可直接安装
- **Release APK** (1.7MB): `BiliLiveDanmaku-release.apk` — 需要签名后安装

## 🚀 快速开始

1. 安装 `BiliLiveDanmaku-debug.apk`
2. 打开应用，默认处于 **Mock 模式**（无需后端）
3. 输入任意直播间号（如 `21484828`）
4. 点击「连接」进入弹幕页
5. 即可看到模拟弹幕实时滚动

## 🏗 架构

```
MVVM + Clean Architecture

UI Layer (Jetpack Compose)
  ├── RoomInputScreen     — 首页：输入房间号
  ├── DanmakuScreen       — 弹幕展示页
  └── SettingsScreen      — 设置页

Domain Layer
  ├── DanmakuItem         — 弹幕数据模型
  ├── ConnectionStatus    — 连接状态枚举
  ├── DanmakuRepository   — 仓库接口
  ├── ConnectRoomUseCase  — 连接房间
  ├── DisconnectUseCase   — 断开连接
  └── ValidateRoomIdUseCase — 房间号校验

Data Layer
  ├── DanmakuWebSocketClient — OkHttp WebSocket 客户端
  ├── MockDanmakuDataSource  — Mock 模拟数据源
  ├── DanmakuRepositoryImpl  — 仓库实现
  └── SettingsDataStore      — DataStore 本地设置
```

## 🔧 技术栈

- **Kotlin** + **Jetpack Compose**
- **MVVM** / Clean Architecture
- **OkHttp** WebSocket
- **kotlinx.serialization** JSON 解析
- **DataStore** 本地设置存储
- **Kotlin Coroutines** + **Flow**
- **Material 3** 动态主题

## ⚙️ 配置项

在设置页可以配置：

| 设置项 | 默认值 | 说明 |
|--------|--------|------|
| Mock 模式 | 开启 | 使用模拟数据，无需后端 |
| Mock 速率 | 10 msg/s | 可选 10/100/300 |
| Gateway 地址 | ws://10.0.2.2:8080/ws/danmaku | 真实后端地址 |
| 自动重连 | 开启 | 断线后自动重连 |
| 关键词过滤 | 关闭 | 屏蔽指定关键词弹幕 |
| 重复折叠 | 开启 | 3秒内相同内容折叠 |
| 最大弹幕数 | 1000 | 超出后丢弃旧消息 |
| 暗色模式 | 跟随系统 | 自动切换深色主题 |

## 🔗 Gateway 协议

Android 客户端连接自有 Gateway，协议格式：

```json
// 弹幕消息
{"type":"danmaku","roomId":123456,"msgId":"id","uid":10001,"username":"昵称","content":"内容","timestamp":1760000000000,"badge":{"name":"粉丝","level":10}}

// 状态消息
{"type":"status","roomId":123456,"status":"connected","message":"connected"}

// 错误消息
{"type":"error","roomId":123456,"code":"ROOM_NOT_FOUND","message":"未找到该直播间"}

// 心跳
{"type":"ping","timestamp":1760000000000}
→ {"type":"pong","timestamp":1760000000000}
```

## 🧪 测试

```bash
# 单元测试
./gradlew test

# 构建 Debug APK
./gradlew assembleDebug

# 构建 Release APK
./gradlew assembleRelease
```

## 📋 后续 TODO

- [ ] 实现真实 Bilibili Gateway 后端服务
- [ ] 支持礼物、SC、进场消息
- [ ] 弹幕搜索功能
- [ ] 弹幕统计图表
- [ ] 房间收藏夹
- [ ] 多房间同时监控
- [ ] 导出弹幕记录
- [ ] 正则过滤 UI
- [ ] Widget 桌面小组件

## 📄 License

MIT
