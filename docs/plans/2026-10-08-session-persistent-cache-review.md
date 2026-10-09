# 会话历史持久缓存：独立协议核查与方案审查

日期：2026-10-08  
审核者：Sol 独立审核  
状态：**零官方源码修改方案通过独立静态可行性审核；未实施，未做部署或设备验收**。精确 alpha1 代码已证明公开组合路径存在：一个新增 Host+Client 插件加配置，保留官方 Session owner 与 renderer，以隔离的兼容 `remote.session` 承接磁盘和差量。不需要新增官方 hydrate/Provider 接口，也不需要整体重写 ISessions。

本次只读核查覆盖精确 `0.2.1-alpha.1` 保存材料、官方本地检出和 hanui 边界说明。未修改业务代码，未访问生产。需求基线：每个曾打开的会话都能复用已取得的历史；A→B→A 和 App 杀进程后恢复均应有效；已缓存记录不因重新打开而整窗重新下载；服务器新增、官方支持的历史变更及删除要正确收敛。

**当前最终判定：理论可行。** 直接给原来已经运行的 owner 附加缓存、写 readonly Binding 或私有方法不成立；通过公开 Host 包装、原字节 Client 工厂登记、受控调用公开 apply、Cordis `isolate('remote.session', label)` 和兼容 follow/page，静态链路成立。下文首轮“需要最小官方 Client 扩展”及“零核心尚未证明”的历史建议已由此结论取代（superseded）；事实性 alpha1 协议核查仍有效。测试属于后续实现正确性与交付验收，不再作为公开组合能力是否存在的未决门槛。

## 证据范围

主要协议证据目录：`artifacts/deploy-20261007/alpha1-contract/dsh-api-session-controller/`。其 `package.json:4` 声明 `0.2.1-alpha.1`，生成 schema、声明和已编译 JS 可互相核对。渲染证据来自同批 `dsh-client-ui-conversation/`，以及 `artifacts/plugin-stage-a-contract-20261007/deepseek-ai-dsh-client-ui-chat-0.2.1-alpha.1/package/`。后者 tarball 清单和摘要见该目录的 `official-package-manifest.json`。

本地 `D:/0HAN/Work/deepseek-harness` 检出 HEAD 为 `0a15e36e7f82b6ed45af6fa9759f29b40dcd965d`，`git describe` 为 `dsh-v0.1.6-alpha.1`，其现有 Client Session 数据形态与 alpha1 保存包不同。它用于定位所有权和未来扩展位置，**不作为精确部署协议证明**。

已阅读官方仓库和 Client 的 AGENTS，以及 `D:/0HAN/Work/dsh-mobile-hanui/AGENTS.md`。hanui 现有所有权是客户端体验增强；业务连接、会话与协议由官方 DSH 页面负责，公开状态投影不等于公开历史写入能力。

## 已核查结论

| 问题 | 精确 alpha1 的可证明行为 | 证据 |
| --- | --- | --- |
| follow 能否携带磁盘保存的 afterSeq | 不能。正式请求只有 address、assistantStream、maxMessages、turnWindow。 | `dsh-api-session-controller/lib/types/types.d.ts:459`；`lib/typert.host.js:2229`；`lib/types/client/transport.js:95` |
| 重新 follow 是否返回新记录差量 | 每次读取当前源并 paginate 最新窗口，先发送完整 snapshot，再发 snapshot cut 后的连续事件。 | `dsh-api-session-controller/lib/types/history.js:201`；`lib/index.js:1508` |
| follow 注释是否足以证明 resume | 不足。`history.d.ts` 提到 caller last committed sequence，但请求 schema 和实际调用均不接受该字段；这是注释与实现不一致。 | `dsh-api-session-controller/lib/types/history.d.ts:24`；上述 schema/runtime |
| page cursor 能否替代断线 resume | page 是固定 throughSeq cut 上的 beforeSeq 向后分页，没有 forward/afterSeq 请求。 | `dsh-api-session-controller/lib/types/types.d.ts:443`；`lib/types/history.js:104`、`:395` |
| SessionEventWindow.revision 是否为服务端历史版本 | 不是。它是本地事件窗口每次 replace/prepend/append/settle 发布的递增计数。 | `dsh-api-session-controller/lib/types/client/contract/events.d.ts:56`；`events.js` 的 publish |
| assistantStream.revision 是否为持久历史版本 | 不是。它是 process-local assistant 展示帧计数，baseline 包含当前活动 attempt 的 compact prefix。 | `dsh-api-session-controller/lib/types/types.d.ts:473`、`:478`；`lib/types/client/sessions/assistant-stream.js` |
| 旧内容修改是否可以通过新增 seq 表示 | 可以表示官方 surface replacement：新的事件含 replace 范围，早期被覆盖节点仍在原始日志中。不能由此推导任意物理改写或删除协议。 | `dsh-api-session-controller/lib/types/types.d.ts:420`；`lib/typert.host.js:2217` 的事件图 |
| api-session/removed 是否为持久删除墓碑 | 不是。定义为退出 live Host registry，发出点是 session/disposed。 | `dsh-api-session-controller/lib/types/types.d.ts:558`；`lib/index.js:2899` |
| 是否已有通用 mutation journal/reset/truncate 协议 | 在此正式 schema 和运行实现中未找到。compaction/prune、inbox canceled、feedback/message-delete 各有业务所有权，不能当通用聊天消息删除接口。 | `dsh-api-session-controller/lib/typert.host.js:2217`、`:2229`；`lib/types/history.js` |

### 状态同步不等于历史同步

`SessionProjectionBaseline` 有 `asOfSeq` 与 complete values（`lib/types/types.d.ts:65`）；control 每次开流先发全 baseline，再发 per-key 的 whole-value/seq 更新（`lib/types/control.js`）。Client `ProjectionValueStore.apply/seed` 在同一 Host generation 中按 sequenced watermark 仲裁；cached list 投影的 watermark 不可与 connected sequenced 值直接比较（`lib/types/types.d.ts:48`；`lib/types/client/sessions/projection-store.js`）。

这些可用于恢复已声明的投影状态，不能证明历史前缀未修改、未删除或已补齐。running 与 lastAgentError 还有独立的 live remote events（`types.d.ts:568` 起），缓存旧值不能替代新连接上的 list/control 结果。

### 真实 hydration 和渲染接缝

`SessionSnapshot` 是生命周期与控制快照，含 running、removed、openState、errors、pendingSubmissions 等；不是聊天消息容器（`lib/types/client/contract/snapshot.d.ts:58`）。只恢复该快照不会恢复历史。

`SessionBinding` 提供 SessionFace、只读 `eventSource` 和 scoped ctx（`lib/types/client/sessions/service.d.ts:76`）。公开 ISessions 没有 hydration 或历史持久 provider 注册口；最终 reference 释放会开始本地 history/scope teardown（`lib/types/client/contract/sessions.d.ts:28`）。

官方 Client 的实际装载点是 `Session.acceptEventChange → installWindow`（`lib/types/client/sessions/session.js:565`、`:581`）：统一更新 assistantStream、baseSeq、hasMore、projections、事件源、submission 观察及通知。Conversation 绑定消费 owner.eventSource（`dsh-client-ui-conversation/lib/client.js:3811`）；本地 revision 跳号或 replace 会重建 assembler（同文件 `:3692–3701`）。因此新增官方缓存注入口可以复用真实渲染链；插件直接包装 private Session/MutableSessionEventSource 不是现有公开能力。

建议持久格式以**验证过的 raw durable records**为核心，加连续窗口边界、固定 cut、hasMore、精确地址、身份分区及格式版本。不要保存 ConversationViewSnapshot/DOM、scoped ctx、reference count、浮点 seq 的 transient chunks、local pending echo 的 blob URL 或浏览器 object URL。

活动 assistant 展示必须从服务器新连接 baseline 接棒。baseline 含 attemptId、startedAfterSeq、nextIndex、compact stream 和 process-local revision；磁盘暂态不能作为新进程的流连续性权威。历史持久提交还必须能保存已经到达的 durable settlement，即使当前展示折叠器暂时等候 assistant end frame 而尚未把它公开进 eventSource。

## 方案必须解决的风险

1. **现有部署不足以实现严格差量重开。** 仅添加 IDB 写入或保留窗口，仍会触发 alpha1 follow 的完整 snapshot。需明确官方 Host + Client 的新增协议、部署升级和兼容能力判定；不能称此能力已存在。
2. **seq 不能证明前缀同一性。** 同 sessionId 的截短、重建、恢复旧备份或同长度外部改写可能让 seq 没有可识别差异。resume 协议必须有可持久比较的日志身份/epoch 或明确的前缀证明，以及服务器接受/拒绝保存 cut 的语义。connection generation 和 assistant revision 都不能替代它。
3. **“修改/删除”要按真实操作定义。** 新 seq 的 surface replacement/prune 可以按新增 durable 事件补齐。物理改写、任意消息删除或 session 删除若也属需求，必须有相应 durable journal/tombstone/reset 机制；不能把 live removed 当删除证明。
4. **hydrate 与随后同步必须保留已读老历史。** 已向后分页的窗口头和 hasMore 必须保存；重开后的新尾窗不能永久抹掉已缓存老页。所有缓存、补洞、向后分页与新事件应保持连续 cut，不可漏洞或重号。
5. **并发和原子性。** live 事件与 cache read/page/resume 并发时，过期 read 不得覆盖新窗口；records 与 cursor/epoch 的磁盘更新必须同事务提交。杀进程后只认可最后完整事务，不能让 cursor 越过尚未持久化的 durable record。
6. **缓存不是授权。** 必须先确定同 origin/Host 与合法账号 scope，再选择分区；logout、scope 更替、not-found/forbidden 和明确删除需要各自准确的失效行为。不能为避免重复下载跨账号或跨 Host 复用。
7. **磁盘与后台生命周期。** A→B→A 不应要求把所有会话一直 retain 或一直开 follow；每个已打开会话按需读写自己的记录。存储失败/配额策略要具体，不能静默把“每个会话落盘”降为仅最后会话或仅内存。

## 首轮独立方案审查记录（历史，官方扩展建议已取代）

审阅文件：`docs/plans/2026-10-08-session-persistent-cache-research.md`，2026-10-08 初稿。

初审认可的方向：独立 IndexedDB Provider、由官方 Client 内部恢复事件窗口、服务端版本化 resume、稳定日志代际与固定 cut；草案没有把 alpha1 既有能力夸大成已经支持差量，也没有用长期 retain 代替每会话持久数据。

已反馈给作者并抄主代理的实质事项：

1. **持久接缝必须在展示折叠前。** ClientAssistantStream 会暂存 durable assistant settlement。Provider 应接收已校验的 transport/journal 原始 durable records，再由展示层折叠；只订阅可见 eventSource 会漏掉已经到达而尚未呈现的记录。
2. **分段追赶与 live 接棒需要明确。** C+1..S 的所有分段完成前，不能让 live>S 或 assistant baseline at S 覆盖未追齐的缓存窗口。必须规定缓冲、去重、持久提交与活动流接棒的次序，并保留已缓存老页。
3. **token 和提交水位要能原子前进。** 如果 resumeToken 内绑定 cursor，每个 delta 段必须给出与该段 records 同事务保存的可继续 token；或者协议明确 identity token 加独立已提交 C 的组合。事务中杀进程后持有旧 token 必须能够重收未提交段，而不是被迫重新 snapshot。
4. **changedRanges 换 epoch 时不能丢掉已证明未变的记录。** 记录按 epoch/seq 编址；只删除失效范围却换当前 epoch，会使所有旧记录逻辑上不可读。需要服务器的保留范围证明/seq mapping，以及同事务的本地引用迁移或重编址；没有保留证明的全局不兼容 reset 可以重建，但必须作为异常传输单列。

### 修订复核与最终意见

已重新读取作者修订文件，四项实质事项均已处理：

| 初审事项 | 修订后的明确约束 | 复核结果 |
| --- | --- | --- |
| durable settlement 被展示层暂存 | 持久接缝位于 transport/SessionJournalChange 校验 raw durable records 后、ClientAssistantStream 展示折叠前；不等 end frame 才保存 | 已解决 |
| catch-up/live/baseline 交接 | C+1..S 分段连续提交并保留原窗口；S 后事件有界缓冲；到达 S 才采用当前 metadata/assistant baseline；live 保留原始混合交付顺序，不把 assistant end 提前到 settlement 前 | 已解决 |
| token 与记录的提交水位 | 每段及 live batch 返回 nextResumeToken(D)，与 raw records/coverage 同事务保存；旧 token 不因新 token 签发而立即失效；向后分页不降低 tail token | 已解决 |
| 换 epoch 后保留未变缓存 | 服务端返回保留范围证明/seq mapping；本地迁移或显式引用未变 records；暂存代际分批写，最后原子切换 active meta；无 proof 的全局 reset 单列 | 已解决 |

**最终设计结论：通过。** 推荐采用独立 IndexedDB Provider、官方 Client 缓存恢复接缝、版本化 Host resume/变更协议这一组合。当前研究稿与“所有打开过的会话已取得历史持久保存、切换返回与进程重启复用、常态仅传服务器变更”方向一致，未发现仍需阻止该研究方案交付的实质设计问题。

通过的边界必须保留：

- 当前 alpha1 不提供这些新增能力，业务代码仍未实现；没有 Host+Client 新协议/接缝，单独 hanui 缓存不能满足严格差量要求。
- 实施前仍要确定具体 owner/schema、允许的官方扩展范围、稳定日志 identity 生成与变更机制，以及真实产品的消息修改/删除操作。若包含物理删除/原地改写，持久 change journal/tombstone 或可验证 changedRanges 是必须交付的部分，不能降格成全量后 diff。
- 必须用部署候选包和设备实验验证 IDB transaction complete→force-stop→新进程读回 A/B、常态 records=0 或精确 suffix、分页旧页保留、settlement/end 竞态及注销晚回。VM fixture 或文档审阅不代替这些证据。

本次仅产生研究与审核文档，没有运行产品测试、访问生产、提交或发布。已对两份文档运行 `git diff --check`，检查通过；这只证明文档 diff 格式无报错。

## 新增约束：插件优先与官方最小改动复核

用户新增约束：按 hanui、App、hanaccount、新增插件、官方源码五类归属；尽量不动官方源码，现有两插件无法解决时新增一个插件。现已独立补查以下精确 npm 发布包，均为 `0.2.1-alpha.1`，不是本地其他版本源码：

| 包 | tgz SHA256 |
| --- | --- |
| `@deepseek-ai/dsh-typert-protocol` | `0408B973A3ECC5E98CB1DB15834E0C84B331FA30C5DB5CDCF5D67CE4FA62C201` |
| `@deepseek-ai/dsh-session-query` | `B8A1A4B57EA6F93E80E303D896CDB6213E54F1ADF799487B82193C9FCA7B2106` |
| `@deepseek-ai/dsh-typert-registry` | `D7A98B41405C63446E5DCAD9AAB31972F69D77BF8D43716DF935DB3A27790028` |
| `@deepseek-ai/dsh-api-gateway` | `A2119C18D5758DE24312D4BFE1CE04B96B2BC2F38373E65ED63268EE678D07A8` |

只为核查读取 npm 发布内容；独立临时展开目录为 `C:/Users/han/AppData/Local/Temp/dsh-alpha1-plugin-contract-audit/`。作者已将 protocol/query/registry 及另查的 projection 保存到仓库 `artifacts/session-cache-extension-alpha1-20261008/`，其中 protocol/query/registry 摘要与本次独立取得的包一致；Gateway 额外证据保留在上述临时目录。包可按精确名称和版本从 npm 重新取得并核对摘要。本次没有安装到项目运行依赖。

### Host 新插件的公开能力

- typert-protocol `lib/types/index.d.ts:64–88` 提供 `bindTypertRemote`、`TypertRemoteService`、自定义 namespace 与 `Remote({mode:'stream'})`。这是独立插件发布新 Remote namespace 的实际入口。
- typert-registry `lib/types/index.d.ts:9` 和 README 的 Registering a contribution 提供 `ctx.typert.register(contribution)`；生成 package/schema/invocation 能整体注册、卸载，重复 endpoint 或 package-face 整批拒绝。因此插件不能靠抢占 `session/follow` 同名 endpoint 达到扩展效果。
- api-gateway README 的 Client API 提供 `$mount()` 挂载生成的 Host-for-Client contribution；`$stream()` 管理连接恢复，但不推断业务 replay。插件可以定义自己的 resume namespace，仍要生成正式 schema，并拥有 replay、权限及取消规则。
- session-query `lib/types/index.d.ts:47` 提供 `observeSession`；`observation.d.ts:8` 的 lease 提供 exact immutable header、events、cursor、projections、source，并可 retain/dispose。这允许新 Host 插件查询历史后计算、分页发送差量，**Host 官方源码修改并非必需条件**。
- `SessionObservation.revision` 只在 cold prepared observation 上可选，冷缓存按 backend/stat revision 复用；它尚不等于跨正常 Host 重启稳定的日志 epoch，也不等于通用 mutation journal。读取 live Session 时不会查询 persistence。插件仍要明确定义 durable identity、支持的变更、gone/forbidden、subscribe-cut 与 assistant baseline 接棒；不能把 Query 服务当现成完整同步协议。

### Client 直接写入能力核查（仍有效，但不构成零修改组合阻塞）

alpha1 `dsh-api-session-controller/lib/types/client/index.d.ts:14` 确实 runtime export `MutableSessionEventSource`，同入口也 export `createScope`。这允许插件创建自己的事件源和 scoped Context。首轮关于“无法写入现有绑定”的结论仍成立，但不能描述为 MutableSessionEventSource 完全没有公开导出。

关键限制是现有 official Session owner 不提供 cache Provider/hydrate/transport adapter 接口：`SessionBinding.eventSource` 是只读面，`Session` 仅 type export，`ClientSessions` 不作为 runtime 导出；existing `Session.doOpen()` 自建 `SessionEventStream`，该 stream 调固定 `remote.session.follow`。新插件拥有一个 source 不会自动替换该对象层的源或阻止旧 snapshot 请求。

`UiConversation.binding()` 还要求 `this.sessions.binding(sessionId) === owner`（已保存 alpha1 `dsh-client-ui-conversation/lib/client.js:3808` 附近）；不能把 synthetic binding 直接递进去绕过真实 current owner。

| 归属 | 可以承接的职责 | 独立核查结论 |
| --- | --- | --- |
| hanui | 移动状态、诊断和插件开关，继续复用官方 UI | 不把它扩张成 Session 业务对象层 |
| App | 稳定 WebView 数据目录、既有显式清理和设备存储验收 | 默认不新增 DSH 消息 DTO 或 RPC 原生业务链 |
| hanaccount | 当前身份/认证 resumeScope 接入、注销失效通知 | 不承担历史协议与消息数据库 |
| 新增一个插件（Host+Client entry） | Host 独立 resume namespace、插件持久 identity/变更状态；Client IDB、兼容 namespace、正式 schema 装配与诊断 | 通过下述公开组合保留官方 owner，零官方源码修改可以闭环 |
| 官方源码 | 不编辑官方源码与已发布包字节；复用公开 Host class、Client apply 与原工厂 | 原“需要最小公开 Provider 扩展”候选已 superseded，不属于当前推荐方案 |

**此前“零代码只剩整体 Client owner 替换且尚未证明”的判断已 superseded。** 公共类型也允许实现一个完整替代 owner，但当前已有更小、确定存在的公开组合：复用未修改的官方 apply，只隔离它消费的 `remote.session`。无需访问 private Session、修改 readonly Binding、monkeypatch 或伪造 renderer owner。

### 五类第一次修订复核（历史，最小官方接缝建议已取代）

已复核研究方案最终新增的五类职责表、公开扩展证据、纯插件与零核心边界，以及下半部分更新后的协议与小步实施归属，结论为**设计通过**：

- 推荐一个新增插件同时承担 Host 的独立差量 namespace/插件同步元数据与 Client 的 IDB/transport；不再把新差量协议默认放入官方 Host 源码。
- hanui 负责移动反馈/诊断，hanaccount 负责身份 scope/撤销与通用授权能力，App 负责稳定数据目录和设备读写清理验收；各自没有被扩张为消息持久业务 owner。
- 官方主要剩余改动明确为 Client SessionHistoryProvider 的定义、注册、选择和完整生命周期消费，使插件数据真正进入现有 Session owner 及 Conversation 渲染；不是暴露一个 private 写方法就算接入。
- 活动 assistant 初始 baseline、逐会话/子会话授权、插件持久 identity/变更证明明确保留实验门槛。若公开事件/服务不充分，再提出有具体证据的最小只读 Host 接口，不预先实施泛化核心改动。
- 完全零官方代码修改被准确列为完整 Client owner 替换的高成本、未证明替代，未宣称新增小插件已经闭环。Gateway peer 准入与 Query 读取能力也没有被混同为逐会话业务授权。

本小节记录第一次分类修订。当时推荐“新增插件加最小官方 Client 接缝”，该推荐已被下面的最终静态构造取代，不再是当前交付结论。

## 最终静态可行性审核：一个插件加配置，零官方修改

已复核研究方案最新“零官方修改的静态构造”，包括具体 `isolate('remote.session', label)`；它与精确 alpha1 证据一致，比隔离整个 remote 服务更小。

装配方式已与作者最终统一为 **只隔离 `remote.session`**；不采用隔离整个 `remote` face 的中间版本。依据是 Cordis 对 name 为 remote 的 Service 设置 tracker.associate，再由 createTraceable 在 caller Context 取 `remote.session`。研究方案和本审核均沿这一条确定的 namespace 包装路径。

| 环节 | 代码证据 | 静态结论 |
| --- | --- | --- |
| 控制 Host/Client 自动装配 | 官方 `SessionController` 为 root runtime export；`client-modules/lib/index.js:835` 只扫 active Loader entry，`:859` 从 entry.options.name 解析 package；Cordis Loader `lib/index.js:628` 使普通子 fiber 继承 owning entry | 配置停用原顶层 Loader row，新插件 `ctx.plugin(SessionController, config)` 保留官方 Host；子 fiber 不自动生成独立官方 Client row |
| 保留原 namespace/schema | 官方 package.json 有 `./typert`、`./remote`；`lib/typert.host.js:953` 导出 TYPERT；registry 公开 register，Gateway 公开 $mount | 新插件显式拥有原生成贡献的装配，再注册自己的差量 namespace；不抢占或覆盖根 official endpoint |
| 不修改 Client 发布字节 | 原 `lib/client.js:1` 注册 factory；末尾 `:4288` 导出 apply、`:4289` 导出 createScope；client-modules `lib/client.js:675` materialize、`:702` makeRequire 可从已登记工厂取得 exports；ClientEntries 只激活 graph row | 插件发布物可携带原工厂字节，登记不等于激活；仅新增插件受控调用公开 inject/apply，无原顶层 Client 的竞争副本 |
| 隔离的具体 service key | Cordis `lib/index.js:1775` 的 Service tracker associate 为注册 name；`:133` createTraceable 优先以 caller ctx 查 `associate + '.' + prop`；`:1718` Context.isolate | `ctx.remote.session` 会读取当前子 scope 的 `remote.session`。因此只隔离 `remote.session`，保留共享 remote、connection、typert、sessions 即可 |
| 唯一官方 owner/scopes | official Client `index.js` 的 apply 创建 ClientSessions、注册 agent adapter；service.js `:186` provide sessions，`:472` materializeScope 使用 createScope | 原 ISessions/SessionFace、引用计数、pending submission、scope identity、prompt/control/附件行为由原 owner 保留；不存在重写这些契约才能接入的硬障碍 |
| 兼容 history 输入 | Session transport.js `:95` 调 `this.remote.session.follow`，`:139` 调 page；Gateway journal 构造器调用 `remote.$stream({open:()=>this.follow(...)})`，并拒绝同代际第二 opening | 插件 follow/page 是合法新 service 实现；原 $stream 执行本地兼容异步流回调，并不强制调用旧 Host follow endpoint |
| 活动流与 Address 授权 | Host `lib/types/index.d.ts:201` 公开 follow；`lib/index.js:3163` 委托原 history.follow；history.js `:351` 原 Address 校验核对普通/子会话及父子关系 | 新 Host namespace 在现有 Gateway 准入下，于同进程调用公共 controller.follow，使用官方 baseline 与混合 live 顺序，不访问 private accumulator |
| 精确差量与正式 UI | 公共 follow opening 有 header/cursor/projections/assistant baseline；query observation 提供完整 immutable events；UiConversation `lib/client.js:3808` 检查 sessions.binding(id) === owner | Host 内部可丢弃原尾窗 records，只把 token/变化发浏览器。Client 将 IDB records 与差量合并成唯一 `[L,S]` snapshot，原 installWindow/eventSource/assembler 接收，身份检查自然成立 |

构造必须保留以下具体条件，否则不是已审核的这条路径：

1. 官方顶层 Client row 不激活；原 factory 仅登记一次，原生成贡献也只注册一次。原工厂与新插件工厂随新增发布物到达，公开 require 可物化原 exports。`dsh.client.immediately` 是第一阶段预取标记，不意味着自动成为固定 bootstrap batch，也不保证 apply 顺序；新插件先安装隔离服务，再显式挂公开 apply，不靠多个启动任务争抢顺序。
2. 保存根 namespace 供非 history 方法委托；隔离后的 wrapper 只改 follow/page。原发送、queue、rename、attachment、control 等继续使用正式 namespace/schema。
3. 一个 physical follow generation 只产出一份 snapshot：完成服务端 metadata/差量校验后，把磁盘与新增 records 合成当前窗口再交官方 owner。不能先发 cached snapshot 后再发第二 snapshot；不承诺同步前立即显示缓存。
4. Host 调原 follow 所取得的已有 records 留在同进程内并丢弃，不返回浏览器；差量读取固定到它的 opening cut S，并保留 S 后 event/assistant 混合顺序。服务器内部完整日志读取与浏览器重复下载是不同观测。
5. 日志 epoch/seq 回退或认证身份切换需重建旧水位时，由插件公开 dispose/remount 它持有的官方 Client apply fiber；仅给旧 Session 再 retain 不能清旧 projection 水位。该组合操作重建 journal/projection/scopes，不调用 private 清理方法。
6. 新协议仍要完整实现持久 token/identity、record/cursor 原子事务、官方支持的 mutation 与删除、授权失效及取消；已核查的 Query cold revision 不能偷换成稳定 epoch。这里的代码待编写，不影响公开挂接链的静态存在性。

**最终审核判定：通过静态可行性审核。** 在“只新增一个 Host+Client 插件及配置，零官方源码或发布文件修改”的硬约束下，所述构造有精确 alpha1 公开代码证据，理论上可满足所有已打开会话已加载历史跨切换/进程持久复用，以及浏览器仅取得服务器缺失/变化数据。无需预先新增官方 Provider/hydrate 接口，也无需以原型才能回答公开能力是否存在。功能尚未实现，性能、设备持久性和行为验收不能据此称已完成。
