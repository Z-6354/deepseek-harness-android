# 架构

当前交付物是三部分协作，而不是旧的 Compose 原生 Harness 客户端。

| 组件 | 本地检出 | 职责 | 不负责 |
|---|---|---|---|
| `dsh-mobile-hanui` | `plugins/dsh-mobile-hanui/` | 手机网页布局与站点侧平台请求 | 验密、系统权限、DSH 服务端内核 |
| `dsh-local-hanaccount` | `plugins/dsh-local-hanaccount/` | 密码登录、门禁 Cookie、官方浏览器身份 | Android UI、聊天协议实现 |
| `dsh-session-cache-sync` | `plugins/dsh-session-cache-sync/` | 会话历史持久缓存与差量同步 | 官方 Session owner / 渲染 |
| Android 壳 | `app/src/shell/` | HTTPS WebView、站点元数据、系统选择器/通知/下载/生命周期 | DSH DTO、原生业务 WebSocket、针对 DOM 的布局补丁 |

Android 站点适配是可替换边界，不是第四个独立部署产品。插件目录说明见 [../plugins/README.md](../plugins/README.md)；契约见 [THREE-MODULE-INTEGRATION.md](THREE-MODULE-INTEGRATION.md)。

## 当前 APK（`app/src/shell`）

```
DshApplication     WebView 数据目录后缀；尽早 startUpWebView 预热；首帧后再清理旧 KeepAlive/通知
MainActivity       系统 UI、能力分派、切站确认、LaunchCover 展示
  BrowserRuntime   WebView/Client/桥/缓存/文档/视觉生命周期与 lease 存活判定
browser/
  BrowserSession   WebView provider 预热（非聊天会话）
  DocumentEpoch    唯一文档 generation / commit / interactive，browserInstance+generation lease
  LaunchCoverState 启动罩观察者（表面、splash、展示预算）；不停管道
  SiteRepository   站点列表、清理事务、干净文档地址；迁移时删除旧会话偏好
  PlatformCredentialStore  Keystore AES-GCM，按精确 HTTPS origin 存密码
  WebsiteBridge    AndroidX WebMessageListener「HanApp」，精确 origin
  BridgeReplyDelivery  出站回复：evaluateJavascript → HanApp.onmessage（避开 ReplyProxy JNI）
  BridgeProtocol   消息长度与类型校验
  NavigationPolicy 仅 HTTPS、无内嵌账号、同 origin 才算站内
  SafeDownload     前台、同 origin GET、不跟随跨站重定向、25 MiB
  StaticAssetCache 公开 immutable 的 JS/CSS；磁盘命中流式回放（带 Content-Length），不再 HEAD；退出时删除
  WebCompatibility document-start：只补齐 Web Platform API（HyperOS 下延后到首 commit，缺 API 时软 reload 一次）
  BrowserStorage   API 28+ 的 WebView 数据目录后缀；失败则禁止打开网站
```

本轮架构与验证范围见 [运行时实施计划](plans/2026-10-07-runtime-architecture-plan.md)。构建配置把 `main` 的 Java/Kotlin 与 assets 指到 `src/shell/`。`app/src/main/` 仅保留 Manifest 与资源（图标、主题、网络安全配置）。

## 数据流

1. 进程启动时设置 WebView 存储后缀；当前不在 Application 里预热 WebView（HyperOS 上与 Activity WebView 竞态会崩）。
2. 加载当前站点 `entryUrl`（默认 `https://<SITE_HOST>/`）；APP 不读写网页会话键。
3. 网页用官方接口与 `password-native-v1` 完成登录；Cookie 留在 WebView CookieManager。
4. 网页可通过 `HanApp` 在前台、主框架、当前 generation 下读写本机加密密码、申请通知、展示通知、请求切站。
5. 切站或本地退出：先落下清理事务，销毁 WebView，删除静态缓存；需要完整删除浏览数据时可能杀进程并要求从桌面重新打开。

## 已移除的旧栈

旧 Compose 原生客户端、`:core` / `:mock-harness` / `:conformance`，以及内嵌 xterm 终端资源已从本仓库删除。不要再当作回退路径或兼容证明。迁移期笔记见 [archive/](archive/)。

## 启动事实所有权

hanaccount 的 `AuthSession` 是客户端认证唯一实例，由源码生成独立 bundle；可选 `hanaccountAuth` 只暴露 status/reason/scope/revision。服务端为有效门禁会话生成独立随机 `resumeScope`，只有门禁和官方身份同时有效时返回；它只分区偏好，不参与授权，也不是多账号系统。

hanui `session-adapter.js` 只投影官方 `ISessions`；`startup.js` 持有恢复尝试与单个带 scope 的 TargetStore。官方 current、列表、消息和图片仍归官方。加载界面通过 `shell.overlay` 挂载，历史 open 与可见绘制分别确认；公共 `conversation.composer.dock` 的 session owner 配合已审计 DOM 判正文/输入。能力失配可撤销，并明确降级。

BrowserRuntime 只接受当前实例和当前文档的 lease。热恢复不 loadUrl，不把 onResume 当视觉完成；正常揭罩需要真实 WebView visual callback。平台 JS 图片结果等待有超时且随原生任务退休取消。诊断只观察，不控制上述状态机。
