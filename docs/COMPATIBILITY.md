# 兼容性

当前 APK 是官方网站的 WebView 容器。下面的矩阵只对这一构建有效；旧原生客户端与 Relay 的版本对照表已失效，不要用来选型。

## 当前组合

| 一层 | 要求 |
|---|---|
| Android | 9.0+（minSdk 28），targetSdk 35 |
| WebView | 支持 `DOCUMENT_START_SCRIPT`、`WEB_MESSAGE_LISTENER`、`DELETE_BROWSING_DATA` 才具备完整增强与切站清理 |
| 传输 | 仅 HTTPS；系统 CA；登录与带 Cookie 的请求不跟随重定向 |
| 认证插件 | `dsh-local-hanaccount` 声明 `password-native-v1`、`passwordOnly`、`nativeSessionBridge` |
| 手机布局 | `dsh-mobile-hanui` 适配当前官方网页结构 |
| 业务 UI | 服务器上的官方 DeepSeek Harness Web，不由 App 实现 DTO |

Harness **版本号单独不能**证明可登录。必须看插件 status 是否打出上述协议字段。

已发布的旧 APK（Compose 原生客户端、Relay、配对码）与本源码构建不等价，不能混用说明。

## WebView 缺口

旧 WebView 缺少 `Promise.withResolvers` / `AbortSignal.any` 时，壳在精确 HTTPS origin 的 document-start 注入 `web-compat.js`。不支持该注入能力时只提示更新 WebView，不会晚注入，也不会改认证。DOM 手机适配属于 hanui，不属于 App。

## 明确不支持

- HTTP 密码会话、信任用户安装的 CA、忽略证书错误
- Relay、二维码配对、外部启动 Token、原生 `/api` 客户端回退
- 离线推送、受限设备身份、把 Cookie 导出给任意脚本
- 跨 origin 文件下载、POST 导出（请用系统浏览器）；同源图片可长按保存（含 blob/data，上限小于文件下载）

旧原生协议模块（`core` / `mock-harness` / `conformance`）与抓包工具已从本仓库移除；不要再拿它们当当前壳的兼容证明。
