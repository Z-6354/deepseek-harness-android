# HyperOS WebView 152 崩溃缓解 — 审查

日期：2026-10-09。对象：0.12.11 会话结论与当前工作区 `BrowserRuntime` / `DshApplication` / 更新检查路径。只读审查，不改代码。

## 会话结论（通知摘要）

| 项 | 内容 |
|---|---|
| 现象 | 红米 K80（HyperOS）冷启 SIGSEGV，`fault addr 0x18`，`libwebviewchromium` |
| 根因 | `loadUrl` 前调用 `WebCompatibility.install()`（`addDocumentStartJavaScript`）与 Chromium 启动竞态 |
| 非根因 | 更新密码、门禁、静态资源拦截本身 |
| 已落地缓解 | 首文档 **commit 后**再装兼容脚本；桥 / 私有文件在 `onPageStarted` 绑定；Application **跳过** WebView warmup；更新检查走 IO，对话框等罩落地 |
| 设备证据 | K80 上 0.12.11 冷启无 Fatal；频道与机身均为 `0.12.11` / versionCode 1211 |

## 审查发现

### [P1] 首文档拿不到 document-start 兼容 — `BrowserRuntime.kt` `commit` / `ensureCompatibilityInstalled`

`addDocumentStartJavaScript` 只作用于**之后**的导航，不会回灌当前正在加载的文档。现路径在 `onInternalCommit` 之后才 `install`，因此：

- **冷启第一次主文档**：无 `web-compat.js`
- **同 origin 后续导航**：有兼容脚本

这与契约冲突：

- [COMPATIBILITY.md](../COMPATIBILITY.md)：缺口 API 在 document-start 注入；不支持时「不会晚注入」
- `WebCompatibility.kt`：明确拒绝 late `evaluateJavascript` 回退

K80（WebView 152）上原生多半已有 `Promise.withResolvers` / `AbortSignal.any`，所以首屏「看起来正常」不能证明契约仍成立。旧 WebView 若仍缺 API，冷启首屏会直接踩到无 polyfill 路径。

### [P2] 设备验收测的是旧安装时序 — `WebCompatibilityDeviceTest.kt`

设备测仍在 `loadUrl` **之前** `WebCompatibility.install()`，断言「首个 inline 前 API 已存在」。这覆盖的是崩溃前的安全路径，**没有**覆盖生产的「commit 后安装」时序，也测不到 HyperOS 竞态回归。

### [P2] 契约文档未随缓解更新 — `COMPATIBILITY.md` / `ARCHITECTURE.md`

文档仍写「document-start 注入 / 不会晚注入」。当前实现是 OEM 规避下的延后注册，读者会误判首屏行为与验收标准。

### [P3] warmup 永久关闭与第三阶段墙钟张力 — `DshApplication.kt`

跳过 `WebViewCompat.startUpWebView` 有助于稳定性；第三阶段基线里 `am start` TotalTime 仍约 **4.9 s**（见 [phase3](2026-10-08-phase3-cold-path.md)）。无对照实验前不宜盲目恢复 warmup，但应作为「有门控的对照项」保留，而不是默认为永久正确。

## 非缺陷（有意取舍）

- 桥 / 私有文件改到 `onPageStarted` 再 `bind`：合理规避早期 `WebMessageListener`；`pageReady` 仍要求 commit 后 lease，早期脚本一般碰不到 HanApp。
- 更新对话框等 `surfaceReady` / Failed：合理，避免与 Chromium 首启叠 AlertDialog。
- 静态资源拦截在 0.12.10+ 已恢复：与崩溃根因正交，K80 上已证可并存。

## 测试缺口

| 缺口 | 为何重要 |
|---|---|
| 生产时序设备测 | commit 后再 install，再同 origin 二次导航断言 polyfill |
| 首屏无 polyfill 矩阵 | WebView 缺 API 的机型上冷启是否可接受 / 是否需一次性 reload |
| HyperOS 回归烟雾 | K80 force-stop ×N：无 Fatal + pageReady；含更新弹窗路径 |
| warmup 对照 | 仅非 HyperOS 或 settled 后尝试，禁止再做串行 gate（phase3 已证有害） |

## 总评

缓解对「HyperOS + WebView 152 冷启必崩」有效，设备证据充分。最大未闭合风险是：**用延后 document-start 换稳定，却让首文档失去契约承诺的 polyfill**；在现代 WebView 上被掩盖，在旧缺口 WebView 上会变成功能缺陷。下一步应先闭合契约与验收，再与第三阶段冷路径实验合流（见同日计划）。
