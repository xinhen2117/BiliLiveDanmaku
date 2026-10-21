# 测试 APK 下载、安装与更新

这是开发测试包，不是稳定发行版。构建、单元测试和 Lint 通过，不代表真实 Bilibili 登录/收弹幕、真机横屏、耗电或备份恢复已验证。首次试用请使用默认 Mock 模式；不要在测试包中输入重要账号或敏感凭据。

## 两种下载入口

1. [Releases](https://github.com/xinhen2117/BiliLiveDanmaku/releases)：选择标记为 **Pre-release** 且实际包含 `BiliLiveDanmaku-test.apk` 的条目。旁边应有 `SHA256SUMS` 和 `BUILD-INFO.txt`。自动生成的 Source code ZIP/TAR 不是 APK；没有 APK 附件的条目不能直接安装。
2. [Android checks](https://github.com/xinhen2117/BiliLiveDanmaku/actions/workflows/android.yml)：仅对已成功且实际上传产物的运行，在页面底部 Artifacts 下载 `BiliLiveDanmaku-test-<构建提交>-<重试次数>`，解压后取出上述三个文件。GitHub 要求登录后下载 artifact；保留期为 7 天，过期后该链接不能继续下载。不要使用其他作者的 fork/PR 产物冒充本项目发布包。

参考：[GitHub artifact 下载说明](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/download-workflow-artifacts)。发布或保存 artifact 是独立步骤，不能因为构建成功就假定下载包存在。

## 安装前核对

- 系统要求：Android 8.0（API 26）及以上。
- 包名：`com.bililive.danmaku.debug`；桌面名称：`BiliLive 弹幕监控（测试版）`；当前版本名：`1.0.0-mvp-debug`。
- 查看 `BUILD-INFO.txt` 中的实际构建提交、CI 运行、包名、版本、APK SHA-256 与签名证书 SHA-256。PR 构建可能对应 GitHub 的临时合并提交，应以该文件记录的实际构建提交为准。
- 将 APK 的 SHA-256 与 `SHA256SUMS` 核对。例如在三个文件所在目录运行：

  ```bash
  sha256sum --check SHA256SUMS
  # macOS:
  shasum -a 256 --check SHA256SUMS
  ```

  Windows PowerShell 可用 `Get-FileHash .\BiliLiveDanmaku-test.apk -Algorithm SHA256`，再与 `SHA256SUMS` 对比。

校验值只能证明文件与给出的校验记录一致，不能替代对项目来源的信任或安全审计。仅安装你确认来源的包；系统安全提示不能作为应当忽略或关闭保护的理由。本项目不会远程更改设备安装权限或安装应用。

## 临时签名与更新限制

测试 APK 使用 Android 构建工具的临时默认 Debug 证书，开启调试，并非正式安全发布签名。CI 不保存或上传该密钥，不新建正式发布密钥，也没有保证不同 CI 运行的证书一致。

- Android 应用更新要求匹配的应用身份/签名。不同运行或自行构建的测试包可能不能覆盖安装已有测试包；不要仅凭版本名相同判断兼容。
- 如果你选择卸载后重装，**会清除该测试包的本地设置与登录信息**。不要为了安装测试版而卸载需要保留数据的正式版本。
- 新 `.debug` 包与 `com.bililive.danmaku` 正式包相互独立，不自动迁移数据。以前从旧源码自行构建的无后缀 Debug 包也不会被新测试包覆盖。
- 正式 Release variant 没有配置发布签名；本次测试版不意味着已有可长期升级的正式发行渠道。

参考：[Android 应用签名与升级说明](https://developer.android.com/studio/publish/app-signing)。

## 验证了什么

CI 在所有检查通过后，使用 Android SDK 的 `aapt` 检查独立包名、版本、最低系统与 Debug 标记，使用 `apksigner verify --print-certs` 验证 APK 签名并记录证书指纹。它还逐字节检查许可/来源 assets 随 APK 保留，再生成 APK SHA-256。上传严格限制为 APK、校验文件、构建说明三个文件，不上传源码工作区、登录数据或任何 keystore。

准备脚本的纯 Python 单元测试可以单独运行：

```bash
python3 -m unittest discover -s scripts -p 'test_*.py'
```

这些测试使用合成输入，不把模拟 APK 当作真实 Android 产物验证。真实签名/打包检查以对应 CI 运行结果为准。仪器测试 APK 的编译不等于执行仪器测试，功耗和防烧屏也没有真机保证。

### 本次限定预发布

仅本仓库 `improve/test-apk-downloads-20261021` 分支的 push 可在全部检查通过后发布固定测试标签 `test-20261021.1`；PR、master 和其他分支不进入发布 job。发布使用临时 GitHub Actions token，不保存正式签名密钥。先创建 draft，核对三个已上传附件的字节后才公开为 prerelease，不作为最新稳定版。

已有标签指向不同提交，或同名附件的字节不同，流程会失败而不是移动标签/覆盖附件。由于重新构建可能生成不同 Debug 签名，同一提交也不保证相同 APK 字节。需要恢复中断发布时，应只重跑失败的发布 job，复用该运行原始 artifact；如果原产物已过期或不一致，应停止并重新审查，不能删除旧附件后强行覆盖。这不是通用或持续自动发布渠道。

## 如何反馈

反馈时附上构建提交、版本、Android 版本、机型、Mock/真实连接模式和可复现步骤。截图或日志先去除手机号、Cookie、SESSDATA、验证码、登录二维码及其链接。展示模式的设备检查清单见 [DISPLAY_MODE.md](DISPLAY_MODE.md)。
