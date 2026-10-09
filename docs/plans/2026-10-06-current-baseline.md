# 当前工作基准（2026-10-06）

本文取代本目录中更早的审核稿、ReplyProxy 方案叙述里与下文冲突的指示，以及「先完整取证再改代码」等阶段性门禁。产品契约仍以 `docs/ARCHITECTURE.md`、`PROTOCOL.md`、`SECURITY.md`、`THREE-MODULE-INTEGRATION.md` 为准。

## 边界

- **不修改** DeepSeek Harness 官方源码。
- **只改** Android 壳（`app/src/shell/`）、`dsh-local-hanaccount`、`dsh-mobile-hanui`。
- 需要新能力时：**新增自制插件**（或在现有自制插件内扩展），不要补丁官方代码。
- 官方网页继续拥有会话、聊天和业务连接。
- 每次修改时需要明确目标，是修改插件/APP亦或者新增插件。
## 本阶段核心目标

在基础功能可用的前提下 **缩短加载时间**（冷启可交互、减少无意义整页刷新）。安全门禁、精确 origin、禁止 `addJavascriptInterface`、出站不碰 ReplyProxy JNI 不为此放宽。

## 冷热启动定义（以本文件为准）

按用户日常用法，**不以**「Activity 是否仍在栈顶」单独命名；以 **进程是否仍在内存** 为准。正常使用下应用应像常见 App 一样留在内存，不主动退进程。

| 名称 | 含义 | 典型操作 | 验收 |
|---|---|---|---|
| **热启动** | 安装并打开过后，**应用进程仍在内存**；从桌面/其它 App 再回来，复用已有进程与页面 | 退到系统主页 → 打开其它 App → 再回主页点开本 App | 原页面尽快恢复可交互；不整页重载、不重演启动罩。目标 P95 < 1s |
| **冷启动** | **进程不在内存** 后再开 | 重启手机；或在多任务里划掉/强制停止本应用后再开 | 到当前认证状态对应页面真正可交互。目标 P95 < 5s（有缓存的日常冷启）；首次安装/新资源版本单独记账 |

对照说明：

- 热启动对应系统 `LaunchState: HOT` / 进程未死时的回前台；`onResume` 不得 `loadUrl`、不得重建 WebView。
- 冷启动对应进程新建后的 `createBrowser` + 文档加载 + `pageReady`；缩短冷启是本阶段明确目标，不能把「划掉再开」误报成热启失败。
- 取证脚本：`artifacts/hotstart-diag/measure-hot-vs-cold.ps1`（Home 再开 = 热；`force-stop` 再开 = 冷）。
- 鲸鱼罩只在 App；hanui 不放启动鲸。
- 壳侧已接入：`androidx.webkit` 1.16 + `WebViewCompat.startUpWebView`（Application 尽早后台预热，不预建 WebView）；WorkManager/旧通知清理推迟到首帧后；`LaunchTrace` 含 `webviewCreateBegin/End`、`loadUrl`。
- 服务器 composition：生产仅安全禁用 `client-hmr` + `repo-testprobe`；大批量 UI `disabled` 会弄坏 Boot（已回滚）。清单与实测：`artifacts/hotstart-diag/`。
- hanui：App 内 CSS 隐藏 `[data-dsh-boot]`（不放鲸、不提早 `pageReady`）。
- 有缓存冷启揭罩约 **3.4–3.8s**（Displayed ≈1.7–2.0s）；热启 TotalTime ≈80–120ms。

## 明确不做

不为性能去改官方 Boot/源码、预置官网快照、拉长 Cookie TTL、或把业务协议搬进 App。
