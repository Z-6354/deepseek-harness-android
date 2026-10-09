# 协议要点

当前 APK **不实现** DeepSeek Harness 的 `/api` 原生客户端协议。聊天、会话跟随、工具卡片都由服务器上的官方网页完成。权威类型定义仍在 Harness 仓库。

本文件只记录壳与认证插件仍然依赖的契约。旧版 Compose 客户端的 mux、RPC、Relay 说明已失效。

## 网页与壳

- 站点入口必须是干净的 `https://` URL（无用户名密码、无查询、无片段）。
- 可选桥 `HanApp`：入站为 AndroidX `WebMessageListener`；出站用受控 `evaluateJavascript` 调用 `HanApp.onmessage`（与 Listener 同形的 `{data}` 事件），不调用 `JavaScriptReplyProxy.postMessage`（部分 WebView 110 上会原生崩溃）。消息 JSON `{version:1,id,type,payload}`，回复 `{version:1,id,ok,result?,error?}`。
- 允许的 `type`：`pageReady`、`capabilities`、`requestNotificationPermission`、`showNotification`、`changeWebsite`、`readCredential`、`saveCredential`。
- `changeWebsite` 的 payload 只有 `{url}`；`saveCredential` 只有 `{password}`；`readCredential` 的 payload 必须为空对象。调用方不能指定 origin。
- 桥要求主框架、精确 origin、generation 一致。除 `pageReady` 外还要求已提交的站内文档。`pageReady` 允许在文档尚未 commit 时进入 pending，以便启动遮罩与第一帧对齐。改站/读写密码还要求前台。

## 认证插件 `password-native-v1`

路由前缀：`/dsh-local-hanaccount/api`。

| 路由 | 契约 |
|---|---|
| `GET /status` | `auth.protocol="password-native-v1"`，`passwordOnly=true`，`nativeSessionBridge=true`；缺字段视为不支持 |
| `POST /auth/login` | JSON `{ "password": "…" }`；成功 `{ "ok": true, "protocol": "password-native-v1" }` 且两个独立的 `Set-Cookie` |
| `GET /auth/me` | 带两枚 Cookie 时返回该协议且 `authenticated=true`、`nativeAuthenticated=true` |
| `POST /auth/logout` | 撤销门禁并过期两枚 Cookie；远程登出在本地清理之后尽力而为 |

两枚 Cookie：`dsh_gate_token` 与一枚 `dsh-auth-*`。必须为 Secure、host-only、path `/`。登录与已认证请求不跟随重定向，不走 HTTP。

常见错误：`invalid_password`（401）、`rate_limited`（429）、`access_denied`（403）、`proxy_misconfigured`（503）、`native_bridge_unavailable`（503）、`password_not_configured`（400）。

原生 Cookie 经官方公开缝合点在服务器内部签发，不把短时内部 URL 做成对外凭据流。门禁负责撤销。详见 [SECURITY.md](SECURITY.md)。
