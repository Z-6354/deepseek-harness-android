# 兼容性

当前 APK 是官方网站的 WebView 容器。下面的矩阵只对这一构建有效；旧原生客户端与 Relay 的版本对照表已失效，不要用来选型。

## 当前组合

| 一层 | 要求 |
|---|---|
| Android | 9.0+（minSdk 28），targetSdk 35 |
| WebView | 支持 `DOCUMENT_START_SCRIPT`、`WEB_MESSAGE_LISTENER`、`DELETE_BROWSING_DATA` 才具备完整增强与切站清理 |
| 私有文件缓存 | 还需 `WEB_MESSAGE_ARRAY_BUFFER`、可用的 no-backup 私有存储，以及每个文档成功完成 binary/local-read/hash probe；不满足时网页应继续原读取路径 |
| 传输 | 仅 HTTPS；系统 CA；登录与带 Cookie 的请求不跟随重定向 |
| 认证插件 | `dsh-local-hanaccount` 声明 `password-native-v1`、`passwordOnly`、`nativeSessionBridge` |
| 手机布局 | `dsh-mobile-hanui` 适配当前官方网页结构 |
| 业务 UI | 服务器上的官方 DeepSeek Harness Web，不由 App 实现 DTO |

Harness **版本号单独不能**证明可登录。必须看插件 status 是否打出上述协议字段。

已发布的旧 APK（Compose 原生客户端、Relay、配对码）与本源码构建不等价，不能混用说明。

## WebView 缺口

旧 WebView 缺少 `Promise.withResolvers` / `AbortSignal.any` 时，壳在精确 HTTPS origin 注入 `web-compat.js`（`DOCUMENT_START_SCRIPT`）。

**HyperOS / WebView 152：** 不得在首个 `loadUrl` 之前调用 `addDocumentStartJavaScript`（会 SIGSEGV）。壳改为首文档 commit 后再注册脚本；若探测到当前文档仍缺上述 API，则**同 origin 软 reload 一次**，让二次导航吃到 document-start。不支持该注入能力时只提示更新 WebView，**不会**用 `evaluateJavascript` 晚补 polyfill，也不改认证。现代已带原生 API 的 WebView 不会触发 reload。DOM 手机适配属于 hanui，不属于 App。

## 明确不支持

- HTTP 密码会话、信任用户安装的 CA、忽略证书错误
- Relay、二维码配对、外部启动 Token、原生 `/api` 客户端回退
- 离线推送、受限设备身份、把 Cookie 导出给任意脚本
- 跨 origin 文件下载、POST 导出（请用系统浏览器）；同源图片可长按保存（含 blob/data，上限小于文件下载）

旧原生协议模块（`core` / `mock-harness` / `conformance`）与抓包工具已从本仓库移除；不要再拿它们当当前壳的兼容证明。

## 运行时架构迁移矩阵

| 组合 | 行为 |
|---|---|
| 新 hanaccount + 新 hanui + 新 APP | 带 scope 恢复、公开目标加载界面、官方历史/owner 绘制判定；DOM/能力仍需具体部署验收 |
| 旧 hanaccount / 无 scope + 新 hanui | 不自动读取跨启动目标；5s装配等待后明确降级；晚到服务不能抢回导航 |
| 新 hanui + 旧 APP | 保留空 pageReady；hanui 不信原生 seed，按新 scope 校正；新诊断失败不阻塞 |
| 新 APP + 旧 hanui | 不再原生 seed；旧网页自有恢复仍可用；没有新 scope 保证 |
| 服务撤销或未知历史/视图能力 | 退休恢复与观察者，回可用官方页面；不算 B 验收通过 |

推荐部署顺序为认证插件→手机 UI 插件→APP；当前只完成本地构建，没有发布或生产替换。旧无作用域偏好不迁移，不删除真实对话。
