# 贡献指南

感谢帮助改进 BiliLive 弹幕监控。先阅读 [README](README.md) 中的构建要求与已知限制；请区分「代码已实现」「本地测试通过」与「真实账号/设备验证通过」。

## 开发与验证

使用 JDK 17、Android SDK 34 和仓库自带的 Gradle 8.5 Wrapper。在提交变更前运行：

```bash
./gradlew --no-daemon --stacktrace :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Windows 请将 `./gradlew` 替换为 `.\gradlew.bat`。如果环境不具备 SDK 或无法下载依赖，请如实记录阻塞原因，不要写成测试通过。

建议按变更范围执行以下人工检查，并记录实际完成的项目：

- Mock 模式连接、断开、重复进入与返回首页。
- 10 / 100 / 300 msg/s 下的列表更新、清屏、手动滚动与回到底部。
- 关键词过滤开关、重复折叠与最大保留数量。
- 深浅色主题及设置重启后保留。
- 真实连接变更：仅使用自己有权使用的账号，检查失败提示、网络切换和主动断开后的行为。

人工清单不是自动化测试，也不表示这些场景已经全部通过。不要为了修复构建静默禁用 Lint、删除失败测试或关闭校验。

## 测试代码

- JVM 单元测试放在 `app/src/test/`；尽量让协议解析、输入校验、二维码编码等纯逻辑不依赖 Android 运行时。
- 协议测试应使用虚构数据，覆盖合法消息、截断包、非法长度和压缩输入的边界情况。
- 连接回归覆盖认证拒绝、认证超时、关闭自动重连、主动断开、切换房间后的迟到回调和心跳应答。使用本地 MockWebServer 验证真正的 OkHttp WebSocket 升级与回调，不依赖 Bilibili 线上房间。
- 登录回归使用合成 Cookie、假 HTTP 和假 SharedPreferences，覆盖取消、刷新、模式切换、注销及旧用户资料迟到；Cookie 路由须覆盖伪造域、HTTPS、路径、过期及账户切换。假存储与 JVM 测试不替代 Android 设备验证。
- 不要在测试或 CI 中调用真实登录接口、发送短信、导入个人 Cookie，或依赖某个真实房间正在直播。
- 二维码生成应保持本地处理；备份规则的静态测试不能代替设备上的备份/恢复验证。
- 显示与横屏检查见 [展示模式指南](docs/DISPLAY_MODE.md)。`assembleDebugAndroidTest` 仅编译测试 APK；实际运行窗口恢复测试需设备/模拟器及 `:app:connectedDebugAndroidTest`，不能将编译成功当作仪器测试通过。
- 涉及连接状态或 UI 的修改，应在说明中列出测试环境和未验证部分。

## Wrapper 的来源与校验

以下文件来自 Gradle 官方仓库的 [v8.5.0 标签](https://github.com/gradle/gradle/tree/v8.5.0)：

- `gradlew`
- `gradlew.bat`
- `gradle/wrapper/gradle-wrapper.jar`

`gradle/wrapper/gradle-wrapper.properties` 使用官方 `https://services.gradle.org/distributions/gradle-8.5-bin.zip`，并固定 `distributionSha256Sum`。

[Gradle 官方发布校验值](https://gradle.org/release-checksums/#v8.5)：

| 文件 | SHA-256 |
| --- | --- |
| Gradle 8.5 Wrapper JAR | `d3b261c2820e9e3d8d639ed084900f11f4a86050a8f83342ade7b6bc9b0d2bdd` |
| Gradle 8.5 binary ZIP | `9d926787066a081739e8200858338b4a69e837c3a821a33aca9db09dd4a41026` |

在 Linux 上可独立核验 Wrapper JAR：

```bash
printf '%s  %s\n' \
  d3b261c2820e9e3d8d639ed084900f11f4a86050a8f83342ade7b6bc9b0d2bdd \
  gradle/wrapper/gradle-wrapper.jar | sha256sum --check
```

macOS 可使用 `shasum -a 256 gradle/wrapper/gradle-wrapper.jar`，PowerShell 可使用 `Get-FileHash gradle/wrapper/gradle-wrapper.jar -Algorithm SHA256` 并与官方值比较。

Wrapper JAR 必须跟随源码提交，不能加入 `.gitignore`。升级时应一并更新脚本、JAR、发行包地址、校验值以及 CI 的校验步骤；在执行新脚本前检查来源和内容。不要通过删除 SHA-256 校验来解决下载问题。发行包校验只验证新下载的 Gradle ZIP，不等于对所有 Maven 依赖都进行了校验。

## CI 范围

`.github/workflows/android.yml` 使用只读仓库权限、固定提交的 checkout/setup-java actions、JDK 17、SDK 34，检查 Wrapper JAR 后执行本地同款检查。它不需要 Bilibili 凭据、发布令牌或签名密钥，不会创建 Release。修改 Action 固定提交时，请核对官方仓库标签与变更记录。

## 问题与 PR 说明

请包含问题现象、复现步骤、期望结果、Android/API 版本、Mock 或真实模式、所用提交，以及已运行的检查。日志只保留定位问题所必需的脱敏内容。

不要提交 Cookie、SESSDATA、验证码、手机号、登录二维码 URL、账号令牌、签名密钥、个人 SDK 路径或真实用户消息数据。若问题涉及这些信息，不要将原始内容贴到公开 Issue 或 PR。

新增的 ZXing Core 3.5.3 依赖采用 [Apache-2.0 许可](https://github.com/zxing/zxing/blob/zxing-3.5.3/LICENSE)；分发 APK 前应按各依赖的许可保留所需的第三方许可文本和通知。这不改变项目自身的许可证声明。

项目自有代码采用 [MIT License](LICENSE)。第三方材料保留其原始许可，见 [第三方许可说明](THIRD_PARTY_NOTICES.md)。添加或升级依赖时，请核对实际版本的许可、版权声明及 NOTICE；不要删除上游归属，也不要将第三方材料标为项目自有的 MIT 代码。
