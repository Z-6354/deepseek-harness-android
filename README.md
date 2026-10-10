<p align="center">
  <img src="docs/images/banner.jpg" alt="DSHA" width="100%">
</p>

<h1 align="center">DSHA</h1>

<p align="center"><em>DeepSeek Harness App</em></p>

<p align="center">
  在 Android 上打开你自己的 DeepSeek Harness 网站。<br>
  聊天、审批、工作区由服务器上的官方网页负责；本应用只提供隔离的 HTTPS WebView 与系统能力。
</p>

<p align="center">
  <a href="https://dsh.wannian.fun/"><img alt="Site" src="https://img.shields.io/badge/site-dsh.wannian.fun-4176E6?style=flat-square"></a>
  <a href="https://github.com/Z-6354/deepseek-harness-android"><img alt="GitHub" src="https://img.shields.io/badge/github-Z--6354%2Fdeepseek-harness-android-181717?style=flat-square"></a>
  <img alt="Android 9.0+" src="https://img.shields.io/badge/Android-9.0%2B-3DDC84?style=flat-square">
  <a href="LICENSE"><img alt="MIT" src="https://img.shields.io/badge/license-MIT-blue?style=flat-square"></a>
</p>

这是 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 的**非官方** Android 容器，由本仓库二次开发维护，不是原生重写的聊天客户端。

当前 APK 只编译 `app/src/shell`：加载 HTTPS 站点、处理文件选择 / 下载 / 通知与私有文件桥。登录与业务协议在网页和服务器上的自制插件里完成。

生产站点：[dsh.wannian.fun](https://dsh.wannian.fun/)。源码：[Z-6354/deepseek-harness-android](https://github.com/Z-6354/deepseek-harness-android)。**以本文件与 `docs/` 契约为准。**

---

## 它做什么

| 层 | 组件 | 职责 |
|---|---|---|
| Android | 本仓 `app/src/shell` | HTTPS WebView 壳、站点配置、系统能力、启动罩与静态资源缓存 |
| 认证 | `plugins/dsh-local-hanaccount` | 操作员密码、`password-native-v1`、门禁 Cookie、RPC 授权 |
| 手机 UI | `plugins/dsh-mobile-hanui` | 布局适配、启动恢复、平台请求、历史图片 Adapter |
| 会话缓存 | `plugins/dsh-session-cache-sync` | 历史持久化与差量同步（可选） |
| 业务 | 官方 Harness 网页 | 会话、工具、工作区、审批 |

Gradle 只构建 `:app`。三个插件是独立仓库检出，见 [`plugins/README.md`](plugins/README.md)。

---

## 当前行为

- 默认打开 `https://dsh.wannian.fun/`；已保存的其它 HTTPS 站点会继续使用。
- 在网页输入操作员密码登录（需服务器安装兼容的 `dsh-local-hanaccount`）。
- 可选 `HanApp` 桥：仅当前精确 origin 的主框架可请求通知、本机加密密码与切站确认。
- 同 origin HTTPS GET 下载（上限 25 MiB）；系统文件选择器上传；禁止混合内容与 `addJavascriptInterface`。
- 本地退出清除浏览数据、Keystore 已存密码，以及静态资源磁盘缓存。
- 加载罩显示当前版本号；启动后自动检查更新（优先 `https://dsh.wannian.fun/dsha/update/latest.json`），有新版本时弹出可关闭的系统对话框，确认后下载安装。

原生设置界面尚未接线；切站依赖网页桥 `changeWebsite`，或后续补上的设置项。

---

## 环境要求

- Android 9.0+（minSdk 28）；JDK 17；Android SDK 35。
- 系统 WebView 支持 document-start 脚本与完整浏览数据删除。
- 服务器提供**系统 CA 信任**的 HTTPS（不信任用户 CA；不允许 HTTP 登录）。
- 服务器安装匹配版本的自制插件；不能只看 Harness 版本号判断能否登录。

部署与服务器：[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) · HTTPS 要点：[harness/README.md](harness/README.md) · 安全：[docs/SECURITY.md](docs/SECURITY.md)

---

## 快速开始

1. 按 [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) 在生产服务器上部署插件与 Nginx，站点为 `https://dsh.wannian.fun/`。
2. 构建并安装本仓 APK（见下）。首次启动进入默认站点，在登录页输入密码。
3. 会话、模型、工作区都在网页里操作。

---

## 构建

```sh
./gradlew :app:testDebugUnitTest   # 单元测试
./gradlew :app:lintDebug           # Lint
./gradlew :app:assembleDebug       # 调试 APK → app/build/outputs/apk/debug/
./gradlew :app:assembleRelease     # 发布 APK（配置了 keystore 环境变量时签名）
```

版本号来自发布工作流的 `DSH_VERSION_NAME`（git 标签去掉前缀 `v`）；本地未设置时回退到 `app/build.gradle.kts` 中的字面量。开发约定见 [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md)。

发布到手机更新通道：

```powershell
.\scripts\publish-app-update.ps1 -ApkPath app\build\outputs\apk\release\app-release.apk -Version 0.16.0
```

---

## 仓库结构

```
.
├── app/                 # 唯一 Gradle 模块：WebView 壳
│   └── src/shell/       # 打进 APK 的源码（main 只放 Manifest/资源）
├── gradle/              # Wrapper + 版本目录（libs.versions.toml）
├── plugins/             # 三个自制插件检出（独立 git，不进 App 图）
├── docs/                # 契约、计划、贡献指南、变更记录
├── harness/             # HTTPS 部署说明
├── brand/               # 启动图标源 SVG
├── scripts/             # 本地测量 / 设备 / 发布脚本
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
├── README.md
└── LICENSE
```

| 路径 | 说明 |
|---|---|
| [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) | 服务器、SSH、App 更新通道 |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | 壳层组件与启动边界 |
| [`docs/PROTOCOL.md`](docs/PROTOCOL.md) | 桥与私有文件协议 |
| [`docs/COMPATIBILITY.md`](docs/COMPATIBILITY.md) | WebView / 能力降级 |
| [`docs/plans/`](docs/plans/) | 当前阶段工作指示 |
| [`docs/archive/`](docs/archive/) | 已结束波次（非现行契约） |

---

## 许可证

[MIT](LICENSE)。第三方材料见 [docs/THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md)。DeepSeek Harness 及其品牌归各自所有者；本项目是独立的非官方远程容器。
