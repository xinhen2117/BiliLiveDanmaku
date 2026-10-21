# BiliLive 弹幕监控

使用 Kotlin 和 Jetpack Compose 编写的 Android 直播弹幕客户端，最低支持 Android 8.0（API 26）。

项目处于开发阶段。默认使用本地 Mock 数据，也包含直接连接 Bilibili 的实现；**不需要自建 Gateway，当前代码没有 Gateway 地址设置**。真实连接依赖 Bilibili 接口、账号状态和服务端策略，不能保证持续可用或收齐所有弹幕。

## 当前功能

以下描述对应仓库内的实现，不代表已经完成真机或线上服务验证：

- 输入正整数直播间号，保存最近使用的房间。
- Mock 模式：默认开启，提供 10 / 100 / 300 条每秒的模拟消息速率。
- 直连模式：解析房间号、获取弹幕服务器配置，通过 WebSocket 接收二进制弹幕；只有服务端明确认证成功才显示已连接，支持心跳、压缩包解析与可关闭的自动重连。
- 弹幕列表：显示昵称、内容、粉丝牌、连接状态、消息计数和速率；提供清屏、断开/重新连接与回到底部操作。
- 展示模式：前台弹幕页常亮、可关闭；低功耗模式使用窗口级低亮度、纯黑背景、按需合批与低频位置移动，可选空闲渐暗。离开、后台或设置弹窗恢复窗口覆盖，不改变系统全局亮度。
- 横屏 / 分屏：根据窗口尺寸调整工具栏与发送人/正文布局，长消息完整换行；支持 14–24sp 字号与紧凑行距。使用说明、风险边界及真机检查清单见 [展示模式指南](docs/DISPLAY_MODE.md)。
- 设置：关键词过滤、重复弹幕折叠、保留数量（300 / 500 / 1000）、深浅色主题；使用 DataStore 保存。
- 登录页包含互斥的扫码、短信和 Cookie 导入入口；扫码图片由设备本地生成。刷新、切换方式、退出或注销会取消相关尝试，迟到结果不能重新写入旧账号。这些流程仍需真实账号/设备验证。

仓库没有随源码附带可安装 APK。本文不提供未经验证的下载包、文件大小或发布承诺；可按下面步骤自行构建 Debug APK。

## 从源码构建

### 环境

- JDK 17，确保命令行 `JAVA_HOME` 和 Android Studio 的 Gradle JDK 指向它。
- Android SDK Platform 34、Build Tools 34.0.0；安装时自行审阅并接受适用的 SDK 条款。
- Gradle 8.5，通过仓库内的完整 Gradle Wrapper 使用，无需单独安装 Gradle。
- 首次构建需要联网下载 Gradle、Android Gradle Plugin 和 Maven 依赖。

构建配置保留 Android Gradle Plugin 8.2.2、Kotlin 1.9.22、compileSdk / targetSdk 34。环境要求可对照 [Android Gradle Plugin 8.2 官方兼容性说明](https://developer.android.com/build/releases/agp-8-2-0-release-notes)。

### 命令行

1. 克隆仓库并进入项目根目录：

   ```bash
   git clone https://github.com/xinhen2117/BiliLiveDanmaku.git
   cd BiliLiveDanmaku
   ```

2. 通过 Android Studio 的 SDK Manager 安装上述 SDK 组件，并设置 `ANDROID_HOME`；也可以在项目根目录创建未跟踪的 `local.properties`：

   ```properties
   sdk.dir=/absolute/path/to/your/Android/sdk
   ```

   请换成自己的 SDK 绝对路径，不要把 `local.properties` 提交到仓库。Android Studio 通常会自动生成它。

3. 运行检查与构建：

   ```bash
   ./gradlew --version
   ./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
   ```

   Windows PowerShell 使用：

   ```powershell
   .\gradlew.bat --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
   ```

成功构建后的 Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，可安装到 Android 8.0 及以上的测试设备。Debug 签名仅用于开发，不等于正式发布签名。项目当前未配置正式发布签名，不应将未签名 Release 产物当作可安装发行版。

Wrapper 使用 Gradle 官方下载地址，并配置了发行包 SHA-256 校验；JAR 校验方法见 [CONTRIBUTING.md](CONTRIBUTING.md)。固定工具版本改善构建一致性，但不表示 APK 已达到逐字节可复现。

### Android Studio

打开项目根目录，选择 JDK 17 并完成 Gradle Sync，选择 `app` 配置和 API 26 及以上设备后运行。不要使用 IDE 自动升级来替代本项目明确固定的工具版本。

## 试用流程

### 本地 Mock 演示

1. 首次启动保持 Mock 模式开启。
2. 输入任意有效的正整数房间号，例如 `21484828`。
3. 点击「连接」查看模拟弹幕。Mock 模式不连接 Bilibili，不需要登录。
4. 在设置页调整速率后重新连接；可测试关键词过滤、清屏与保留数量。

Mock 只用于演示和本地测试，不证明真实房间连接或高负载性能已经验证。

### 真实房间（实验性）

在设置中关闭 Mock 模式，返回首页输入有效房间号并连接。设置变更后请断开并重新连接。若失败，先确认房间号、网络和账号状态；查看错误提示及经过脱敏的日志，不要反复高频尝试登录或请求接口。

当前实现直接使用 Bilibili 的 HTTP / WebSocket 接口，而不是 README 旧版描述的自建 JSON Gateway。

## 已知限制与安全注意事项

- 真实连接、扫码及短信登录未在本文中声称验证通过。短信流程尚无完整的交互式人机验证处理；服务端要求验证时可能失败。
- 扫码图片使用 ZXing 在本地内存中生成，不再把登录二维码 URL 发给第三方图片服务；获取和轮询登录二维码仍需访问 Bilibili 接口。不要公开登录二维码或其链接。
- 登录 Cookie 仍保存在应用私有的普通 SharedPreferences 中，未使用加密存储。备份规则只允许设置文件，并显式排除登录凭据，覆盖旧版自动备份及 Android 12+ 云备份/设备迁移；这些规则尚需真机恢复验证，不能等同于全面账号安全保障。
- 不要在 Issue、PR、截图或日志中公开 Cookie、SESSDATA、验证码、手机号或二维码登录链接。登录状态提示也不能证明会话仍然有效。
- 「自动重连」设置在开始连接时读取，修改后请断开并重新连接。关闭时故障后停在失败状态；开启时网络故障会退避重试，但明确的认证拒绝不会无限重试。主动断开会取消在途发现请求、WebSocket 与计划中的重连。
- HTTP 与 WebSocket 使用同一 CookieJar。登录 Cookie 只发送给 HTTPS 的 `bilibili.com` 及其真实子域；路径、Secure 和过期限制仍适用，不向近似域名或第三方发送会话。
- 常亮仍然耗电；纯黑的节能效果主要针对 OLED。低亮度与低频移动只能降低长期静态显示风险，不能保证不烧屏。尚未完成真机功耗、窗口生命周期与横屏视觉验证。
- 当前仅面向普通弹幕列表，不包含礼物、SC、进场消息、多房间监控、搜索、记录导出或后台常驻服务。
- 协议解析设有资源上限：整帧 4 MiB、单帧累计解压 8 MiB、压缩嵌套 4 层、单个 JSON 消息 64 KiB / 嵌套 64 层；超限的帧或消息会被丢弃。
- WebSocket 待处理二进制数据另限 8 MiB，超出时中止当前连接并按重连设置处理，避免只限制消息条数却堆积大量大帧。
- 消息缓冲和显示数量也有上限，拥塞或高负载时可能丢弃消息；这不是用于完整归档的采集器。

## 代码结构

```text
app/src/main/java/com/bililive/danmaku/
├── data/
│   ├── local/       DataStore 设置与最近房间
│   ├── remote/      Bilibili HTTP、登录、Cookie、WebSocket、协议与 Mock 数据源
│   └── repository/  数据源选择与 Flow 对外接口
├── domain/          数据模型、仓库接口、房间号校验与连接用例
└── ui/              Compose 首页、弹幕、登录、设置与主题

app/src/test/         JVM 单元测试
app/src/androidTest/  需要设备 / 模拟器的窗口恢复测试
.github/workflows/    Android 检查工作流
```

主要依赖：Jetpack Compose / Material 3、Coroutines / Flow、OkHttp、kotlinx.serialization、DataStore、Brotli 解压库，以及用于本地二维码生成的 ZXing Core 3.5.3。

## 测试与贡献

```bash
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:lintDebug
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest
```

- 单元测试报告：`app/build/reports/tests/testDebugUnitTest/index.html`
- Lint 报告：`app/build/reports/lint-results-debug.html`
- [Android 检查工作流](.github/workflows/android.yml)在推送到 `master`、Pull Request 和手动触发时执行单元测试、Lint、Debug 构建与仪器测试 APK 编译（编译不等于运行仪器测试）。添加工作流不等于检查已经通过，应以相应提交的实际运行结果为准。
- CI 不登录 Bilibili、不验证线上接口、不进行真机测试，也不发布或签名正式版本。

问题反馈、验证范围和 Wrapper 维护说明见 [贡献指南](CONTRIBUTING.md)。

## License

MIT

原 README 声明为 MIT，但仓库目前缺少完整的 `LICENSE` 文件。许可证正文及版权信息仍需项目维护者确认和补齐，本次文档整理不改变原有许可声明。
