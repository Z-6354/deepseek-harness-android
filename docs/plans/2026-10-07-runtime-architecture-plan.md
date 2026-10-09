# 三层运行时架构与最近会话启动实施计划

状态：S1–S5 与 S7 的本地实现及定向验证完成；S6 完成可控本地验证，匹配新版插件的完整已登录 APP 路由/图片/分布验收尚未完成。没有提交、推送或部署。日期：2026-10-07。

用户已选择 B：启动直接进入最近对话的加载界面，再显示消息和图片。最新优先级是**架构清晰、方便后续内容优化优先；本轮性能与闪首页修复其次**。最近对话指上次访问的非空对话。本计划是本轮实施依据，取代 [归档的 launch-decoupling-audit](../archive/2026-10-07-launch-decoupling-audit.md) 中“允许揭罩后由主页跳到会话”“恢复只是可选增强”的产品决定；保留其中文档代际与视觉展示分离、不让罩阻止管道的原则。未完成验证前不得把目标架构写成已落地。

## 1. 现状证据与约束

已核读 Android 壳、hanui、hanaccount 的相关实现、各可用 AGENTS 和现行契约；Android 与 hanui 有未提交工作，必须以当前工作树为基线增量修改，不覆盖或回退。三个自制插件检出在本仓 `plugins/`（`dsh-local-hanaccount`、`dsh-mobile-hanui`、`dsh-session-cache-sync`）。官方检出 `../deepseek-harness/` 只读，不修改核心或编译产物。

| 层 | 实际实现与问题 | 本轮处理 |
|---|---|---|
| 整体 | 官方拥有业务连接、会话和内容；两个插件与 APP 的三分工正确，但“谁决定目标、谁判定就绪、谁保存最近会话”跨三层重复 | 保留三产品，不新增插件或通用框架；写清事实唯一所有者及版本兼容 |
| APP | `MainActivity` 仍管理 WebView 创建、Client 回调、文档代际、桥安装、缓存、视觉回调、恢复、超时、会话脚本和原生能力；文档称“薄适配”早于实际 | 收拢一个真正拥有 WebView 文档生命周期的 Module；Activity 留系统交互/视图与能力分派 |
| APP | `SiteRepository.lastSessionId`、document-start seed、`pageReady/onPause` 抓网页 localStorage；`Site.owner = id|revision|entryUrl` 并非账号 | 删除原生会话所有权与 DSH storage 键依赖；继续保留站点/清理事务 |
| APP | `DocumentEpoch` 和 `LaunchCoverState` 已在未提交工作中拆开，但 `onResume` 可把 awaitVisual 当已绘制；browserId 与 document generation 是不同轴 | 保留现有分工并明确 lease；视觉完成只接受当前文档的真实回调/明确的保守兼容路径 |
| hanui | `client.entry.js` 同时含平台请求、布局适配、通知、最近会话、DOM 就绪和装配；恢复门闩与 3200/1500ms 定时器纠缠 | 从真实职责 seam 抽出启动 Module、官方能力 Adapter、诊断 Module；保留已有布局/通知 Module |
| hanui | 原生、hanui key、官方 key 三份选择可能不同步；当前 ID 匹配即 release，并不等于历史载入或内容绘制 | 官方 current 是实时选择唯一事实；网页插件只持久化“下次启动意图”，不得另维护 current |
| hanaccount | `auth-session.js` 与 `client.js` 内复制了认证状态机；hanui 用 `[data-lha-loading]` 猜认证进度，但当前 LoginGate 故意用 `data-lha-checking` | 认证状态单一源码、单一运行实例；提供窄的可选网页服务，业务不靠认证 DOM 推断 |
| 观测 | 原生 LaunchTrace 有 begin/load/document/ready/visual；缺少认证、目标、历史、绘制、图片分段 | 建立可扩展、有界、可关闭的诊断能力；不把日志本身当完成信号 |
| 静态缓存 | 命中已流式；首次仍全量 `body.bytes()`、写盘 `fd.sync()` 后交 WebView；失败 Skip 可能触发第二次网络请求 | 本轮增加分阶段观测与故障归类；仅在测量证明为瓶颈后改实现，默认不重写缓存 |

现有两轮日志 begin→visual 为 4.077s / 9.598s，loadUrl→documentStarted 为 0.646s / 6.729s；两轮 JS/CSS 均 local hit，document commit→pageReady 约 1.5s。慢轮主要增量在主文档生命周期开始前，但该事件不是 DNS/TLS/TTFB 的精确替代。此证据不足以定 nginx、缓存或认证的根因，也不是 p95。匿名登录页 curl 不能代表已登录 APP。

只读核对官方本地 `0a15e36e7f`：`packages/api/session-controller/src/client/contract/sessions.ts` 有 `list/open/clear/binding`，binding.session 为可订阅 `SessionFace`，`SessionSnapshot.openState` 为 cold/loading/open/error；list.phase 为 pending/ready。`ui-conversation/.../historical-images.ts` 已有按 session 的历史图片缓存，经 `readAttachment` 得 bytes 再形成 Blob URL。**这只是已审计本地版本，线上版本仍需能力探测和真实验证。**

## 2. 决策记录：每种事实只有一个所有者

本节作为本轮 ADR；沿用现有 docs/plans 目录，不另建平行文档树。选择 B 及以下责任分配是已授权决定。

| 事实 | 唯一所有者 | 其它层允许持有的内容 |
|---|---|---|
| 是否通过认证、当前认证作用域 | hanaccount 服务端 + 同源客户端 AuthSession | hanui 只读状态；APP 不接收 token/身份/账号详情 |
| 当前会话、列表、历史载入状态 | 官方 `sessions` / `SessionFace` | hanui Adapter 只读投影并用公开动作导航；APP 无会话模型 |
| 上次访问的非空会话意图 | hanui 的带作用域 TargetStore | 官方 storage 由官方维护；APP 不镜像 sessionId |
| 启动决策与该次尝试状态 | hanui StartupCoordinator | 展示 Module 订阅；诊断记录事件；不复制官方列表/消息 |
| WebView 实例、文档代际、commit、存活性 | APP BrowserRuntime（拟名） | 桥、原生异步操作借用不可混淆的 lease |
| 原生罩、慢提示、视觉揭罩 | LaunchCoverState/视图 | 只观察运行时事实，不驱动业务恢复或中断加载 |
| 性能测量 | 两端各自诊断 Module | 只读采样，不能成为上述状态机的条件 |

不选择“只加延时/延长罩”：没有解决多份状态和跨层脚本；不选择原生缓存聊天或原生导航：破坏通用壳职责；不做全量 MVC/DI/EventBus 重写：没有实际变化点支持这些抽象。新增 Module 以消除调用方复杂性为标准，不能把每个函数搬成透传类。

## 3. 目标 Module 与小 Interface

### 3.1 APP：BrowserRuntime 拥有页面生命期

新增 `browser/BrowserRuntime.kt`（名称可按仓库习惯调整），移动实际的 WebView 配置、WebViewClient、兼容脚本、WebsiteBridge 绑定、StaticAssetCache 生命周期、document/commit/visual 的归属检查。内部沿用 `DocumentEpoch`，不要再新增另一份 generation。

对 Activity 的 Interface 控制为：`start(site, savedState, initialNavigation)`、常用导航/重载/后退方法、`setForeground`、`saveState`、`close`，以及单个运行时事实回调和平台请求回调。WebView 视图附着是明确的 Android Adapter。无须把每个 Android 回调暴露成新方法或创建通用命令总线。

Activity 保留系统文件选择、通知权限、下载目的地、设置/切站确认和视图绘制。桥协议解析/来源验证留现有 WebsiteBridge/BridgeProtocol，原生能力业务可继续在一个分派处；本轮不强制拆出通知、下载等已稳定流程。BrowserRuntime 必须拥有异步任务的存活判定，调用方只捕获/验证 lease `{browserInstance, documentGeneration}`，不能自己复制 origin/generation 检查。

遮罩读取 runtime 事件；`pageReady` 是“此网页现在有可展示界面”的兼容信号，既可以是正确目标的加载界面，也可以是登录/确定为空的主页。pageReady 不表示历史/图片完成。commit 前信号允许 pending；旧文档信号不得释放新罩；`onResume` 不合成 VisualComplete、不 loadUrl。正常热恢复沿用当前页面；渲染器死亡后是显式新一次恢复。

从 `MainActivity` 移除 `persistSessionFromWebView`；从 `WebCompatibility` 移除 DSH/hanui session seed，保留 Web Platform API 补齐；`SiteRepository` 退休原生 session 字段并清理旧值，站点清理机制不重写。不把 sessionId 改名为“通用metadata”继续塞桥。

### 3.2 hanaccount：共享认证状态与保守作用域

现有 `/auth/me` 没有 userId/accountId，密码/Passkey 访问的是同一 Harness 实例；**不能声称现有系统已有多账号隔离**。本轮使用比“同账号”更保守的**认证会话作用域**：每个有效门禁 session 保存独立随机 `resumeScope`，同一次登录跨 APP 重启稳定，重新登录/密码轮换/撤销后的新 session 得到新 scope。不是 bearer token，不从密码、Cookie 原文或认证散列直接派生；它只能分区 UI 偏好，绝不参与授权。

`store.issueSession()` 创建 scope；旧有效 session 可在第一次已认证 `/auth/me` 访问时原子补齐并持久化一次。只有 gate session 与 nativeAuthenticated 同时有效时才返回可选 `resumeScope`；未认证返回无值。持久化失败按既有失败关闭路径处理，不造临时不稳定 scope，不延长 TTL。旧客户端忽略新增字段，认证协议与授权语义不变。

认证恢复算法只保留一个源 `src/lib/auth-session.js`。将客户端壳作为 entry，通过小型确定性构建脚本嵌入该 Module，生成仍可独立加载的 `src/client.js`；测试实际生成 bundle，加入 stale 检查。避免运行期导入辅助源码或增加第三方构建依赖。

客户端由该唯一实例提供可选 Cordis 服务（建议 `hanaccountAuth`）：`getSnapshot()`、`subscribe(listener)`、`refresh()`；snapshot 只含状态、非敏感原因枚举、scope、生命周期修订号。LoginGate 和 hanui 读同一状态，不各自请求一次 `/auth/me`。注销/确认失效/作用域变化退休先前恢复；暂时网络错误不当作退出，不清有效 target，但暂停新的恢复动作。插件卸载撤销服务、订阅和未完成请求。

缺新服务/旧 `/auth/me` 无 scope 时保守降级：不读取或自动恢复自己保存的跨启动目标；官方页面仍可用。不能以 origin 当账号替代，也不能因等待超时断言已退出。未来若官方真有独立账号，可在 hanaccount 内换 scope 来源，而不修改 APP 或启动 Module。

### 3.3 hanui：启动、能力适配、展示分离

建议新源：`startup.js`（Coordinator + 内部 TargetStore）、`session-adapter.js`、`diagnostics.js`；加入现有 `scripts/bundle.mjs`。只把本轮实际职责从 entry 收拢，既有 effects/private-adapters/needs-action/platform 保留。`client.entry.js` 装配这些 Module 与既有 React slots。

`createStartupCoordinator({auth, sessions, targetStore, clock, diagnostics})` 提供 `getSnapshot/subscribe/retry/dispose`。snapshot 是轻量启动视图，不含官方对象/完整列表/消息。按需要包含 `phase`、`targetId`（仅网页内存）、可显示的原因、尝试号；不同维度如 historyReady/inputReady 用只读事实，不滥建互相重复的状态机。

`createSessionAdapter(sessions)` 统一探测公开 `list.getSnapshot/subscribe`、`open`、`clear`、`binding(id).session.getSnapshot/subscribe`，把当前 ID、列表是否已权威到达、目标存在与否、openState 投影给 Coordinator。公开 capability 缺失返回 unsupported；未知 openState 不猜成 ready。已审计版本能用这些能力不等于线上必有；添加与真实官方类型相符的 fixture，并在设备验证。

TargetStore 用 origin 自带 storage 隔离 + 服务端 scope 分区，存 `{schema,scope,sessionId}`，上限有界，只保留需要的当前作用域记录。不缓存标题/消息/图片，不写 `dsh.sessions.current`。本轮不安全迁移无作用域的老原生/hanui记忆；标记退休并在 scope 确认后清除旧自有 key，官方 key 由 `sessions.clear/open` 维护。升级后的第一次可能需要用户重新打开一次对话，这是换取可靠作用域的迁移代价，应在交付说明明确。

恢复中的程序性 current 变化不能覆盖目标；终态后订阅官方 current，记录已确认属于当前 scope 且非空的当前对话。空白新对话不覆盖最近非空值；目标确认删除才清记忆；pending/error/超时不清。同一尝试 open 一次；用户主动选了其它会话/明确转主页后取消自动恢复，迟到的列表、认证和历史回调不能抢回目标。

### 3.4 B 的展示契约

1. 原生加载阶段保持单段启动视觉，内容管道并行推进。
2. hanui 在公开 `shell.overlay` 注册自己拥有的轻量“正在打开最近对话”界面，形成稳定加载状态；只在该界面已实际挂载且对应当前尝试时允许 pageReady。无目标或认证未确定时不展示任何旧标题/正文。
3. auth scope 确认后读取目标，列表权威到达后执行公开 open；官方 current 匹配只是“选择完成”。已确认目标不存在或该 scope 无目标才走主页；账号/作用域变更时用公开 clear 排除旧选择，再开始当前 scope 决策。
4. 使用 SessionFace.openState 判断历史到达/错误；使用已验证的官方内容/输入席位或可撤销 DOM Adapter 确认可展示。优先公开状态；DOM 只用于绘制与视图匹配，不能推断认证。当前 ID、目标、作用域、尝试和视图 owner 必须一致才移除网页加载界面。目标存在时主页不得作为中转露出。
5. 历史到达与实际绘制分别记录；图片不阻挡正文/输入。图片尚慢时保留官方占位与重试。错误进入目标的可恢复加载状态，不把超时变成“没有会话”。提供重试及用户主动回主页的退出路径，操作后退休旧尝试。

这是网页内容加载界面，不增加第二个品牌鲸鱼启动动画；不克隆聊天 UI，不改官方根 DOM/内部状态。认证登录/错误界面优先，hanui 加载界面不得压住 LoginGate 的可用操作。若公开/DOM 能力失配，撤销自有效果，回到可用官方页面并记录 compatibility 降级；不能永久遮挡，更不能把降级报告为 B 验收通过。

## 4. 可扩展诊断能力

Android `RuntimeDiagnostics`（拟名）集中替代零散 LaunchTrace 拼接；接收受限枚举事件与数值，内部添加实例/文档/启动关联和单调时钟。默认关闭或仅 debug；ring buffer 有界，生产不上传。不记录 Cookie、scope、sessionId、正文、完整 URL/查询。保留必要静态资源类型、字节、cache hit、阶段耗时、错误类别。

网页 diagnostics 包装 User Timing/PerformanceObserver，在 dispose 时解绑；Performance API 不支持或跨源字段不可见时报告 unknown，不能填零。阶段至少覆盖：factory开始、auth检查起止、列表ready、目标解析/选择、historyReady、加载界面paint、正文paint、输入可用、首屏图片显示/失败。新优化只新增 probe，不改 StartupCoordinator 分支。

桥保持 `{version:1}` 与空 payload 的旧 pageReady。可在 capabilities 增加 `pageLifecycleVersion:1`，新网页协商成功后发送小型 `pageLifecycle` 元数据（schemaVersion、受限 stage、webElapsedMs、sequence）；不含任何业务 ID，只有 pageReady 影响揭罩。该类型按已提交文档、精确 origin、主框架、当前 lease 校验；消息频率/数量/数值均有界。旧壳拒绝新类型时网页继续原有 pageReady；协商与诊断失败不能阻塞启动。禁止调用 createBridgeClient.request 等待一个原生不回复的 pageReady；定义通知发送/请求发送的明确差异并测试。

原生时钟与 performance.now 不直接相减；桥记录本端收到事件的时刻并保留网页相对时长。CDP/网络取证用于分解主文档 DNS、连接、TLS、redirect、TTFB、下载；用文档实例关联，不把 onPageStarted 命名为请求开始。增加静态缓存命中/网络等待/读取/写盘/回放分段，仍不扩缓存类型。

图片诊断同时覆盖普通 URL 和官方 `readAttachment → bytes → Blob/data URL → decode/paint`。普通 Resource Timing 不能自动看到 RPC 内部附件请求；必要时用诊断期 CDP 网络/WS 帧时序及浏览器 profiler，避免全局 monkey-patch 官方对象或绕过 readAttachment 自取资源。拿不到某段就写明缺口。本轮交付测量接缝和已复现结果；仅针对证明的自制层瓶颈实施一个可验证优化，若瓶颈在不可修改的官方层则记录证据与限制，不增加重复缓存/CDN/凭据转发。

## 5. 依赖顺序与可验证交付

| 步骤 | 修改位置 | 独立完成条件 |
|---|---|---|
| S0 保护与基线 | 两仓 git diff；现有测量脚本与诊断 artifacts | 记录既有改动、版本、设备/网络、真实已登录一次冷/热路径；先写可失败的路由/时序回归，不做性能根因承诺 |
| S1 认证事实 | hanaccount `auth-session.js`、client entry/生成脚本、store/api、tests | 一个算法/一个实例；scope稳定与轮换、旧session迁移、旧响应/失效/暂时错误、卸载与竞态测试通过；安全语义未变 |
| S2 网页启动 Module | hanui startup/session-adapter/target-store/diagnostics、entry/bundle/tests | 纯行为测试通过：恢复一次、跨scope不读取、pending不清、旧回调不抢、用户导航退休、删除/空白/错误区别 |
| S3 B 页面展示 | hanui slots/private adapter/platform | 实际 bundle 生命周期 fixture覆盖加载界面挂载后pageReady、history与paint区别、认证优先、缺能力撤销；网页无桥仍可用 |
| S4 APP 运行时 | BrowserRuntime、DocumentEpoch/LaunchCover、MainActivity、WebCompatibility/SiteRepository、测试 | WebView与lease归属集中；移除session脚本；Activity只协调系统UI；热启不reload，过期视觉/桥/权限/选择器回调失效 |
| S5 诊断串联 | 两端diagnostics、BridgeProtocol/WebsiteBridge/platform、测量脚本 | 新旧协议矩阵通过；一条命令输出逐次结构化分段、目标正确/是否闪主页 verdict，missing不当0 |
| S6 集成与证据 | APK + 本地/隔离官方Host验证；必要设备实测 | 路由/认证/热冷启/图片复现矩阵，报告瓶颈和单项优化前后；有不足明确标注，不拿匿名curl/单测代替 APP |
| S7 文档收敛 | ARCHITECTURE、PROTOCOL、THREE-MODULE、COMPATIBILITY、两插件README、plans索引 | 文档描述实际完成与降级；旧审核加 superseded 引用而不删除历史；本计划逐项记录完成证据与剩余工作 |

实现允许把 S2/S4 的纯结构提取先于行为切换，以减小差分。不得一次性删除既有保护测试；把源码字符串锁替换为跨同一 Interface 的行为测试，避免同时保留两套真实状态。既有未提交 `DocumentEpoch/LaunchCover` 是起点而非应覆盖的补丁。

## 6. 兼容迁移与风险

- 部署顺序设计为新 hanaccount → 新 hanui → 新 APP；本轮构建/本地验证不等于已获生产发布指令，不自动发布、push 或替换线上。新认证字段对旧客户端无害；新 hanui 遇旧scope能力保守降级；新APP仍接受旧pageReady。新功能需匹配能力，不能承诺任意旧组合无主页闪现。
- 新hanui + 旧APP：旧APP仍可能seed陈旧ID；新hanui不得相信原生/官方旧持久值，在自己的加载界面内按scope和公开动作校正。旧APP抓取ID不再影响新hanui的TargetStore。
- 新APP + 旧hanui：不再提供原生seed，但网页旧记忆仍可恢复；保留旧pageReady。账号作用域保证只对新插件能力成立，交付版本矩阵要写清楚。
- 无作用域旧记忆不导入；可能失去一次自动恢复，不丢真实聊天。重新登录换scope后同样不沿用旧恢复偏好。系统目前无多账号模型，scope隔离不是服务端多租户授权。
- 能力与 DOM 版本失配必须可撤销，不能用“看起来像”的节点判正文。历史open不等于paint；输入可点击不等于认证通过，必须结合认证与官方状态。
- 私有入口大规模挪动易破坏现有通知、触摸、菜单、历史分页：本轮不重新设计这些流程，只验证装配/生命周期不回归。不重写 cache streaming、账号存储、下载或 WebSocket。
- 超时仅产生可重试等待态，不删除目标、不放弃真实网络完成；任何迟到结果带attempt/scope/lease检查。原生展示预算不能停加载。

## 7. 验收与命令

### 架构与行为门槛

1. APP 生产源码没有 DSH session storage 键、sessionId种子或原生聊天状态；权威文档lease只一份。
2. hanui只有一个启动意图Store；官方current始终官方所有。hanaccount客户端实际bundle使用同一认证算法，并提供唯一状态实例。
3. 有效目标：首个业务界面是该目标的加载/内容界面，主页闪现0次，迟到自动抢导航0次；仅current匹配不算正文显示。
4. 目标不存在只由权威列表/移除事实确定；网络失败、列表pending、加载超时不清目标。无目标/已删除进入主页；失效进入登录；临时认证错误可重试。
5. 同origin不同scope、不同origin、注销后重登、旧session升级、快速切会话再杀进程/重开均验证；无scope不自动恢复自有记忆。
6. 热启动复用页面、无loadUrl和新罩；过期bridge/visual/permission/picker回调不能作用于新文档。页面/插件卸载清理所有观察者、定时器及任务。
7. 新旧插件/壳、无桥、不支持Performance API、未知官方能力均不造成不可用或认证降级。
8. 第一轮找出图片具体场景和路径；若仍无复现，报告为待证实且不能宣称图片慢已修复。

### 性能验收

固定设备、版本、网络与同一有效目标，分别记录热启、有静态缓存冷启、首次/新资源冷启，不混算。每次分别输出“目标加载界面显示”“历史ready”“正文paint”“输入可用”“图片显示”。以至少20次有缓存冷启与20次热启作一轮分布（样本不足只报告原始值/中位数，不冒称稳定p95）；首次资源另记。沿用目标：有缓存冷启可交互p95<5s、热启p95<1s，加载骨架时间不能替代内容/输入指标。架构达成但外部瓶颈未消除时如实分别报告，不以改名指标达标。

### 检查命令（实施者按各自目录执行，以下不是已运行结果）

Android 仓：`./gradlew.bat :app:testDebugUnitTest :app:assembleDebug`；有已连设备及相关仪器测试时运行 `:app:connectedDebugAndroidTest`。新增运行时测试要跨实际Coordinator/lease seam，而不是只测试常数字符串。

hanui：`npm run build`、`npm test`、`node --check src/client.js`、`npm pack --dry-run`。新增测试覆盖实际生成bundle装配与卸载，不仅导出helper。

hanaccount：新增生成脚本后 `npm run build`、`npm test`、`node --check src/client.js`、`npm pack --dry-run`；使用既有只读官方source契约fixture：`node scripts/probe-isolated-host.mjs D:/0HAN/Work/deepseek-harness`。它不是完整已登录APP性能测试，不得如此报告；不运行需要模型/密钥的官方demo。

现有设备起点：`powershell -File artifacts/hotstart-diag/measure-hot-vs-cold.ps1 -Serial 127.0.0.1:16384`（先确认设备/包）。此旧脚本最后有 `RED_IF_USER_CALLS_THIS_HOT`，不能作本需求红绿信号：必须扩展为真正断言当前需求的新脚本/兼容参数，输出正确目标、主页可见性和上述阶段，不仅等visualComplete。必要的屏幕录像/CDP提取与结构化trace同次关联；不清用户业务数据去模拟首次。

## 8. 本轮完整范围与后续

本轮必须完成：三层事实与生命周期收拢、保守scope、B恢复/展示、版本降级、可扩展测量、回归与文档；已证实且位于自制层的具体瓶颈才做针对性性能修改。完成报告分开写架构完成度、功能证据、性能证据和真实限制。

后续而非本轮默认范围：官方会话/图片内部优化、CDN、图片压缩/缩略图协议、原生正文缓存、Service Worker/HTML快照、全量MainActivity拆分、所有DOM特征重写、所有插件统一SDK、远程遥测平台、认证多账号系统、静态缓存全量流式重构。任何后续优化应能接在本轮Adapter/diagnostics seam上，而无需再次跨三层寻找状态所有者。

## 9. Sol 本地实施与交付记录（2026-10-07）

保护起点：主仓 MainActivity/BrowserSession/LaunchCoverState 与测试、DocumentEpoch 新文件以及现有文档差分作为基线保留整合；hanui 原有 entry/generated/effects/private-adapters 与 artifact 测试差分保留。hanaccount 起点干净。没有 reset/覆盖工作树、提交、push、版本 bump、生产替换，也没有修改官方 Harness 核心。

| 步骤 | 实际实现与证据 | 状态/界限 |
|---|---|---|
| S0 | 核读工作树与已有4.077/9.598s证据；新增可失败行为回归 | 沿用历史性能证据；没有把旧线上视为新版 B |
| S1 | AuthSession唯一算法；apply独立实例；生成bundle stale检查；scope随机独立/双认证返回/旧session原子补齐；实际bundle reapply/refresh重入/初始错误界面ready | 本地完成；API两身份/补齐写盘失败关闭回归通过 |
| S2 | session-adapter/startup/单目标scope store；官方list/binding.session订阅；终态与auth暂停分离；pending用户导航退休、current=null权威删除、注销幂等clear | 本地完成；11条Coordinator行为回归覆盖关键竞态 |
| S3 | shell.overlay加载页；公共session-scoped composer.dock owner+精确active/composer绘制；AuthOverlay自己挂载后ready；延迟依赖/撤销/缺paint能力有界降级 | 实际生成bundle覆盖成功paint、初始unavailable、延迟服务与撤销；新线上完整路由尚未测 |
| S4 | BrowserRuntime实际拥有WebView/Client/桥/缓存/文档/visual；DocumentEpoch唯一generation与双轴lease；Activity系统UI/分派；退休原生seed/mirror；原生图像结果有界等待与job取消 | APP unit/debug构建及定向本地设备生命周期测试通过；不把恢复onResume当visual |
| S5 | JS可关闭首次阶段采样、navigation timing及network/blob/data首屏图片probe；新旧bridge通知/请求分离；native 128ring/64doc/16sec限额；cache headers/read/write/replay分段；最小测量命令 | 本地完成；未知值null，时钟分列；readAttachment/RPC bytes内部段缺证据 |
| S6 | hanui 91测试通过；hanaccount 147总/141通过/6显式live skip；Android56unit通过/debug构建；MuMu Android15/API35定向BrowserRuntimeDeviceTest 1通过；只读官方source Host fixture通过其契约 | 部分验收；完整新版已登录 APP、20冷/20热、不同origin/scope真实部署与图片场景尚未验收 |
| S7 | ARCHITECTURE/PROTOCOL/THREE-MODULE/COMPATIBILITY、两README、索引、旧审核superseded引用收敛 | 本地完成；文档保留历史且明确降级与未发布 |

最后本地检查命令：两个插件 `npm run build` / `npm test` / `node --check src/client.js` / `npm pack --dry-run`；APP `:app:testDebugUnitTest :app:assembleDebug`；设备只运行 `:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.labteto.dshmobile.browser.BrowserRuntimeDeviceTest`，不是全instrument suite。官方 `node scripts/probe-isolated-host.mjs D:/0HAN/Work/deepseek-harness` 是源码契约fixture；仍报告 `deploymentReady=false`（既有optional Loader失败时暴露fallback），不代表允许发布。

日志产物位于本地被git忽略的 `artifacts/`：`hanui-suite-final.txt`、`hanaccount-suite-final.txt`、两`*-pack.json`、`runtime-gradle-delivery.txt`、`runtime-device-test.txt`、`official-isolated-probe.txt`、`runtime-fixture-trace.txt`、`runtime-fixture-structured.json`。脚本源码在 `scripts/measure-runtime-phases.ps1`。已实际执行 `-TraceFile artifacts/runtime-fixture-trace.txt` 解析设备的纯本地HTML：最后browser/doc相对begin的start72/commit79/ready95/visual113ms；这是本地fixture第2browser的生命周期，不是聊天/冷启性能。history/body/input/image、目标正确和主页闪现证据缺失项均null/unknown，不将其填0。

交付 debug APK 为 `app/build/outputs/apk/debug/app-debug.apk`。插件构建与pack dry-run不等于发布，版本号没有提升。完整新组合B应在部署者提供匹配能力的隔离或授权已登录环境后，再运行相同目标/设备/网络的路由与图片矩阵及至少20冷20热；当前不宣称nginx、图片慢或p95目标已修复。架构接缝已具备后续测量/优化入口，本轮未实施没有证据支撑的缓存/CDN/官方内部优化。
# Deployment follow-up

用户随后授权部署，实际发布与验证见 [DEPLOYMENT-2026-10-07](../archive/DEPLOYMENT-2026-10-07.md)。
正确 dsh.wannian.fun 已更新至 hanaccount 2.4.2 / hanui 0.2.9，MuMu 新装 debug APK。
实际 scope、目标冷恢复与热复用已验证；S6 的完整性能分布、连续 home-flash 和历史图片 bytes/RPC 仍未完成。
三次实际冷启动 inputReady 为 20,049 / 20,800 / 18,689 ms，不能宣称性能达标。

后续启动架构候选与用户确认的新增计划见 [启动架构重构评估](2026-10-07-startup-rearchitecture-assessment.md)：A 已纳入历史图片的本地文件持久缓存，并设计为 B 可复用；这是后续设计范围，不是本轮既有实现。图片由网页数据模块决定身份、授权和版本，App 提供通用私有文件存储；与 JS/CSS 静态缓存分开。第一阶段弹窗、最近对话和首屏图片分别验收，完整会话历史持久缓存仍待独立评估。

