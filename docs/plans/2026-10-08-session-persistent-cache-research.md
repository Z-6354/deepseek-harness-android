# 会话历史持久缓存与增量同步研究方案

2026-10-08。Sol 代码与一手资料研究稿；本轮仅提供可审核方案，没有实施、部署或真机验收。范围是每个打开过的会话已加载历史，覆盖切换返回和关闭进程重启；不包含 HTML、欢迎弹窗或脚本批次优化。按用户后续要求修订为五类职责，优先通过一个新增插件承接缓存与同步，不默认将新增协议写进官方核心。

## 结论与决策边界

**静态理论判定：只增加一个Host+Client插件，并调整profile组合，可以满足磁盘缓存与仅差量网络的要求，不需要修改官方源码或官方已发布文件。** 推荐插件用公开官方Host/Client入口作为子插件，保留官方会话owner和renderer，只给官方Client隔离注入自己的兼容`remote.session`服务；其follow/page把IDB数据与新差量接口组装成官方既有帧。因此无需新增官方hydrate接口，也无需重写整个ISessions。下面给出完整公开调用链，不把此判定写成“等待实践证明能力是否存在”。功能尚未编码，测试仍用于验证实现符合这些已确认接口。

未增加插件的现有部署不能满足完整要求：官方follow仍发送最近窗口，且无hydrate接口。此前“所以必须改官方Client”判断范围过窄，遗漏了公开服务隔离与包装现有apply路径；该结论由本次静态核查修正。新的方案复用官方installWindow路径接收兼容snapshot，不直接写readonly Binding、eventSource或private Session。读取/调用未修改的官方npm公开入口允许，编辑官方源码、替换其已发布文件字节或monkeypatch方法均不属于本方案。

收益可以验证为减少已有历史的重复传输、切换后的重复等待；不预估节省秒数。网页引导、认证、首次联网、解析和绘制仍需独立计时。

## 零官方修改的静态构造

1. profile禁用原先顶层`@deepseek-ai/dsh-api-session-controller`Loader row，只装载新增插件的顶层row。插件Host通过公开`ctx.plugin(SessionController, config)`挂载官方Host class，保留原sessionController/官方命令；使用公开`./typert`贡献`TYPERT`和`./remote`贡献保证原namespace按原生成schema注册，另注册自己的sessionCacheSync namespace。原官方包与字节均保留，子fiber不是独立Loader row。
2. alpha1 ClientModuleRegistry.processOne只扫描`ctx.loader.entries()`的active entry.options.name，不把普通`ctx.plugin`子fiber转换为独立package row。因此boot graph只有新增插件row，不自动启动一份未隔离的官方Session Client。UI的package inject边在对应row不存在时跳过，实际服务inject仍等待`sessions`等真正能力；conversation/chat的runtime不要求官方Session构造器身份。
3. 新增插件Client发布物附带原官方client.js注册factory原字节；`dsh.client.immediately=true`仅作stage-one预取，不保证apply先后，也不把新插件变成Host固定bootstrap batch。插件自己的dsh.client依赖仍列出原apply所需Gateway/fileUpload等包。原factory在新增插件的文件中先登记，新插件factory随后通过公开`require`参数物化原官方`/client`导出的apply/inject；登记不自动执行apply，也不创建原包graph插件row。新增插件明确先装隔离namespace，再由`ctx.plugin`挂载官方apply；此顺序由自己的apply代码控制，不靠预取标记。不修改原包文件，不给官方factory换名或替换其导出。
4. Gateway根scope仍提供原`remote.session`和完整`remote`服务。插件保存根scope的原namespace，在`Context.isolate('remote.session', label)`创建的子scope提供自己的兼容namespace，再在同一子scope挂载官方Client apply。namespace拥有自己的follow/page实现，其余session方法委托根namespace；`remote`、`sessions`、`typert`、`connection`不隔离。官方Client仍向原共享scope提供原ISessions与agent context adapter，所有官方UI获得同一个owner。适配face归新插件所有，不改Gateway根对象；监听与stream的disposer归挂载子fiber，遵守公开effect生命周期。
5. 精确Cordis Service tracker的associate机制把`ctx.remote.session`解析为调用方scope的`remote.session`。官方apply的`const remotes=ctx.remote`传入ClientSessions；后续SessionEventStream.follow/page调用插件namespace，官方inject也在同一scope取得它。`$stream`保持原Gateway监督器；它执行open回调拿到兼容follow异步流，不强制该流必须来自原Host endpoint。wrapper只实现follow/page缓存路径，list/control/prompt/rename/attachment等原样委托根namespace。
6. follow先验证认证与新差量协议，读取IDB连续已缓存记录、仅从新namespace取得缺失/变化，然后返回**一份**符合alpha1 SessionFollowFrame的snapshot（本地records与差量组成完整[L,S]窗口、正确cursor/hasMore/header/projections/assistant baseline），再保持原混合顺序交付S后的事件。官方原SessionEventStream→RemoteJournalStream→acceptEventChange→installWindow→eventSource→uiConversation照常运行。page优先从已缓存连续范围返回官方SessionPage；缺页才请求新接口。组装完整窗口发生在本地，不能误计成网络全量下载。

这里不承诺“网络同步尚未完成就先展示历史”：alpha1官方owner在兼容snapshot到达后展示；用户硬要求的“磁盘可复用、无变化不重复传历史、有变化只传变化”已由上述链满足。先同步metadata/差量再给官方一份snapshot，不需要额外cached openState或hydrate方法。插件状态可由hanui单独展示，官方输入继续按真实owner状态启用。

Host不需要自行访问private assistant accumulator：插件同进程调用公开`ctx.sessionController.follow(request, signal)`，从第一次官方snapshot取得header/cursor S/projections/assistant baseline，丢弃其records，**这些旧窗口records不发送到浏览器**。使用sessionQuery的immutable观察按S裁切，产生C+1..S差量；已有官方follow随后交付的durable event/assistant帧按原mixed顺序继续转发。原follow还保留官方Address校验与Agent activation语义，插件仍额外验证当前准入/业务可见性。这里只是服务端取元数据时内部计算最近窗口，网络只发送差量，不是浏览器全量后diff。

同一generation只能给官方journal**一个opening snapshot**。禁止先发旧缓存snapshot再发current snapshot，RemoteJournalStream会拒绝第二个opened/snapshot。因此缓存+catch-up在插件本地先合并成S，再开官方展示；之后只发连续事件，重连创建新generation。raw durable入盘/cursor事务、assistant mixed顺序与旧页保留继续遵守后述约束。

| 已确认的代码关系 | 精确alpha1位置 | 判定 |
|---|---|---|
| Host class与Client apply为公开export | Session Controller lib/index.js末尾；lib/client.js末尾；package.json的`./client`、`./typert`、`./remote` | 可由新插件正常组合，禁止改原包 |
| 只扫描Loader rows | client-modules lib/index.js processOne/resolveSource，约832行 | Host wrapper子fiber不会造成独立官方Client自动激活 |
| factory注册不激活插件 | client-modules lib/client.js materialize/makeRequire，约675/708行；Host compose的PARSER_PRELOAD_IDS | 原factory在new plugin文件先登记，factory通过公开require调用apply；无原graph row不自动挂载。immediately仅预取 |
| Remote namespace按caller scope解析 | cordis lib/types/context.d.ts Context.isolate、reflect.provide public face；lib/index.js createTraceable约130行与Service constructor约1775行 | associate=remote在caller ctx读取remote.session；插件隔离provide自己的namespace，不改根Gateway对象 |
| Remote history只消费异步流/page返回值 | alpha1 Session transport.js SessionEventStream.follow/readPage；Gateway RemoteStream/Journaling公开代码 | compatible follow/page足够让原owner正常装配，不需要访问private Session |
| renderer要求同owner，不要求官方class instanceof | uiConversation lib/client.js:3808；ui-chat apply/chatSource | 原owner保留，所以严格owner身份检查自然满足 |

上述是确定的组合能力。实现仍必须写兼容帧转换、日志代际、差量/分页、身份、活动stream和事务代码，测试验证其正确性；这些是正常工程工作，不是“能否从插件触达”的未知能力。整体独立实现ISessions/SessionFace同样不存在UI类身份障碍，但没有必要承担这套重复实现成本，故本方案采用公开apply+兼容服务包装。

## 五类职责与修改小点

阶段：A认证与分区；B读取磁盘和恢复历史；C差量补齐/活动流接棒；D向后分页；E退出、清理和设备验收。公开组合能力已经静态确认；表中测试是实现验收，不是是否能触达的能力探测。

| 类别 | 修改小点 | 阶段 | 预期收益 | 是否需要改官方源码 / 待验证 |
|---|---|---|---|---|
| **hanui插件** | 订阅新增插件cached-readable/syncing/current/persist-failed状态并显示合适移动反馈 | B/C | 缓存可读与同步完成分开，失败不伪报命中 | 否；只使用新插件公开服务 |
| hanui插件 | 将当前已选择SessionAddress和真实绘制诊断交给新插件，不直接写官方Binding | B/E | 关联切换返回的真实体验与网络测量 | 否；选择能力沿既有适配，缓存不复制导航逻辑 |
| hanui插件 | 独立历史/输入/同步时间记录 | E | 能确认收益来自缓存，而不是终点变化 | 否 |
| **App** | IDB事务提交后force-stop读回探针及普通关闭路径审计 | B/E | 证明数据在磁盘，排除只存内存 | 否；壳已有稳定存储目录，正常路径可能只需验收 |
| App | 对接现有明确清浏览数据流程的持久缓存清理验收 | E | 用户清理确实删除，普通退出不误删 | 否；仅确有缺口才补通用生命周期，不新增DSH RPC/DTO |
| **hanaccount插件** | 向新插件公开认证scope与撤销生命周期（现有客户端auth服务可复用，Host公开face需核验） | A/E | 同登录重启复用、账号退出立即退休旧读写 | 否；不把历史业务塞进认证插件 |
| hanaccount插件 | 核验新Remote namespace仍经过现有Gateway peer准入；必要时补通用授权公开服务 | A/C | 新接口不绕过现有保护，不保存cookie/token作缓存键 | 否；独立HTTP路由不可默认继承Gateway授权 |
| **新增插件** | 同一个包提供Host入口、Client入口与生成Typert host/client贡献，包装未修改官方公开入口 | A/C | 一个插件集中维护缓存与同步协议，不改官方owner | 否；alpha1公开包、Loader扫描及Cordis隔离组成完整路径 |
| 新增插件Host | 注册独立 `sessionCacheSync` namespace，baseline/resume/changes/page/capabilities | C/D | 不改原session.follow也可只传未缓存/变化records | 否；必须按生成schema/注册/dispose流程，不能只标Remote就声称加载成功 |
| 新增插件Host | 通过公开sessionQuery.observeSession读取immutable cut，监听正式session/event与assistant-stream | C | 查询+订阅形成不漏gap的差量源 | 否；需精确Address/可见性校验和subscribe-cut fixture |
| 新增插件Host | 插件自有持久log identity/token、覆盖证明及变更元数据 | C | 跨Host正常重启复用；区分迁移/重建/原地变化 | 否；插件维护自己的磁盘元数据及覆盖hash/mapping，按后述原子规则实现 |
| 新增插件Host | 同进程调用公开sessionController.follow取得权威cut/投影/活动assistant基线，丢弃其历史records | C | 复用官方活动流语义，网络不重发旧历史 | 否；公开follow足够，不需读private accumulator |
| 新增插件Client | IndexedDB records/meta/coverage/token同事务写读 | B/C/D | A→B→A和杀进程都复用每个已读会话 | 否；不依赖ArrayBuffer桥 |
| 新增插件Client | 包装公开官方apply，隔离兼容Remote face；follow单snapshot/page缓存优先 | B/C/D | 原官方owner与renderer正常消费磁盘+差量，不复制聊天UI | 否；公开服务组合已静态闭合 |
| 新增插件Client | 身份/注销/晚回/配额/代际/多写者治理 | A/E | 不串数据、cursor不越缺口、清理不复活 | 否；与hanaccount/App公开生命周期衔接 |
| **官方源代码** | **不修改；官方npm文件也不修改** | 全阶段 | 避免核心fork，复用原owner/renderer/发送与follow状态语义 | 本次推荐路线零官方修改；新增Provider接口不再是前置 |

优先次序是按已确认公开组合实现新插件包装/兼容face → 新差量服务与IDB → 帧、身份与生命周期测试 → 用户场景闭环。无需先研究官方新接缝；不能交第三插件自己的缓存页面，称官方会话已命中。

## 新增一个插件的可行范围与零核心边界

新增证据来自官方npm的精确`0.2.1-alpha.1`发布包，保存于 [插件扩展证据目录](../../artifacts/session-cache-extension-alpha1-20261008/package-hashes.json)，不是安装或升级生产依赖。没有执行package scripts。新增Gateway包用于验证$stream/namespace，原有alpha1 Session/渲染材料继续作为接入依据。

| 公共扩展点 | 精确alpha1证据 | 对新增插件的含义 |
|---|---|---|
| Host+Client同包 | [Session Controller package.json](../../artifacts/deploy-20261007/alpha1-contract/dsh-api-session-controller/package.json) 有Host main、./client、dsh.client、Host/remote-client生成文件 | 一个插件可有两面，不需要为Host/Client拆成两个插件；公开组合已静态确认 |
| 独立Remote namespace | [Typert protocol](../../artifacts/session-cache-extension-alpha1-20261008/deepseek-ai-dsh-typert-protocol-0.2.1-alpha.1/package/lib/types/index.d.ts) 的TypertRemoteService、bindTypertRemote；[README](../../artifacts/session-cache-extension-alpha1-20261008/deepseek-ai-dsh-typert-protocol-0.2.1-alpha.1/package/README.md) | 可注册不同namespace并使用Remote stream与ctx.invocation.peer；不占用/替换官方session endpoint |
| 生成贡献注册 | [Typert registry service](../../artifacts/session-cache-extension-alpha1-20261008/deepseek-ai-dsh-typert-registry-0.2.1-alpha.1/package/lib/types/service.d.ts)、[贡献类型](../../artifacts/session-cache-extension-alpha1-20261008/deepseek-ai-dsh-typert-registry-0.2.1-alpha.1/package/lib/types/types.d.ts) | host/client schema/model/invocations贡献可整体注册撤销；构建时生成，不依赖source-json宽松回退 |
| 公共日志观察 | [SessionQuery face](../../artifacts/session-cache-extension-alpha1-20261008/deepseek-ai-dsh-session-query-0.2.1-alpha.1/package/lib/types/index.d.ts)、[Observation](../../artifacts/session-cache-extension-alpha1-20261008/deepseek-ai-dsh-session-query-0.2.1-alpha.1/package/lib/types/observation.d.ts) | observeSession给immutable raw log/cursor/header/projections；cold revision可用作来源变化提示，不是稳定epoch。返回lease须dispose |
| 公共投影 | [SessionProjectionRegistry](../../artifacts/session-cache-extension-alpha1-20261008/deepseek-ai-dsh-session-projection-0.2.1-alpha.1/package/lib/types/index.d.ts) | 可读snapshot、订阅变化、注册plugin projection；projection水位不能取代历史同一性 |
| Client公开source与scope | [Session Client exports](../../artifacts/deploy-20261007/alpha1-contract/dsh-api-session-controller/lib/types/client/index.d.ts) | MutableSessionEventSource/createScope是公开export，但Session构造器只export type，没有公开创建/替换当前Client Session owner接口 |
| 官方renderer绑定限制 | [UiConversation alpha1 bundle](../../artifacts/deploy-20261007/alpha1-contract/dsh-client-ui-conversation/lib/client.js) 的binding(owner)校验 | 要求sessions.binding(id)===owner；插件自有source不能任意注入现有readonly Binding。公开source export不等于history Provider注册口 |

**只靠插件可做完整路线：** 新差量namespace、Host日志观察/插件元数据、IDB持久库、公开follow取得活动基线，以及隔离兼容face让原官方owner消费本地组装的snapshot。**不能做的是直接hydrate当前Binding**，但推荐路线不使用该操作，所以不是目标阻碍。

独立namespace不自动获得业务授权：Gateway peer准入只证明连接已被现有gate接纳；sessionQuery是Host日志读取能力，不是逐会话/子会话访问授权器。新插件必须逐请求校验当前准入、当前scope、完整Address、官方可见性与父子关系，并对权限撤销停止流。若现有公共服务不足以表达官方同等可见性，先要求通用只读授权face或缩小支持范围，不能沿任意sessionId暴露observeSession数据。优先使用既有Gateway Remote carrier；若退到独立HTTP路由，显式复用hanaccount公开校验，不能假设webServer.register继承认证。

独立delta接口可以在Host读取完整日志后只传差量，用户限制的是客户端重复全量下载，因此不等于违规全量后客户端diff；服务器读取/恢复整个日志、prefix/覆盖hash和reducer仍可能O(n)，测试应量CPU/IO，不能许诺节省秒数。插件维护durable identity并核验覆盖hash/mapping，不能只随机epoch或使用cold revision；活动流初始baseline和subagent address直接通过公开官方follow保留官方语义，不另写private accumulator。

**完整替换owner也可理论构造，但不推荐：** 公开ISessions/SessionFace是结构接口，renderer只检查当前binding身份；新插件独立实现全部方法、scope、引用计数、list/control、prompt回执等能成为owner。没有不可达的官方class身份门槛，但工作量显著高于本次公开apply+兼容face路径。推荐路线既不替换owner也不另造聊天界面。

**官方Provider新接口仅是未选替代：** 未来可用更小显式Provider简化插件装配，但不是实现本次要求的必要条件，不在本次推荐实施范围内。不得再用“官方无hydrate接口”推导“插件方案不可能”。

## 精确版本证据

以下 alpha1 证据来自本仓库发布包展开文件，`package.json` 标记 `0.2.1-alpha.1`，不是把本地新源码当线上接口。未访问生产读取业务历史，仍须实施前核对部署包指纹和实际握手。

| 核对点 | 代码证据 | 能力与限制 |
|---|---|---|
| 历史传输 | [alpha1 types.d.ts](../../artifacts/deploy-20261007/alpha1-contract/dsh-api-session-controller/lib/types/types.d.ts) 的 `SessionFollowRequest`、`SessionPageRequest`、`SessionFollowFrame` | `follow` 只有 address、maxMessages、turnWindow、assistantStream；没有 afterSeq/since/持久恢复令牌。`page` 支持 beforeSeq、throughSeq，是向后读取更早历史，不是变更接口 |
| 每次打开行为 | [alpha1 history.js](../../artifacts/deploy-20261007/alpha1-contract/dsh-api-session-controller/lib/types/history.js) 的 `follow()` | 每次先分页最近历史，发 snapshot（records、cursor、hasMore、projections、可选活动流），再跟随新事件。不是全会话全量，但仍重复传近期窗口 |
| 客户端 journal | [alpha1 transport.js](../../artifacts/deploy-20261007/alpha1-contract/dsh-api-session-controller/lib/types/client/transport.js) | 承接 Gateway RemoteJournalStream；repairRequest 只转发分页选项。不能从浏览器私加 afterSeq 就获得服务端差量 |
| 权威恢复位置 | [alpha1 session.js](../../artifacts/deploy-20261007/alpha1-contract/dsh-api-session-controller/lib/types/client/sessions/session.js) 的 doOpen/installWindow/prependWindow | installWindow 同时维护 baseSeq、hasMore、事件源、projection、活动 assistant 和输入回执。不能只改 DOM 或 SessionSnapshot 冒充历史恢复 |
| 真实渲染接缝 | [alpha1 sessions service.js](../../artifacts/deploy-20261007/alpha1-contract/dsh-api-session-controller/lib/types/client/sessions/service.js) 的 ensureScope；[alpha1 ui-chat bundle](../../artifacts/plugin-stage-a-contract-20261007/deepseek-ai-dsh-client-ui-chat-0.2.1-alpha.1/package/lib/client.js) 的 chatSource | SessionBinding 带 eventSource，官方 uiConversation.binding(binding).target('chat') 形成聊天视图。恢复应通过该链，不另建一套聊天 UI |
| 公共接口缺口 | [alpha1 session face](../../artifacts/deploy-20261007/alpha1-contract/dsh-api-session-controller/lib/types/client/contract/session.d.ts)、[sessions face](../../artifacts/deploy-20261007/alpha1-contract/dsh-api-session-controller/lib/types/client/contract/sessions.d.ts) | 有 retain、loadOlder 等，但无 hydrate/cache provider。Binding 是只读事件源；不向 readonly 强写或包装私有方法 |
| 切换重新加载的机制 | 同上 sessions face 的 SessionReference.release、service.js 的 retainScope/dropScope | 最后一个 reference 释放后 teardown。不能拿 session.js 顶部历史注释“resident”否定真实引用计数，也不能永久 retain 所有会话替代落盘 |
| 修改与删除语义 | alpha1 types.d.ts 的 SessionWireSurfaceOp、SessionWireEvent，以及 typert.host.js | wire 已有 append / replace、sourceEventSeqs；“surface 替换”是新日志事件表达的展示变化。未确认通用的历史原地修改、物理删除、会话永久删除差量协议 |
| 三种水位不可混用 | alpha1 types.d.ts 的 SessionProjectionHints；[events face](../../artifacts/deploy-20261007/alpha1-contract/dsh-api-session-controller/lib/types/client/contract/events.d.ts) | eventWindow.revision 是本地发布次数；assistant revision 是进程内活动流版本；projection asOfSeq 有 cached/sequenced 语义。都不能直接充当跨重启历史同步令牌 |

本地 [官方源码 history.ts](D:/0HAN/Work/deepseek-harness/packages/api/session-controller/src/history.ts)、[Session 类型](D:/0HAN/Work/deepseek-harness/packages/core/session/src/types.ts)、[格式迁移测试](D:/0HAN/Work/deepseek-harness/packages/session/session-format-catalog/tests/catalog.spec.ts) 只作为设计参考：追加日志可以表达 surface 替换，格式迁移可能插入事件、改变 seq。不能据此宣称 alpha1 已支持 durable epoch、消息删改同步或未来接口。

## 存储选择

| 选择 | 杀进程后保留 | 本项目适配成本 | 判断 |
|---|---|---|---|
| 内存 Map / 长期 retain | 否 | 小 | 不满足需求，且绑定继续占资源 |
| localStorage | 通常保留 | 小，但缺少多记录事务和适合历史的索引读取 | 只适合小偏好，不存聊天正文集合 |
| IndexedDB | 本地持久数据库；已提交数据可跨页面、进程读取 | 网页可以直接使用；支持事务和按 seq 索引 | 推荐主方案；需要设备读写/关闭进程探针、配额及清理测试 |
| Android 内部 SQLite + 通用桥 | 数据库可跨进程 | 需设计有界 JSON 分片、事务、权限、清理和两端协议；壳不可持有 DSH 业务协议 | 若未来要求摆脱浏览器自动淘汰，再独立评估通用 opaque storage 服务；本轮不选 |
| 当前私有图片文件桥 | 有图片落盘能力 | 依赖二进制桥探针，不是事件事务数据库 | 不作为消息唯一存储，不受此次模拟器 ArrayBuffer 限制牵连 |

IndexedDB 标准提供多记录事务与原子更新，事务完成事件是提交边界；支持时为 cursor 与相应 records 的提交请求 strict durability，它仍是浏览器提示，不能承诺硬断电绝不丢失。不能跨网络 await 持有事务，也不能等 pagehide 才落盘。参考 [IndexedDB 3.0](https://www.w3.org/TR/IndexedDB/)、[IndexedDB 2.0](https://www.w3.org/TR/IndexedDB-2/)。

“落盘”不等于“永不清理”：默认浏览器存储可能在压力下淘汰，persist() 的授权和支持必须检测，clear browsing data、卸载、用户清数据也会删除。普通进程退出不应走业务清理，已提交的数据要能再次读取。参考 [WHATWG Storage](https://storage.spec.whatwg.org/)。若 persist 不可用/返回 false，仍可提供普通重启持久缓存，但明确报告存储状态；不能把内存回退称为持久成功。

壳 [BrowserRuntime.kt](../../app/src/shell/java/com/labteto/dshmobile/browser/BrowserRuntime.kt) 已启用 JS/domStorage；[BrowserStorage.kt](../../app/src/shell/java/com/labteto/dshmobile/browser/BrowserStorage.kt) 保存稳定 data-directory suffix；[BrowserEnvironment.kt](../../app/src/shell/java/com/labteto/dshmobile/browser/BrowserEnvironment.kt) 在明确 cleanup 路径调用 deleteBrowsingData。它们支持开展 IDB 探针，但不是 IDB 真机验收。setDatabaseEnabled 是旧 WebSQL 开关，不应拿它解释 IDB；参见 [Android WebSettings](https://developer.android.com/reference/android/webkit/WebSettings)。原生替代存储要用内部持久目录/数据库，不能用可能自动删除的 cacheDir，参考 [Android app-specific storage](https://developer.android.com/training/data-storage/app-specific)、[SQLite atomic commit](https://www.sqlite.org/atomiccommit.html)。

## 推荐数据与职责

新增插件内部的SessionHistoryCache与兼容Remote namespace实现IDB、新差量transport和持久同步元数据；官方Session Controller仍按原follow/page帧消费数据。hanui负责移动状态/诊断，hanaccount提供认证scope生命周期。官方owner保持权威事件校验与渲染状态，插件负责数据取得、磁盘事务、token与差量。Android不发DSH RPC、不解释session DTO。Host元数据存插件自己的持久命名空间，不修改官方Session日志/格式，不能以插件内存Map代替。

插件内部Cache方法为readWindow、commitRange、commitDelta、invalidate、clearPartition；传入已校验历史记录、覆盖范围、同步令牌和当前写入generation，不传Cookie/认证token。resumeToken是非认证同步元数据，按事务保存。它们由新增插件自己定义，不新增官方API。开关、容量和批量提交策略可配置，不靠浏览器对象monkeypatch形成持久接口。

| 持久集合 | 保存内容 | 关键约束 |
|---|---|---|
| partitions | origin + 认证 resumeScope + 数据格式版本；退出/清理 generation | scope 是隔离标记，不是授权证书，不保存凭据 |
| sessionMeta | 完整 SessionAddress、header、server log identity/epoch、durable cursor、覆盖区间、hasMore、同步状态 | cursor 属于已提交的连续尾段，不能仅等于见到的最大 seq |
| records | 官方原始 durable wire records，按 partition/address/epoch/seq 编址 | 保留 type、seq、time、data、surfaceOp、sourceEventSeqs、ignorable；不只保存 message.text |
| optional viewState | 可信的滚动锚点等局部状态 | 只是视图偏好，不替代记录与同步令牌 |

所有记录与对应覆盖范围/cursor 在同一 IDB 事务提交。页范围例如 [1200,1800] 连续，不表示 [0,1199] 已加载。首次仅缓存实际取得的窗口，用户翻页后扩展范围。显示可限制活动窗口尺寸，磁盘仍保留打开过且已取得的历史；容量不足应反馈，不能悄悄清完仍宣称所有已加载页可复用。需配置配额和显式清理策略，不无界预取整个历史。

当前 hanaccount [store.js](../../artifacts/plugin-stage-a-checkpoint-20261007/hanaccount/src/lib/store.js) 每次 issueSession 随机生成 resumeScope，[auth-session.js](../../artifacts/plugin-stage-a-checkpoint-20261007/hanaccount/src/lib/auth-session.js) 从 /auth/me 接受它。因此同登录状态的进程重启可以保持分区；重新登录的跨 scope 缓存复用不在首版承诺内。恢复前确认当前认证 scope；401/403/注销或身份变化立即撤下旧数据并退休旧写入。网络暂不可用不同于注销，不误删缓存；完全离线跨重启授权策略需另定，本方案不承诺绕过认证显示旧身份历史。

## 真增量协议：必须新增的能力

新增插件自己的sessionCacheSync namespace实现首次baseline/缓存resume；插件兼容face转换为官方follow/page已有schema。原根session namespace业务保持不变。以下是插件新协议，实施时正式生成schema和capability version，不在alpha1原follow盲发新字段。Host独立路由与兼容face都要实现，不能只交Host接口。

| 请求/响应 | 必要内容 | 目的 |
|---|---|---|
| 首次 baseline | address、窗口选项 → records、coverage、cursor、header、projections、assistant baseline、resumeToken | 获取当前窗口和可恢复 token，首次数据写盘 |
| resume | address + resumeToken（绑定 log identity/epoch/格式 + committed seq/连续范围） | 服务端确认当前用户权限和前缀有效性 |
| unchanged / resumed opening | authoritative cut S、身份/epoch、必要 control/projection/assistant baseline；历史 records 为空或只含 C+1..S | 无变化不传历史正文；控制快照可继续传，不伪称零网络 |
| 分段 changes | after C、through S，分页上限按事件/字节，返回连续 records 与续页令牌 | 有变化只传新增日志事件，包括表达替换/删改的事件；避免巨量差量失控 |
| live follow | 初始 cut 之后连续事件；服务端先订阅、再取 cut，去重缓冲 | 查询与订阅之间不能漏掉新事件；重连从已落盘 cursor 接续 |
| reset / changedRanges | 明确原因、新 epoch/格式、失效区间或 cursor mapping | 物理截短、原地改写、迁移等不能当正常追加。只重取失效范围；全局不兼容重建必须单独显示和计数 |
| gone / forbidden | 权威删除或权限撤销结果 | 撤下视图，持久 tombstone/删除；不把普通 live registry 移除当永久删除 |

**分段令牌规则。** resumeToken 是新增插件Host签发的版本化持久恢复令牌，绑定日志 identity/epoch、协议/格式及已认可的 cut C；它不是认证凭据，每次使用都重新核验当前身份权限。opening 明确 accepted C 与 authoritative S。每个 catch-up batch 返回 ending D 和对应 nextResumeToken(D)，每个 durable live event/批次也给出最后 seq 对应令牌；Client 将其与截至 D 的 raw records、coverage 在同一事务提交后才替换磁盘 token。旧 token 不因签发较新 token 被立即失效，因此事务中 force-stop 仍可从最后提交的 C 重试。无变化返回对应 C=S 的有效令牌，不传历史正文；向后读取老页只更新coverage，不把尾部token降回老seq。令牌的签发/前缀校验由新增插件Host owns，不能从本地eventWindow revision合成。

**catch-up 与 live 接棒。** 服务端先订阅并缓冲，再取 S；C+1..S 分段返回，S之后的durable事件与assistant presentation帧暂缓交付或被Client有界缓冲。Client按连续顺序提交每段，以append保留原缓存窗口；达到S后更新当前projection/control与assistant baseline（baseline观察cut为S），然后顺序接入S之后事件与对应assistant帧。不能提前用baseline调用replace抹掉缓存老页，也不能先把S+1灌进缺少中间段的窗口。进入live后保持服务端durable event与assistant帧的原始混合交付顺序，只在各自序列空间校验seq/revision/index，不能两队列独立重排或跨空间按数值排序；展示层不能把assistant end提前到对应durable settlement之前。持久层可以独立批量写raw records，暂态不推进durable cursor。缓冲达到配置上限时暂停/重开，从最后提交token继续，不能无界存内存或丢gap。若catch-up中断，可读已提交缓存但标记同步未完成，不能启用依赖当前Host状态的发送。

**日志 identity 必须跨服务端正常重启稳定；发生格式迁移、重写、同 ID 重建或截短时能够失效旧 token。** seq 单调只在指定日志代际内成立；Host 进程 generation 不能替代 durable epoch。prefix witness/digest 可验证已声明不可变前缀，但不能神奇发现任意未纳入协议的原地修改。服务端必须承诺可支持的 mutation：追加事件；以及由新事件表达的 surface replace/删除。如果产品确有旧事件原地修改/物理删除，必须新增持久 revision/change journal、tombstone/changed ranges；没有它就不能声称满足用户的“修改、删除也只取变化”。

**跨epoch局部失效。** records按epoch/seq编址，新epoch不能只删失效页后任其余旧页不可见。服务端对Client已缓存覆盖清单返回明确可保留区间与old→new seq mapping/未变证明；Client将已证明未变的本地记录迁移到新epoch（或用显式引用元数据复用旧epoch存储），只下载失效范围，提交新coverage与有效token后才激活新代际。大范围迁移用暂存代际分批事务，最后原子切换active meta；中途崩溃保留旧完整代际供恢复，不展示混合epoch。没有上述证明/mapping的全局不兼容reset只能明确重建并单列异常流量；不得把每次消息修改/删除一律转全量reset后称真增量。正常追加的surface替换/删除事件不变更日志epoch。

当前 alpha1 的 surface append/replace 已有日志表达，因此不要另写“按时间猜新消息”。普通消息变化走官方原始事件 reducer。消息删除功能是否存在、采用何种权威事件尚需 owner/fixture 验证；队列 remove、反馈 delete 不等于历史消息删除。会话列表缺席、api-session/removed（离开 live Host registry）也不是权威永久删除证据。

分页还必须绑 epoch 和 authoritative throughSeq cut。缓存的旧 throughSeq 不能不经验证原样用于新连接。同期 live append 不改变本次向上翻页的固定 cut；已缓存邻接页从 IDB 读取，缺页才请求官方 page。若 surface 替换引用缓存窗口外的数据，按官方组装语义判定是否需要目标范围，不重拉整个会话；该接缝要专项 fixture，不能假设尾页独立折叠必然充分。

ETag/304 可减少未变资源的正文传输，但不定义变化后的差量内容、日志连续性和删除语义，所以单用 HTTP 条件请求不能交付本需求。参考 [HTTP Semantics RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html)。

## 打开与恢复时序

1. 官方retain创建真实binding并通过隔离face调用follow；插件按当前认证分区和完整address读取IDB，不长期保留所有binding。
2. 插件校验schema、epoch/token、覆盖连续性，resume取得cut S与差量，先在插件合并IDB窗口与C+1..S。无变化不下载旧历史；不以旧running放行发送。
3. 合并后yield唯一完整snapshot。官方journal与installWindow按原代码初始化连续[L,S]窗口，uiConversation消费原eventSource；之后只交连续live事件，不把差量suffix当replace窗口。没有新增hydrate API，也不发第二snapshot。
4. 原始durable记录在插件face校验后、yield给官方transport/ClientAssistantStream前纳入事务，records+cursor+nextResumeToken+coverage同提交。展示层可能hold settlement，所以不靠可见eventSource推进持久cursor。同步完成需cut S已提交；重连只认已提交token，允许幂等重收未提交小段。
5. page先读连续已缓存范围；缺页才请求新Host接口并持久提交。切到B释放A的官方引用与活动流、不删A磁盘；返回A创建新binding并重复以上步骤。

临时 assistant chunks/process-local attempt baseline 不作为 durable 历史记录落盘、不推进 cursor。重开从服务器重新取得活动流基线；durable settlement 到达并通过原始事件校验后就纳入持久事务，不等展示层公开它。这样不会把“正在生成”永久固化，也不会跳过未完成消息。projection/control 快照单独比较所属序列空间，旧 pending submissions/临时上传也不直接恢复成已发送。精确行为见 [alpha1 assistant-stream.js](../../artifacts/deploy-20261007/alpha1-contract/dsh-api-session-controller/lib/types/client/sessions/assistant-stream.js) 的 acceptDurable；该层与持久层必须分开。

## 并发、崩溃与清理

| 情况 | 处理 |
|---|---|
| 快速 A→B→A、旧网络晚回 | 每个绑定/认证分区有 generation；旧结果可在同身份下按校验后的记录写缓存，但不能写新选中视图；退出后的旧结果一律拒绝写 |
| 同会话多个窗口写 | 同一事务读取 meta，比较 epoch/cursor，幂等 upsert；低 cursor 不覆盖高 cursor；同 seq 不同内容触发协议失效，不能 last-write-wins |
| 同步事件有 gap/乱序 | 不推进 committed cursor 越过 gap；仅补缺段，校验连续后提交；不可支持的版本返回显式错误 |
| 已收事件但事务未完成时 force-stop | 旧 cursor 保持；下次只重收未提交段。事务中断不能留下“cursor 新、records 旧” |
| 配额、写盘异常 | 原事务回滚、保留旧 cursor，显示持久化失败；可以继续联网操作，但验收不得计作持久缓存成功 |
| 注销/403/身份切换 | 先阻止读写/撤销视图，再删除当前分区；用持久 cleanup generation 防止晚回写复活；仅网络错误不触发永久删除 |
| 用户清缓存/切站/系统删除数据 | 按现有壳 cleanup 删除相应缓存，随后明确冷缓存首次请求；普通关闭/force-stop不触发 cleanup |
| 格式升级 | IDB versionchange 升级有序关闭旧 connection；缓存格式不兼容只失效对应分区，服务端 session 数据不受影响 |
| 日志epoch/reset且projection seq空间变化 | 插件取消流并public dispose自己持有的官方Client apply Fiber；处理磁盘代际后重新ctx.plugin挂载，重建manager/projections/scopes/sessions服务，Cordis消费者重新订阅。不能只fail journal再retain旧Session，旧ProjectionValueStore较高水位会残留；异常reset重画允许，不修改private store |
| 权威删除 | tombstone 与记录删除同事务；缺席/卸载 live agent 不自行判永久删除 |

## 小步实施与预期收益表

“收益”是机制预期，必须用右列证据验证；前四步是能否实施的前置，不允许先交半套后称完成。

| 步骤/修改小点 | 所属部分 | 所属阶段 | 预期收益 | 验证或交付证据 |
|---|---|---|---|---|
| 1. 核实部署指纹、日志身份、删改语义和官方扩展权限 | Host/Client 版本及协议 | 方案前置 | 避免用错版本或错误增量语义 | 精确包 hash + 实际 schema/capability；删除/替换 fixture |
| 2. IDB 写读/事务/重启探针与当前 cleanup 核查 | 网页存储 + Android 生命周期 | 存储前置 | 确认可跨进程，无二进制桥依赖 | MuMu及支持真机 transaction complete → force-stop → read |
| 3. 新增 versioned resume 与 cut/range 协议 | 新增插件Host + 自有Remote schema | 差量取得 | 无变化不重传历史；有变化仅传 suffix/changed ranges | 守恒/连续性、subscribe-cut竞态、重启与逐会话权限测试 |
| 4. 包装官方apply、隔离兼容remote.session、单snapshot与epoch reset remount | 新增插件Client；原官方文件零修改 | 历史恢复 | 原官方owner/renderer正常消费缓存与差量 | 官方renderer snapshot、single opened、projection reset与发送行为 |
| 5. 实现 records/meta同事务写入 | 新增插件Client IDB Provider | 持久保存 | 关闭进程保留每个已打开会话 | 事务中杀进程、配额回滚、cursor不越 gap |
| 6. tail resume与幂等追加/替换 | 新增插件Client/Host，官方owner消费 | 后台同步 | 避免已缓存窗口重新下载 | 真实网络records范围与字节，追加/替换后相同UI |
| 7. 已缓存历史页优先、缺页按需请求 | 新增插件Provider，官方分页生命周期消费 | 历史翻页 | A返回和已读旧页减少重复请求 | 固定cut/epoch、跨页替换、无缝prepend及滚动锚点 |
| 8. 身份/退出/删除/多写者治理 | hanaccount接入 + Cache Provider | 全生命周期 | 防串数据、防晚回复活、防误删 | 401/403、网络失败、注销晚回、live移除和永久删区分 |
| 9. 发布前设备闭环与可回退开关 | 壳/网页/Host集成 | 交付验收 | 证明用户场景，形成实际收益数据 | 下述验收矩阵，失败留原官方联网功能，不宣称已达标 |

## 验收矩阵与测量终点

| 场景 | 页面/磁盘证据 | 网络证据 |
|---|---|---|
| 首次 A，翻两页旧历史 | 每个已加载记录与meta成功提交；消息/附件引用/工具卡完整 | 仅首次窗口和实际未缓存页 |
| A→B→A（重复多轮） | 新 binding 从磁盘恢复A；画面与最新官方一致；官方引用可释放 | 无变化 records为0；不重复请求最近窗口或已读旧页 |
| transaction complete后 force-stop 重启 A/B | 可独立读回两个会话，缓存存在；新进程真实页面可读 | resume无重复窗口；可有认证/control/活动流请求 |
| 离开期间新增、surface替换、支持的删除 | 与官方权威结果一致，不重影、不复活旧消息 | 只接收相应新事件/变更区间/墓碑；不能全量后diff |
| 无变化 + 服务端正常重启 | 已缓存历史继续有效 | log epoch稳定；不被Host process generation误失效 |
| 截短/迁移/同ID重建 | 明确reset原因，proved未变范围本地迁移，激活代际原子；无mapping才必要重建 | 只取局部失效范围；全局reset传输单列，不能混入常态差量指标 |
| 收到事件后事务中force-stop、gap与重复；settlement先到end后到/丢失 | 恢复cursor不超过完整raw记录；展示暂hold不导致磁盘漏记录；幂等收敛 | 只补未提交/缺失段，记录补传范围 |
| 注销/换身份/403/网络不可用 | 退出撤视图并清分区；网络错误保留盘数据 | 旧请求不可在新分区写入；不越权读取 |
| 存储配额失败/用户清缓存 | 如实显示冷缓存或持久失败；业务可恢复 | 明确第一次重新拉取，不伪称命中 |

采用真实测试会话和发布候选包，不把 VM fixture 当设备证据。网络 trace至少记录RPC名、打开模式、cursor/cut、records数、范围与字节，不记录正文/凭据。记录 T_cacheReadable（真实历史可读）、T_inputReady（输入可操作）、T_syncCommitted（当前cut落盘），分别比较固定设备/账号/会话/加载范围的前后样本。不把总启动所有耗时归为历史缓存收益。

## 方案交付结论

**零官方修改的插件方案在代码理论上可行，公开装配与数据调用链已闭合。** 新增一个Host+Client插件包装官方公开Session入口，提供独立差量namespace、持久identity元数据、IDB及隔离兼容remote.session；hanui提供移动反馈，hanaccount提供认证生命周期，App验证通用持久。保留原官方owner、renderer、发送及Host活动流语义；不改官方源码/已发布文件，不新增官方Provider，不monkeypatch。实现与测试应验证磁盘、差量范围、删改、事务、reset/remount和真实设备结果，不再将它们称作未知能力探测。不承诺未测的工期或节省秒数。

本稿提交独立 Sol 审核，审核状态与修订结论由主代理补充；截至成稿尚未通过设备验收。
