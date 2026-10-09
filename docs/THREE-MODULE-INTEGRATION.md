# 三模块集成

本项目交付两个 DSH 插件与一个 Android 网站壳。业务 UI 与协议仍由 DSH 官方网页拥有。

| 组件 | 拥有 | 不拥有 |
|---|---|---|
| dsh-mobile-hanui | 手机网页呈现、网页侧平台请求、登录样式 | 验密、原生权限、DSH 服务端内部 |
| dsh-local-hanaccount | 密码入口、访问控制、官方浏览器身份与撤销 | Android UI、业务聊天实现 |
| Android App | HTTPS WebView、可替换站点配置、系统选择器/通知/权限/生命周期 | DSH DTO、原生业务 WS、针对 DOM 的布局修补 |

Android 仓库即本检出；认证与手机 UI 插件为独立仓库/检出，不进入 `:app` Gradle 图。检出路径不是部署依赖，发布物必须使用经过测试的明确版本。

## 平台契约

优先使用标准 Web API。可选 `HanApp` 桥有版本约束，仅对当前精确 origin 的主框架开放。它提供能力查询、通知权限/展示、本机密码存取与切站确认；不导出 Cookie、不提供任意 fetch、不执行任意脚本、不经桥暴露 DSH 会话/工具对象。

壳可在 document-start 把本机记住的 **session id**（非 Cookie、非密码）写入网页 `localStorage`，仅用于冷启尽快回到上次会话；切站/本地退出时清除。这不属于 `HanApp` 能力面。

没有桥时就是普通浏览器行为。通知业务判断在网页侧；原生展示不是离线投递。

登录发生在官方客户端图加载之前。登录样式可由 hanui 以同源静态资源提供，hanaccount 始终保留可用的内置样式。缺样式不得改变访问控制。

## 验收与发布门闩

需要插件 Node 测试、与官方 Host 的隔离探测、Android 单测/构建，以及真机 WebView 验证。HTTP 200 不能证明官方网页业务流程可用。

认证插件失败时，官方启动仍可能继续提供入口。插件测试通过不等于可以公网默认拒绝。在证明入口失败关闭之前，不要把本构建当作已完成的对外发布。

## WebView 兼容

缺失的 Web Platform API，以及可选的上次 session id 种子，由壳内可替换的 `WebCompatibility` 在精确 origin 的 document-start 注入。DOM 手机适配留在 hanui。

## 启动图与静态缓存

系统 splash 与品牌罩保持到 Ready/Failed（单段加载）。主框架在精确 origin 上发送空 payload 的 `pageReady` 后，壳等待 visual-state 再揭开。`pageReady` 可在文档 commit 前进入 pending。公开 immutable 的 JS/CSS 使用有上限的磁盘缓存；带版本的 URL 命中磁盘时流式回放，不再用 HEAD 做登录鉴权。HTML、API 与个人数据不进该缓存。

**本地退出与站点清理会删除该磁盘缓存**，避免 Cookie 请求头留在明文缓存文件里。普通按 Home 退出仍保留浏览状态与登录 Cookie。
