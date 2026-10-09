# A 阶段：hanaccount 与 hanui 更新实施计划

日期：2026-10-07。执行者 Luna；Astra 方案与独立审查。用户已授权两个插件本地实施；本轮不部署、不安装 App、不提交、不发布、不 bump。App 源码和官方 Harness 核心保持不变。

## 1. 目标与基线

交付两项独立增强：hanaccount 在文档引导阶段启动同一认证状态机的第一次请求；hanui 通过现有 App 私有文件能力持久缓存已经授权的历史图片，保留官方图片展示和灯箱。完整会话快照、离线聊天、跨登录账号身份、程序资源 manifest/B 分发不在本轮。

保护现有未提交工作，以 `artifacts/plugin-stage-a-baseline-20261007/{hanaccount,hanui}/baseline-manifest.json` 为本轮差异起点，不能用整个 HEAD diff 判断新增。hanui 遵循兄弟仓库 AGENTS.md；hanaccount 未发现本仓 AGENTS.md。使用 writing-plans 工作流，按可验证交付物组织。

真实依据：

- `artifacts/deploy-20261007/alpha1-contract/dsh-client-ui-conversation/lib/client.js:3451` 的 HistoricalImageCache 是按 binding 的内存 URL 缓存；`seed` 随即 `loadCanonical`，因此 seed 不能避免网络读取。
- 同目录 `lib/types/client/contract/slots.d.ts:97` 的 MessageImageLoader 有 load/peek；OwnerProps 没有 sessionId，不能由全局当前会话猜测。
- 精确 npm `0.2.1-alpha.1` 快照位于 `artifacts/plugin-stage-a-contract-20261007/`。ui-chat `lib/client.js:13149` 声明 `conversation.message.images` 为 session/single；ui-attachment 末尾 apply 注册的图片 entry 仅 component 与 `locale:'conversation'`，没有 inject/store/children；组件消费 images/loadImage/align/compact/thumbnail/t。
- 实际 `ctx.slots` 是 ui-renderer 的 SlotRegistry，不是 ui-slots 的 SlotCore。精确 alpha1 实例探针确认可调用 `entries(key)`、`entriesOfSlot(key)`、`spec(key)`、`subscribe(key,fn)`、`inject(key,callback)`、`register(...)`；`isLive`、`subscribeDeclaration`、`hasEntry`、`specDynamic` 在该服务均为 undefined，不能加入能力门槛或调用。用 `entries(key).includes(entry)` 检查登记身份，声明寿命由 `slots.inject` 拥有。`spec(key)` 返回 `{kind:'single',scope:'session'}`；默认 entry.options 为 `{}`，priority 按 `options.priority ?? 0` 读取，-1 shadow 实测获胜。原组件不是包导出，禁止 require 未导出的 MessageImages。
- alpha1 SessionFace `contract/session.d.ts:90` 的 readAttachment(id) 返回经会话授权的 `{attachment,data:Uint8Array}`；精确 dsh-attachment `types.d.ts:6` 声明图片引用为 immutable normalized image。图片 attachmentId 是 opaque，不假设它是 SHA256。
- host-webserver `lib/types/index.d.ts:27` 的 index-inject 只收表，没有 request；injections.d.ts 支持静态 script row，不能在全局表中放用户认证快照。

## 2. hanaccount：提前执行同一 AuthSession

新增小模块 `src/lib/auth-bootstrap.js` 和确定性生成的静态 bootstrap 文本；沿用 `src/lib/auth-session.js` 唯一分类/并发/撤销算法，不能复制一个精简版认证逻辑。通过受已审计 adapter 版本约束的 `webserver/index-inject` 注入 head classic script row。内容只包含代码、协议版本和固定同源 API 路径，不包含账号、scope、Cookie、token 或服务端按用户计算的 snapshot。不新增第二条认证 HTTP API，不修改现有双身份 gate。

脚本创建一次 AuthSession，立即 `refresh()` 请求现有 `/dsh-local-hanaccount/api/auth/me`，credentials same-origin、cache no-store、5 秒超时、禁止跨 origin 重定向结果被采信。请求工具从现有 api 提取最小共享实现；最终 classify 仍归 AuthSession。以页面内不可枚举的版本化 holder `__HANACCOUNT_BOOTSTRAP_V1__` 提供 `claim()`，只转移一次 session 所有权；holder 不序列化 snapshot、不写 localStorage。响应数据只能来自本页真实 fetch。脚本重复执行不重复请求；pagehide 在未接管时 dispose；接管后由 Cordis 插件 lifecycle dispose。

`client.entry.js` apply 在 holder 协议/来源/寿命正确且尚未被接管时采用同一 session，再提供唯一 `hanaccountAuth`。若脚本被 CSP 禁止、未注入、协议不符、对象已失效或 reapply，创建正常 session 并按旧路径 refresh；不得把缓存 scope 当认证。接管尚在途请求不再发请求；若提前请求已成功且没有失效原因，不立刻重复 refresh。显式刷新与登录状态变更仍触发同一状态机。过期/失败由状态机原有策略处理，不能把 bootstrap 失败当成功或永久锁死。

不要引入新的 parser-blocking 外部脚本请求；优先静态 inline script row。若现有 CSP 不允许运行，保留正常客户端路径并记录 bootstrap 未采用，不放宽 CSP。官方引导顺序须 fixture 验证 script 在大 bundle 前执行。旧官方没有该公开注入能力时只关闭提前执行，不关闭认证 gate。

退出成功前后需要认证所有者明确发出撤销状态：POST logout 成功后，同一 AuthSession 同步清 scope、发布 unauthenticated，退休旧 refresh；之后才页面跳转。新增 `invalidate(reason)` 只表达本地失效，不给认证权限。hanui 收到失效立即撤销显示/读写并发起旧分区 clear；不为等待原生清理无限阻塞退出。失败清理保留有界待清理标签队列供下次桥可用时重试，标签不是授权信息；App 原生退出/换站仍拥有全来源清理。

## 3. hanui：唯一桥客户端与私有文件接口

扩展 `src/platform.js` 现有 createBridgeClient，保留唯一 HanApp.onmessage dispatcher。新增严格白名单 privateFiles 控制消息及 `awaitBinaryAck(transferId,seq)` 内部登记；先登记 `pf:<id>:<seq>` 再 HanPrivateFileWriter.postMessage(ArrayBuffer)，不能另写 onmessage 抢走通知/生命周期回复。

新增 `src/private-files.js`：`createPrivateFilesClient({win,bridge,isCurrent})`，暴露 `probe(),open(bindingLabel),lookup(handle,key),read(handle,key),write(handle,key,blob),remove,clear,release,dispose`。控制面采用 App 已实现协议和字段，不扩 App 方法。所有 await 后校验文档/认证 generation；late reply 只清资源、不发布。

- 首次需要持久图时复用一次能力探针，不在正常启动关键路径阻塞 pageReady。capabilities 无 privateFiles、版本/namespace 不符或缺 ArrayBuffer 则关闭本页持久缓存。
- probe begin 获 probeId/transferId/url；发送 seq0、32 字节 payload 的标准 binary 帧并等 ACK；GET 返回原生随机 32 字节，校验 URL 为精确当前 origin/保留路径，no-store 后 SHA256，将摘要 confirm。以 confirm ready 为可用，不能仅看 feature flag；探针失败本页不反复重试。
- 二进制帧 16 字节 id + 4 字节 BE seq + 4 字节 BE length + ≤256KiB bytes。每 transfer 一帧一 ACK，校验 nextSequence/writtenBytes；网页首版只允许一个落盘 transfer，预留原生第二路空间。终止/超时 abort，禁止 Base64 JSON 大消息。
- 读取先 lookup/openRead，再同源 GET 得 Blob/ArrayBuffer，核长度、允许 MIME 与 lookup.sha256；Blob URL 本页重建，禁止持久化原生 token 或 Blob URL。错误统一 miss，只有当前业务授权仍有效才转官方读取。
- 不支持/旧 App 默认使用官方 loadImage。不向原生发送 session DTO、Cookie、附件 URL 或任意路径。

## 4. 图片入口与责任边界

新增 `src/image-adapter.js`（官方版本与 renderer接缝）、`src/image-cache.js`（业务授权/缓存/URL寿命）。只首批接 `conversation.message.images`；上传预览、trajectory/tool call/queue 图片继续官方流程，不声称全部图片已缓存。

adapter 只在显式已审计 alpha1 profile 与实际能力一致时启用。外层用 `slots.inject(KEY, callback)` 拥有声明寿命，内层用 `slots.subscribe(KEY, reconcile)` 观察登记变化，返回完整清理函数；通过 `entries(KEY)` 检查身份与竞争者，排除自己的 wrapper。要求唯一有效原 entry、locale conversation、component 可作为 React 类型、无 inject/store/children/select、`(options.priority ?? 0) === 0` 与 `slots.spec(KEY)` 的 session/single。精确 loader 不给无 name 的插件补包名，registrant 可能继承 root，仅作诊断，不能作为官方身份门槛。完整 `Function.prototype.toString.call(component)` 的原始 UTF8 SHA256 必须为 `8329badc9491fd5865b6b6c0885dc08caf61d092a4279384c354d6978b5b7a11`（263字符，保留LF/tab），配合版本与结构作为兼容画像，不能仅用函数名。摘要异步返回后再次验证 entry 身份与竞争集合。未知源码或任何额外竞争 entry 均不包装；此画像不替代 AuthSession/binding 业务授权，不防御恶意同源脚本。运行时不调用官方 apply 提取内部组件，不访问 registry._core/hostFace。

以比原 entry 低的唯一 priority 注册 wrapper，并设置相同 locale。利用新 entry 的公开注册参数 `inject(sessionId)` 捕获 `sessions.binding(sessionId)`，传入专用内部属性，不依赖 OwnerProps 自带 sessionId（此参数不同于外层 `slots.inject(KEY,callback)`）。原 entry 仍登记；wrapper 用 React element 渲染该 entry.component，透传原 props/翻译/布局属性，仅替换 loadImage 及 peek；不要直接调用组件函数，或绕过带有 store/inject 的未知组件。每次 render/异步返回验证 `slots.entries(KEY).includes(originalEntry)`、binding identity 和认证 generation，声明撤销/原组件替换/插件卸载立即清 wrapper 与资源。subscribe 回调自触发必须有重入与同身份去重；slots.inject 回调只能返回同步 disposer/其 iterable，不返回 Cordis Fiber 或 Promise。

授权条件为：AuthSession 当前 authenticated 且 scope 合法；精确来源；真实 session-scoped renderer 给出的 durable attachment；仍存活且已经打开的官方 binding，以及当前目录/连接就绪事实。checking/unavailable 时停用私有读取并撤销本插件持有 URL，不把保留旧 scope 当有效认证。不自行打开/retain 历史，不扫描 DOM 推断 session。adapter 必须用 fixture 证明 attachment 来自该 session 的官方 durable owner；不能接受任意外部字符串 ID。

`bindingLabel = SHA256(JSON.stringify(['hanui-images',1,origin,resumeScope,sessionId]))`，每个 session 独立分区便于删除。resumeScope 是每次登录的 opaque scope，不是稳定账号 ID。`key = SHA256(规范化完整 immutable ImageAttachmentRef + schemaVersion)`；固定字段顺序，保留维度/name/originalDimensions 的明确缺省，不能把图片 opaque id 当内容 hash。原生 label/key 均只是命名空间，授权在网页官方 binding 与 AuthSession。

load 路径：

1. 先采用当前官方 loader.peek 已有 URL（借用、不 revoke）；然后本插件同 binding 内的已解码缓存/在途 Promise 去重。
2. 有效授权与持久能力下 lookup/read；命中只创建并返回本插件 Blob URL，**不调用 imageUrl 或 seedImageUrl**，避免后台 canonical 再读。
3. miss 使用精确 binding.session.readAttachment(id) 一次；核 ok、返回 attachment 与请求 immutable tuple 一致、允许 image MIME、data 长度与引用一致且≤32MiB。该官方方法拥有服务端授权。创建 URL 供官方组件显示，落盘异步，不让存储失败拖延显示。
4. 图片接缝/私有能力不支持时直接原 owner.loadImage；已走 readAttachment 的业务拒绝不能再无条件调用另一路造成双请求。网络失败沿官方组件重试交互，下次调用可重试，不能缓存 rejected Promise。

总图片加载并发 2，持久写并发 1、待写至多 1 图且总缓冲有上限（32MiB）；队列满跳过持久化，不能无限排队。只响应实际已挂载图片加载；不提前遍历历史、不声称已有可视区域优先调度。URL/Promise 按 binding 生命周期持有，组件消费者引用归零后允许回收；额外 64MiB 本页 Blob 预算（活跃图超限时仍可显示但不额外保留）。旧 binding dispose、Auth 失效、插件停用撤销 owned URLs/读写；官方借用 URL 不撤销。图片 onLoad 才记 imageShown，缓存命中不代表绘制完成。

当前 session 被已成功同步的目录明确删除才 clear 其分区；loading/error/分页暂缺不能当删除。普通导航只 release handle 保留磁盘，下次同登录同 session 可重新 open。logout/scope 变化清旧会话分区，清理开始先退休本地 generation，晚 write/read 不发布；使用有界本地标签账本（只 schema+digest labels，无 token/scope/raw sessionId）覆盖跨刷新旧分区清理，配额保证不会无限增长。失败清理保持 tombstone，下次打开同标签前先重试 clear；无法清理不复用该缓存。删除附件的精确事实缺失时不据 DOM 消失清文件，随 session 删除/退出或原生 LRU 回收。

## 5. 实施步骤与验收

| 顺序 | 文件/交付 | 必须证据 |
|---|---|---|
| P0 | 对照冻结基线、精确 alpha1 包，建立 source-contract fixture | 真实 registry 注入 sessionId、原 renderer locale/priority/卸载行为；若接缝不成立仅关闭图片 adapter，不改核心 |
| P1 | hanaccount bootstrap/request helper、AuthSession invalidate、index lifecycle、bundle生成 | 提前请求与 apply 接管合计一次；失败/超时/CSP缺失/重复脚本/reapply/logout竞态；两页不同身份不串数据；index表无任何用户快照 |
| P2 | hanui platform 单 dispatcher + private-files | JSON+二进制 ACK 同时与通知并行；ACK即时下一帧、错ID/序号/长度、超时、dispose、probe各阶段失败；无 Base64 与越权 URL |
| P3 | image-cache 纯业务模块 | miss readAttachment 一次→异步落盘→新文档同 scope命中零readAttachment；错scope/session/ref、旧binding/注销晚回调、损坏摘要/MIME、32MiB/quota、队列上限 |
| P4 | image-adapter 与 client.entry 真实装配 | 真实 alpha1 slots + 原 MessageImages fixture 保留缩略图/灯箱/翻译/预览；缺 entry、竞争 entry、动态替换、原组件卸载、旧App/旧hanaccount不黑屏；不调用 seed |
| P5 | 两插件生成 bundle、README/协议说明、独立审查 | 本轮仅新增差异；完整既有测试+新增高价值竞态测试，bundle一致性、语法检查、pack dry-run；App源码哈希不变 |

测试应含真实 Cordis 注入生命周期，不用“返回 Fiber 等同 disposer”的假实现。renderer 关键测试必须使用精确 npm alpha1 的实际 slot registry/组件装配；普通 React 仿真只用于确定性状态机，不替代所有真实集成。包在测试中不访问生产；fixture来源及hash记录 artifacts，避免测试依赖用户机器偶然存在的最新官方源码。

两个仓库执行 `npm run build`、`npm test`、`node --check src/client.js`、`npm pack --dry-run`。必要新增 bootstrap 生成文件加入包files与build --check，不新增运行时第三方依赖。实际命令以各仓现有 scripts 为准。保留旧测试，不为通过测试篡改官方契约。

兼容矩阵：新两插件+旧App/普通浏览器正常官方图；新hanui+旧hanaccount缺scope不持久化；新hanaccount+旧hanui认证保持正常；新App+新两插件通过probe才持久化；未知官方版本/第三方renderer保持官方图。两插件可独立降级，不让持久缓存或bootstrap成为访问页面的新硬依赖。

本轮可完成本地契约、源代码、生成bundle与测试；真实 WebView probe/CSP/SW、同登录跨 App 重启图片命中、注销与进程关闭清理、真实首阶段时间仍需要后续获授权设备/部署验收。不能把单元 fixture 通过称为线上缓存生效，也不能把提前显示主页/静态弹窗当第一阶段提速。启动终点仍为实际官方预览说明弹窗，最近会话绘制另计；没有新实测不得宣称 7.4 秒已降至 5 秒。

## 6. 当前实施记录（本地代码阶段完成）

P1–P5 插件代码阶段已完成。hanaccount bootstrap 与唯一 AuthSession 接棒、注销失效和 late-response 竞态已实现。hanui 复用唯一 HanApp dispatcher，增加 App v1 private-files 客户端与实际 alpha1 图片 slot adapter。持久能力只在图片实际加载时探测；授权门槛要求合法 resumeScope、open binding、ready catalog 的 ids membership 与 `removed === false`、connected connection generation（alpha1 generation id 为 number）。普通 catalog 通知不重载图片，断线/代际更替退休旧读取，明确删除才清对应摘要分区。未知/不支持能力与超出缓存限制回退官方 image loader。

缓存以 `hanui-images` 业务 schema 摘要生成分区标签；native 请求字段采用 `private-cache-v1`。标签不授予认证权限。实现覆盖同 session 多消费者 URL 生命周期、同 attachment/同 lease 请求合并、两路读取上限、共享 opening handle 取消竞态、同源 GET 超时/长度/hash检查、写入/ledger失败、清理 tombstone 和删除事实门槛。版本不匹配、连接/目录不确定、缺少有效旧 hanaccount scope 时保留官方图像流程。没有完整历史快照或首屏加速承诺。

最终本地检查：hanaccount `npm test` 151 项，145 通过、0 失败、6 项明确跳过 live 测试；hanui `npm test` 127 项全部通过。两仓 `npm run build`、bundle 检查、`node --check src/client.js` 与 `npm pack --dry-run --json` 均通过。实际 auth bundle + LoginGate + alpha1 Cordis/SlotRegistry handoff fixture 通过；实际生成的 hanui bundle + alpha1 Cordis/SlotRegistry/React renderer image-owner fixture 通过，证明无图片时不探测私有存储、图片挂载后才使用 session 授权读取与分区 lookup；额外图片 adapter fixture 覆盖真实 alpha1 MessageImages 组件、catalog/connection/删除、renderer preview/lightbox 及 URL 生命周期。测试未访问生产站点。

仍待设备验收：AndroidX ArrayBuffer WebMessage、保留路径 GET 与 Service Worker/CSP 的真实 WebView 行为、跨进程重启命中、真实注销和存储清理，以及获授权设备上的启动/图片计时。当前未部署、未安装/运行 App、未提交、未发布或 bump；App 源码未修改。测试通过不等于线上图片缓存已生效，也不证明 7.4 秒降至 5 秒。

Astra 最终独立复审通过，所列 P1/P2 问题全部关闭。最后的读取竞态修复先检查操作是否退役，再判断文件损坏；仅明确 `PRIVATE_CORRUPT` 删除文件，认证暂不可用、超时或读取凭证失效不会误删缓存。回归测试确保第二次读取实际进入等待后才改变认证；隔离的负向验证恢复旧行为后该测试按预期失败，生产源码未被负向验证修改。最终证据与源码哈希见 `artifacts/plugin-stage-a-final-20261007/`，替代此前未完成 checkpoint。
