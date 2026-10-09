# 三模块集成

本项目交付三个自制 DSH 插件与一个 Android 网站壳。业务 UI 与协议仍由 DSH 官方网页拥有。插件源码统一检出到本仓 [`plugins/`](../plugins/README.md)。

| 组件 | 本地检出 | 拥有 | 不拥有 |
|---|---|---|---|
| dsh-mobile-hanui | `plugins/dsh-mobile-hanui/` | 手机网页呈现、网页侧平台请求、授权历史图片缓存 Adapter、登录样式 | 验密、原生权限、DSH 服务端内部 |
| dsh-local-hanaccount | `plugins/dsh-local-hanaccount/` | 密码入口、访问控制、官方浏览器身份与撤销 | Android UI、业务聊天实现 |
| dsh-session-cache-sync | `plugins/dsh-session-cache-sync/` | 会话历史持久缓存与差量同步 | 官方 Session owner / 渲染 |
| Android App | `app/src/shell/` | HTTPS WebView、可替换站点配置、系统选择器/通知/权限/生命周期 | DSH DTO、原生业务 WS、针对 DOM 的布局修补 |

Android 仓库即本检出；三个插件为独立仓库/检出，不进入 `:app` Gradle 图。检出路径不是部署依赖，发布物必须使用经过测试的明确版本。
## 平台契约

优先使用标准 Web API。可选 `HanApp` 桥有版本约束，仅对当前精确 origin 的主框架开放。它提供能力查询、通知权限/展示、本机密码存取与切站确认；不导出 Cookie、不提供任意 fetch、不执行任意脚本、不经桥暴露 DSH 会话/工具对象。

最近非空会话偏好只由 hanui 按认证会话 scope 保存。APP 不镜像、不注入 session ID。官方当前选择仍由公开 `sessions.open/clear` 维护；原生和旧 hanui 无作用域偏好不导入，升级后需重新访问一次对话建立安全偏好。

App 阶段 A 提供可选的私有文件缓存协议；本地 hanui 已接入 `conversation.message.images` 的授权历史图片加载器。Adapter 验证当前 AuthSession、准确官方 binding、已就绪目录成员和连接代际，通过本页二进制探针后按 [私有文件协议](PROTOCOL.md#私有文件能力app-阶段-a) 使用缓存。命中不调用会触发后台下载的 `seedImageUrl`；未命中使用该会话的官方 `readAttachment`。App 的分区标签不是授权票据。上传预览、queue、trajectory 与工具图片继续官方流程。

两个网页插件可以分别更新：新 hanui 遇到旧 App、缺 scope 的旧 hanaccount、未知图片组件或不可用文件能力时使用官方加载；新 hanaccount 仍提供既有认证服务。文件桥和通知共用单一消息 dispatcher，原生仍不解析 DSH 对象。图片 schema 与程序 batch 版本独立，为后续 B 保留接缝。

没有桥时就是普通浏览器行为。通知业务判断在网页侧；原生展示不是离线投递。

登录发生在官方客户端图加载之前。登录样式可由 hanui 以同源静态资源提供，hanaccount 始终保留可用的内置样式。缺样式不得改变访问控制。

hanaccount 的静态 head bootstrap 提前查询现有同源 `/auth/me`，客户端接管同一个 AuthSession 与在途请求。全局 index 注入表只含代码，没有用户快照或凭据；CSP 或注入能力缺失时继续普通客户端认证。退出成功后先发布失效，再导航。图片缓存按需启动，初始页面没有图片使用时不发私有文件控制请求。

## 验收与发布门闩

需要插件 Node 测试、与官方 Host 的隔离探测、Android 单测/构建，以及真机 WebView 验证。HTTP 200 不能证明官方网页业务流程可用。

认证插件失败时，官方启动仍可能继续提供入口。插件测试通过不等于可以公网默认拒绝。在证明入口失败关闭之前，不要把本构建当作已完成的对外发布。

## WebView 兼容

缺失的 Web Platform API 由壳内可替换的 `WebCompatibility` 在精确 origin 的 document-start 注入。DOM 手机适配留在 hanui。

## 启动图与静态缓存

系统 splash 与品牌罩合成单段加载视觉；罩是观察者，不 `stopLoading`。hanui 在目标恢复中展示公开 overlay 加载界面，实际挂载后可 pageReady；只有目标历史 open、公共席位 owner、当前 scope/attempt 和精确内容/输入绘制一致才撤网页加载界面。认证可用操作优先；未知能力明确降级而不永久遮挡。展示预算超时不删除恢复目标，不中断管道。有缓存冷启可交互 p95<5s、热启 p95<1s 仍是目标，本轮没有足够真实匹配版本样本证明达标。

公开 immutable 的 JS/CSS 使用有上限的磁盘缓存；带版本的 URL 命中磁盘时流式回放，不再用 HEAD 做登录鉴权。HTML、API 与个人数据不进该缓存。

**本地退出与站点清理会删除该磁盘缓存**，避免 Cookie 请求头留在明文缓存文件里。普通按 Home 退出仍保留浏览状态与登录 Cookie。

## 本轮验证界限

本地 Node 实际生成 bundle、官方只读 source Host 认证契约 fixture、Android unit/build、MuMu Android 15/API35 定向 BrowserRuntime HTML 生命周期测试已执行。定向设备测试不是完整新插件 B 集成；新插件未部署生产。官方 Host probe 报 `deploymentReady=false` 的既有 optional Loader 失败入口限制仍存在，不能据此发布。

测量入口：`powershell -File scripts/measure-runtime-phases.ps1 -Serial 127.0.0.1:16384 -Samples 20`。仅在匹配新版插件且开启网页诊断的授权环境运行；按原生 beginLaunch 归零，网页相对时间单列。缺 body/input/image 或主页可见性证据保持 unknown。普通 URL 与 Blob/data 图片显示有网页 probe，readAttachment/RPC bytes 内部时序仍需专门取证，不宣称图片慢已修复。

本地插件 A 接入及最终验证记录见 [插件更新计划](plans/2026-10-07-plugin-stage-a-update-plan.md)。本次没有安装新版 App、部署插件或执行真实 WebView 图片闭环；首阶段仍以实际官方预览说明弹窗为终点，已有约 7.4 秒记录没有被新的实测结果替代。
