# 协议要点

当前 APK **不实现** DeepSeek Harness 的 `/api` 原生客户端协议。聊天、会话跟随、工具卡片都由服务器上的官方网页完成。权威类型定义仍在 Harness 仓库。

本文件只记录壳与认证插件仍然依赖的契约。旧版 Compose 客户端的 mux、RPC、Relay 说明已失效。

## 网页与壳

- 站点入口必须是干净的 `https://` URL（无用户名密码、无查询、无片段）。
- 可选桥 `HanApp`：入站为 AndroidX `WebMessageListener`；出站用受控 `evaluateJavascript` 调用 `HanApp.onmessage`（与 Listener 同形的 `{data}` 事件），不调用 `JavaScriptReplyProxy.postMessage`（部分 WebView 110 上会原生崩溃）。消息 JSON `{version:1,id,type,payload}`，回复 `{version:1,id,ok,result?,error?}`。
- 允许的 `type`：`pageReady`、`pageLifecycle`、`capabilities`、`requestNotificationPermission`、`showNotification`、`changeWebsite`、`readCredential`、`saveCredential`。
- `changeWebsite` 的 payload 只有 `{url}`；`saveCredential` 只有 `{password}`；`readCredential` 的 payload 必须为空对象。调用方不能指定 origin。
- 桥要求主框架、精确 origin、当前文档与浏览器 lease 一致。除 `pageReady` 外还要求已提交的站内文档。`pageReady` 是无回复的通知，payload 必须为 `{}`，表示当前网页已有可展示界面（目标加载页、登录/认证错误页、已确定的主页）；不表示历史或图片已经完成。commit 前只进入 pending；当前文档的真实 visual callback 才揭原生罩。读写密码/改站仍要求前台。
- `capabilities.pageLifecycleVersion=1` 支持可选无回复 `pageLifecycle` 通知。payload 精确为 `{schemaVersion:1,stage,webElapsedMs,sequence}`；stage 只能为 factory/authChecking/authReady/listReady/targetSelected/historyReady/loadingPaint/bodyPaint/inputReady/imageShown/imageFailed/compatibility。webElapsedMs 为有限数值 0–600000，sequence 为 1–128 整数。每文档最多64条、每秒最多16条、sequence 递增；失败不影响 pageReady。
- 诊断不传会话 ID、scope、凭据、正文或 URL。原生仅记录收到事件的单调时刻及网页相对时间，两种时钟不直接相减。旧壳拒绝新 type 时网页继续原有 pageReady。

### 私有文件能力（App 阶段 A）

`capabilities.privateFiles` 仅在 WebMessageListener、ArrayBuffer 与私有存储可用时出现，初始状态为 `probe-required`。网页必须在当前已提交文档中完成 `privateFiles.probe` 的二进制上传、同源一次性读取及 SHA-256 确认后，能力才变为 `ready`。桥协议允许的 `privateFiles.*` 控制请求及字段见 [App 阶段 A 计划](plans/2026-10-07-app-stage-a-update-plan.md)；不支持该能力时继续使用网页原有读取方式。

`bindingLabel` 和文件 `key` 是 64 位小写十六进制摘要，用于分区/条目选择，不是服务器授权凭据。二进制只经 `HanPrivateFileWriter` ArrayBuffer 帧发送；ACK 仍走 `HanApp.onmessage`。文件读取由同源、30 秒、单次 token URL 完成。该能力不导出 Cookie、会话 scope 或服务器授权结论，也不接入插件业务逻辑。

当前网页 Adapter 使用原生 namespace `private-cache-v1`；业务 label/key 另含 `hanui-images` schema 1，与 JS batch 的版本无关。探针 URL 的随机 id 为 32 位十六进制，正常一次性 read token 为 48 位。`clearPartition` 即使回复 `ok:true`，仍须检查 `result.cleared===true`；失败保留网页待清理账本，不能据此复用旧分区。页面只持久化有界摘要账本，不持久化 handle、read token 或 Blob URL。授权、会话删除和身份失效由网页服务判断，存储配额及文件安全代际由 App 管理。

## 认证插件 `password-native-v1`

路由前缀：`/dsh-local-hanaccount/api`。

| 路由 | 契约 |
|---|---|
| `GET /status` | `auth.protocol="password-native-v1"`，`passwordOnly=true`，`nativeSessionBridge=true`；缺字段视为不支持 |
| `POST /auth/login` | JSON `{ "password": "…" }`；成功 `{ "ok": true, "protocol": "password-native-v1" }` 且两个独立的 `Set-Cookie` |
| `GET /auth/me` | 带两枚有效 Cookie 时返回该协议且 `authenticated=true`、`nativeAuthenticated=true`，并可返回非凭据 `resumeScope` |
| `POST /auth/logout` | 撤销门禁并过期两枚 Cookie；远程登出在本地清理之后尽力而为 |

两枚 Cookie：`dsh_gate_token` 与一枚 `dsh-auth-*`。必须为 Secure、host-only、path `/`。登录与已认证请求不跟随重定向，不走 HTTP。

常见错误：`invalid_password`（401）、`rate_limited`（429）、`access_denied`（403）、`proxy_misconfigured`（503）、`native_bridge_unavailable`（503）、`password_not_configured`（400）。

原生 Cookie 经官方公开缝合点在服务器内部签发，不把短时内部 URL 做成对外凭据流。门禁负责撤销。详见 [SECURITY.md](SECURITY.md)。

`hanaccountAuth` 网页服务提供 `getSnapshot()` / `subscribe(listener)` / `refresh()`，并增加 `invalidate(reason)` 表达本地撤销；它不授予认证权限。scope 跨同一次有效登录稳定，重新登录或撤销后新会话使用新随机值。旧有效 session 在首次已认证 `/auth/me` 原子补齐；补齐写盘失败遵循失败关闭，scope 不延长 TTL。网络暂不可用不视为退出。

提前认证使用文档内 `__HANACCOUNT_BOOTSTRAP_V1__` 的一次性 `claim()` 交接同一 AuthSession，不序列化结果、不持久化认证状态。已完成的初始查询在接管和 LoginGate 挂载时不重复发送；普通重挂载重新查询。bootstrap 缺失或协议不匹配时使用普通认证路径，不放宽 CSP 或门禁。
