# 架构

当前交付物是三部分协作，而不是旧的 Compose 原生 Harness 客户端。

| 组件 | 职责 | 不负责 |
|---|---|---|
| `dsh-mobile-hanui` | 手机网页布局与站点侧平台请求 | 验密、系统权限、DSH 服务端内核 |
| `dsh-local-hanaccount` | 密码登录、门禁 Cookie、官方浏览器身份 | Android UI、聊天协议实现 |
| Android 壳 | HTTPS WebView、站点元数据、系统选择器/通知/下载/生命周期 | DSH DTO、原生业务 WebSocket、针对 DOM 的布局补丁 |

Android 站点适配是可替换边界，不是第四个独立部署产品。契约见 [THREE-MODULE-INTEGRATION.md](THREE-MODULE-INTEGRATION.md)。

## 当前 APK（`app/src/shell`）

```
DshApplication     WebView 数据目录后缀；尽早 startUpWebView 预热；首帧后再清理旧 KeepAlive/通知
MainActivity       单站点 WebView；单段启动遮罩；桥；下载/长按存图；切站确认
browser/
  BrowserSession   WebView 预热（不预建页面）；创建会话绑定
  SiteRepository   站点列表、清理事务、冷启用 lastSessionId（非密码）
  PlatformCredentialStore  Keystore AES-GCM，按精确 HTTPS origin 存密码
  WebsiteBridge    AndroidX WebMessageListener「HanApp」，精确 origin
  BridgeReplyDelivery  出站回复：evaluateJavascript → HanApp.onmessage（避开 ReplyProxy JNI）
  LaunchCoverState 系统 splash + 品牌罩保持到 Ready/Failed；普通导航不重贴 Logo
  BridgeProtocol   消息长度与类型校验
  NavigationPolicy 仅 HTTPS、无内嵌账号、同 origin 才算站内
  SafeDownload     前台、同 origin GET、不跟随跨站重定向、25 MiB
  StaticAssetCache 公开 immutable 的 JS/CSS；磁盘命中流式回放（带 Content-Length），不再 HEAD；退出时删除
  WebCompatibility document-start：API 补齐 + 可选注入上次 session id
  BrowserStorage   API 28+ 的 WebView 数据目录后缀；失败则禁止打开网站
```

构建配置把 `main` 的 Java/Kotlin 与 assets 指到 `src/shell/`。`app/src/main/` 仅保留 Manifest 与资源（图标、主题、网络安全配置）。

## 数据流

1. 进程启动时设置 WebView 存储后缀，并尽早后台预热 WebView（不预建页面）。
2. 加载当前站点 `entryUrl`（默认 `https://dsh.wannian.fun/`）；若有上次 session id，在 document-start 写入网页 `localStorage` 键以缩短冷启重开。
3. 网页用官方接口与 `password-native-v1` 完成登录；Cookie 留在 WebView CookieManager。
4. 网页可通过 `HanApp` 在前台、主框架、当前 generation 下读写本机加密密码、申请通知、展示通知、请求切站。
5. 切站或本地退出：先落下清理事务，销毁 WebView，删除静态缓存与记住的 session id；需要完整删除浏览数据时可能杀进程并要求从桌面重新打开。

## 已移除的旧栈

旧 Compose 原生客户端、`:core` / `:mock-harness` / `:conformance`，以及内嵌 xterm 终端资源已从本仓库删除。不要再当作回退路径或兼容证明。迁移期笔记见 [archive/](archive/)。
