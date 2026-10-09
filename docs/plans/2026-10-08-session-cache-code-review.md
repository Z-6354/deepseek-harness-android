# 会话缓存实施：独立代码审查

日期：2026-10-08  
审核者：Sol，仅审核；业务代码由同一 Luna 实施  
当前范围：**hanaccount 通用 RPC 授权；新插件 Host/Client 同步、IndexedDB、公开 Cordis 装配、生成物、profile 与本地 tarball；Android debug fixture 的缓存模块与 force-stop 证据。仅更新审核文档，没有实施业务代码。**

状态：**本地缓存逻辑、部署配置与403窄修已通过；R3正常 grant 续租已通过定向自动重连测试，live落盘及完整Host模型/生成物仍待核；产品实网缓存验收尚未完成。** 用户随后仅批准<客户端出口/32>；该追加权限替代早期ip_blocked未许可状态，不能把登录替代IP门禁诊断。服务器与产品 App 最新证据及权限边界见末节；早期测试数量和 tarball hash 是历史里程碑，不等于当前线上状态。

## 固定基线与需求

固定基线为 `artifacts/session-cache-preimplementation-20261008/hanaccount/` 的 `fingerprint.json` 与 `files/` 内容备份；基线 HEAD 为 `26460080b92174478572e4dfac694d374369cf03`，其既有未提交修改也包含在备份中，不将整个 HEAD 差异冒充本轮新增。

需求来源：`docs/plans/2026-10-08-session-cache-implementation-brief.md` 的“hanaccount 通用 RPC 授权”补充。精确 alpha1 所有已认证浏览器共用 OperatorPeer，缓存分区必须以 Host-authoritative 账号 scope 为准，不能按 peer 映射最后登录账号，也不能信 Client 自报 scope。短期 grant 只驻内存，是认证证明；resumeToken 是非认证同步元数据，两者不可混用。

本次核对新增 `src/lib/rpc-authorization.js`，以及相对备份新增的 `src/api.js`、`src/index.js`、`src/lib/auth-session.js` 接线和相关测试。没有修改业务代码或生成 bundle。

## Standards：规范与所有权

- 认证服务由 hanaccount index 生命周期创建、provide、dispose；没有把会话历史 DTO 或持久库塞进授权模块，也没有修改官方 Gateway。
- 新 endpoint 位于既有 requireAuth、revalidate 之后，没有加入 PUBLIC_API_SUFFIXES；仍经既有 gate/native fence。成功 response 显式 `cache-control: no-store`。
- grant 的随机值、关联 Cookie token、scope、TTL、AbortController 仅保存在 Host Map；Client 的 grant/expiry 仅保存在 AuthSession 内存。返回 lease 不含 Cookie，不将 grant 写入 resumeToken、IDB 或日志。
- 未发现本轮定向范围的剩余规范阻塞。生成文件一致性属于独立发布检查，目前结果见下文，不能遗漏。

## Spec：授权行为核查

| 需求 | 实际代码链 | 结论 |
| --- | --- | --- |
| Cookie、native、IP 与 Origin 才能签发 | API requireAuth + revalidate；issueForRequest 检查 bridge.authenticated、gate.ipContext、store.sessionFromToken；connection.admit 确认 operator | 成立。Origin 沿原 native bridge 的 connection.requestRejection 检查；精确 alpha1 `dsh-client-connection/lib/index.js:586` 先 Host/Origin fence 后 native auth，`:591` admit 复用它 |
| scope 由 Host 得出 | issueForRequest 使用 store.resumeScope(token)，请求 body 必须空对象；authorizeGrant 返回保存并再次核对的 scope | 成立。expectedScope 只额外拒绝不匹配，不能赋予权限 |
| 共享 OperatorPeer 不串账号 | grant 以随机 secret 单独编址，分别绑定 token/scope/peer；没有 peer→账号 singleton Map | 成立，两个账号同 operator 的测试通过 |
| 每 RPC grant/peer/TTL/session 校验 | authorizeGrant 检查存在、peer 对象、expectedScope、有效 Cookie session 和当前 scope、expiry | 授权 face 成立；最终 Host capabilities/open/page 均实际调用，见下文 |
| 静默 stream 也可失效 | row.timer 主动 retire/abort；store.onSecurityChange sweep；logout/密码更新使 session 不再有效；dispose abort 所有 row | 成立。最终 Host 合并 lease.signal 与 invocation.signal，并在历史/live 分块中持续检查，撤权回归通过 |
| Client 晚回不复活身份 | grant generation、当前 authenticated status、scope 比较；invalidate/dispose 退休请求与内存 grant | 成立 |
| grant 不等于 resumeToken | 请求认证 lease 驻内存，TTL/重启后重新取得；没有持久同步 token 的实现混入本模块 | 成立 |

Origin 检查的事实依据是精确官方 fence 与调用链；现有新 endpoint 集成测试使用 native owner fixture，而非已部署 Gateway 测试。本次不把该 fixture 误称生产验证。

## 发现、修复与复核

### P2：合并 grant 请求的取消属于首个 caller，取消其他 waiter 被忽略（已修复）

初稿 `requestRpcGrant()` 直接返回共同 flight；仅首个 caller 的 signal 连接底层请求，后续 waiter signal 不订阅。确定性只读 Node 复现：B 已取消仍获得成功 grant，结果为 A/B 都 fulfilled；A 取消则会影响共享请求。缓存早退也忽略 already-aborted signal。

已直接反馈 Luna 并抄主代理。Luna 改为独立 waiter 集合与每 waiter abort：单个 caller 取消只拒绝自己；最后 waiter 退休才中止共同请求；进入缓存/合并分支前拒绝 already-aborted。修复未由审核者实施。

复核证据：

- `node --test test/rpc-authorization.test.js test/auth-session.test.js test/password-native.test.js`：**35 项通过、0 失败、0 跳过**。
- 独立 Node 复现分别取消首个/第二个 waiter：结果分别 rejected/fulfilled 和 fulfilled/rejected；底层共同 request.signal 两次均未 abort。确认存活 waiter 不再受另一 caller 取消影响。

**取消问题已关闭，没有剩余授权逻辑阻塞发现。**

## 当前生成文件检查与后续边界

早期独立 `node scripts/bundle.mjs --check` 发现生成物 stale，已反馈并由 Luna 重建。最终审核重新执行该 check 成功；hanaccount 完整 `npm test` 同样先执行 bundle check，再取得 155 通过、6 跳过、0 失败。生成物问题已关闭，审核者没有重建生成文件。

上述 35 项测试仅覆盖授权模块。新增 Host/cache 的实际审核结果如下；官方 wrapper 装配、最终 bundle、真实 force-stop 与设备验收不由此数量替代。

## Host/cache 定向审核（发现已闭环）

范围：`D:/0HAN/Work/dsh-session-cache-sync/src/host/log-state.js`、`src/host/sync-service.js`、`src/client/cache-db.js`，并沿 `src/client/sync-owner.js` 核对实际消费链。业务代码未由审核者改动。发现直接反馈唯一实施者 Luna，并抄主代理。

### 已确认的正确接缝

- Host `capabilities/open/page` 先调用 `authorizeGrant`，绑定 invocation peer 与 Host 返回的 scope；grant 没有写进 IDB、Host 持久 log-state 或 resumeToken。
- Host 沿原 controller iterator 转发 durable event 与 assistant 帧，保留混合顺序；Client live durable event 在 `await commitDelta` 完成后才 yield 给官方消费层，没有提前按展示折叠代替 raw 存储。
- 单范围 raw 写入、coverage 更新在同一 IDB 事务；live delta 同事务更新 token/cursor。只写老页不会降低 live cursor/token。Host 对客户端缓存 hash 比较覆盖已缓存老页，不只比较新 tail。
- 独立执行 `node --test tests/log-state.test.js tests/cache-db.test.js`：**9 项通过，0 失败、0 跳过**。测试覆盖基础范围算法与存储，不覆盖完整 open、截断恢复、大记录、logout 后迟到写入。

### 已修并复核的事项

**授权失效不能唤醒静默流（初稿 P1，已关闭）。** 初稿只在 `iterator.next()` 前后检查 grant.signal，官方 follow 只收 invocation signal。Luna 已合并信号并传给 follow/observe/page。独立内存 fixture 的静默流收到 lease abort 后：官方 signal aborted、pending next rejected、原 iterator finally 执行、observation disposed 全部为 true。首 snapshot/observe 失败的 finally 已改用 `observation?.[Symbol.dispose]?.()`，避免 undefined 掩盖原错并跳过清理。

**warm 哈希算法不一致（初稿 P1，已关闭）。** Host 原先使用递归排序 canonical，Client 用 JSON.stringify。独立复现：相同两条记录得到 `hash_mismatch=true`、warm deltas `[[0,1]]`，即无变更也全重传。当前两端共同导入 `src/record-hash.js` 的 canonicalJson。独立使用实际 cacheManifest（WebCrypto）→Host planDelta：warm_deltas_after_shared_hash=0，确认正常缓存不会因键顺序重传。

**退休分区迟到写入（初稿 P1，主调用链栅栏已复核关闭）。** 初稿 clearPartition 后旧 delta 可重建分区。当前保留 retired 标记并递增 generation，Client 每操作获取 generation，主读写链同事务校验它。独立旧 generation 写入复现已改为拒绝 `partition_retired`。最终清理事件接线和 owner 生命周期已复核，见末节注销状态转换。

**分页副本返回过期正文（初稿 P1，已关闭）。** 初稿 raw 已更新 edited 而分页副本仍 old。当前 PAGES 只保存范围/边界元数据，readPage 同事务读取当前 raw，并在覆盖已删除时拒绝命中。独立复核：page_reads_current_raw=edited；移除范围后 page_after_delete=null，没有复活旧副本。

**重开隐藏老页并重复拉取（初稿 P2，主体已关闭）。** 当前 snapshot 从已覆盖到 tail 的最老连续范围开始，page 先读本地元数据；oldestSeq 初始改为 -1。实际 createSyncOwner fixture 预存 tail 3..4 与老页 0..2，重开零 records 网络差量后 snapshot 为 0..4；同页请求返回 0..2 且 Host page_calls=0。hasMore 在最老可见 seq=0 时仍沿 opening tail=true，已反馈修正显示边界。

### 发现闭环追踪（后续修复替代初稿状态）

1. **P1：truncate/delete（已关闭）。** 当前 Host 比对全 raw 提供 unchangedRanges，staging 本地复制证明未变的记录，只网络接改变/缺失，激活切换为原子事务。独立实际 Host→Client 回退+修改复现：原缓存 0..5，删 5、改 2 后网络只传 seq 2；新 snapshot 保留 0..4、cursor=4、seq 2=edited。entry 已增加公开 owner dispose/remount 回调，真实未改官方 factory 的水位恢复已由下文 published Cordis fixture 验证。
2. **P1：cut 的覆盖证明（主体已关闭）。** 初稿只有 record 10 也能 commit cut 10。当前 commitCut 在同一事务检查 opening requiredFrom..cut，以及旧 cursor+1..cut 的 catchup 缺口。新增拒绝 gap/未覆盖 cut 的回归已通过；不要求未阅读的全历史从 0 缓存。
3. **分页副本项已关闭，见上文。** 生产 raw 的老页复用、更新/删除一致性及 turnWindow 转发已通过本地回归；实际产品分页 UI 验收留在末节边界。
4. **P1：大 raw record（默认配置下已关闭）。** 当前 Host 按完整 JSON frame 大小打批次，超过批次预算的历史/live record 分块，Client 密集数组+receivedCount 重组、校验 SHA-256 后才提交并发布。独立 `tests/host-client-roundtrip.test.js`：180KiB 历史及实时记录、每个完整 frame≤16KiB、两条正文完全重组并持久化。另独立 live 首块后撤权复现 next 拒绝 logout，未继续发剩余块。默认 32MiB record 上限属显式失败范围；非默认 Host/Client 上限协调也已通过下文配置回归。
5. **P2：老页 hasMore（已关闭）。** 当前 snapshot 用 opening.hasMore && visibleFrom>0；到 seq 0 不再虚报可加载老页。
6. **P2：冷开全量（已修）。** Host cursor>=0 才添加旧 cursor+1..S；cold cursor=-1 不再绕过 tail，真实 Host 开窗回归已通过。
7. **cut 一致性（源码检查已复核）。** 当前比较 opening 所有 records 对应 query raw hash，不一致抛 session_cut_changed_retry；Client 重试一次。raw 改写前后的 projection baseline 已由 published owner 同 seq remount fixture 核对；真实运行 Host 并发改写/observation cut 的端到端场景未验收，留在末节实网范围，不能将 seq 过滤本身视为历史改写证明。
8. **P1：并发 epoch（主体已关闭）。** 身份队列串行 load/判断/save；独立并发 observe 返回同 epoch、旧设备复用现有 epoch。Host 对 stale opening 抛重开错误，避免误发 removed；Client follow 按完整 address 排他。实际多浏览器/装配生命周期留给集成。
9. **P1：空会话（transport 主体已关闭）。** Owner 在 cut<visibleFrom 时构造空 snapshot，不生成反向 IDB 范围。独立最新 `sync-owner/cache-db/log-state/host-sync` 合计 **14 项通过，0 失败、0 跳过**，含空 opening 与第一条 live prompt raw 提交。此测试模拟收到 user/message，不能冒充官方发送命令 fixture。
10. **P1：RemoteResult 与 capability scope（源码已修，定向测试通过）。** alpha1 非 stream Remote 返回 Result；当前 owner.page unwrap 新 RPC 的 Result，compat.page 包装官方所需 Result。compat 回归通过，Host→Client 复现也使用实际 Result 形状。CapabilityRequest 已加入 scope，不再被 codec 丢弃。
11. **P1：/remote external（已关闭）。** ClientModules makeRequire 只标准化 /client，不登记 /remote factory；build 当前只 external 官方 /client，把 published /remote descriptors 编入新增 ownFactory。实际构建 factory 加载、build --check 已通过。
12. **P1：同 seq 改写与官方 projection 水位（已关闭）。** 精确官方 projection-store.apply 对 seq<=旧 watermark 丢弃；seed 同样调用 apply。Host 当前原有 raw hash 或 header 改变更新 epoch，客户端 hash 不同也走 reset/staging。实际 published Journal 与 Session binding 的回归已验证：旧 title=old、新 title=new，asOfSeq 同为 0，public Fiber remount 后旧水位不再阻挡更新。

初稿 Node ESM import /client 与 plain remote 的 fixture 限制已由 VM/实际 Remote Service 版本替代。独立最新八个测试文件合计 **21 项通过、0 失败、0 跳过**，包括 generated bundle factory、published Gateway Remote Service、实际 Cordis namespace 隔离、分页 Result、大历史/live record、迁移事务与 auth abort。`node scripts/build.mjs --check` 通过，证明新插件生成物与当前源码一致；hanaccount 当时的 stale 已在最终构建及独立 check 中关闭。

精确版本独立核对：Cordis 4.0.5-alpha.1，Gateway/SessionController/TypertRegistry 均 0.2.1-alpha.1。安装的 SessionController client factory 与仓库 alpha1-contract 相同，SHA-256 `20904cdbc1e59ed55a70480f56b1e182424f9689e87dd3d50f62652848dcbc02`；Gateway client factory 与 alpha1 扩展证据相同，SHA-256 `e09e26fcd4849cd55683b9cc702dca896efce93a5af3968431c1c8a62147b9cf`。未把最新版官方检出作为部署证明。

另独立在真实 Cordis plugin 中实例化精确 TypertRegistry 并 register(TYPERT)/remotes.register(TYPERT_REMOTE)：capabilities/open/page 三个 endpoint 正常注册，6 个 strict schema 可实例化 parse，capability scope 保留 48 位。没有以 no-op registry mock 代替此注册结论。

**官方 owner 集成门槛已关闭。** 更新后的 fixture 使用精确 published 真实 RemoteSnapshotStream/RemoteJournalStream，经 actual bundle VM→Gateway Remote Service→Cordis→原官方 Session Client factory；handleSessionAdded→retain.ready→binding/eventSource，真实 beginSubmission/prompt 到 session/prompt，epoch 切换触发公开 Fiber remount、raw turn 与同 seq title 更新；auth revoke 的活动 owner=0，恢复后=1。该轮独立 `npm test`（含 build --check）**24 项通过、0 失败、0 跳过**；加入 title 断言后实际 Cordis 测试另重跑通过。四个 Gateway ESM fixture 文件（snapshot-stream/journal-stream/stream-client/index）与 alpha1 证据逐字节相同。Local RPC、auth 与 snapshot-store seed 是 I/O fixture，不把此结果冒充真实设备或实际生产服务器验收。

**P2 可选配置边界已关闭。** 初稿合法 2KiB batch/32MiB record 在 1.2MiB record 上超过 Client 分块数。当前配置同时验证 maxRecordBytes 的 base64 长度在给定 chunk budget 下不超过 2048 chunks，拒绝不兼容组合；Host record 上限统一 32MiB，Client frame 上限容纳 Host 4MiB frame。默认和不兼容/可用组合回归均通过，默认大记录仍可完整分块传输。

**P1 生产 Client export 已关闭。** 初稿 package.json 的 ./client 指向裸 ESM src/client.js，而精确 alpha1 ClientModules Host `lib/index.js:719` 解析此 export，`:723` 用它作为原样服务的 clientPath。当前已改 ./dist/client.bundle.js。审核者独立读取实际 package export→VM，正确注册原 Controller 与新增插件两组 factory；生产 serve 路径与测试工厂一致。已要求将实际 export 路径纳入回归，避免仅硬编码 dist 文件。

**收口 P2：turnWindow 静默丢失（已关闭）。** 初稿丢失官方 Follow/Page 的 turnWindow{minMessages,minTurns}。当前 OpenRequest/PageRequest schema、Owner open、Host follow/page 均透传，实际 Cordis page 与 Host request 捕获断言核对 maxMessages/turnWindow；最终完整测试通过。审核者没有实施业务代码。

本轮最终检查与剩余验收边界见下节，不用本地测试数量替代产品部署验收。


## 最终候选复核与结论

**P1：临时认证不可用后再注销/换账号未清理（已关闭）。** 初稿 observeAuth 要求 prior.status 为 authenticated；authenticated→unavailable→unauthenticated 或新的 authenticated scope 会绕过 abort/clear。独立实际 Owner+IDB 复现得到 signal 未 abort、旧 records=1。当前以先前确认的 scope 判断：临时 unavailable 保留数据，随后明确注销或确认不同 scope 中止 stream、clearPartition 并退休 generation。最终两条实际 Owner 回归均通过，明确注销后 records=[]，迟到写入受 tombstone/generation 栅栏拒绝。普通 owner.dispose/app 关闭保留缓存。hanaccount 每次新 login 生成新的随机 scope，因此不承诺跨注销/重新登录复用旧分区。

**profile 授权依赖（已关闭）。** sample profile 现在显式包含 updated dsh-local-hanaccount bundle 与 file tarball dependency，两份 README 给出两包本地打包/安装流程；不再把缺授权服务的单独 cache bundle 配方称为可运行 profile。Cordis patch 禁用原 api-session-controller 顶层 Loader 项，wrapper 在 Host 挂原插件 child，在 Client 隔离 remote.session 并挂原 factory，保留唯一官方 Client owner；published 工厂未修改。

**external 不会产生第二个官方 owner。** 定向核对精确 alpha1 ClientModules：Host `lib/index.js:649` compose 仅使用已有 table，`:834` processOne 仅采集已启动且未 disabled 的 Loader 来源，`:426` external 仅给已有 graph rows 排序；没有按 external 自动加包/Loader 项。Client `lib/client.js:653` arriveGraphRow 只到达已存在的 graphRows dependency，`:706` makeRequire 可直接 materialize 已登记 factory；materialize 只取得 exports，不 apply plugin。ClientEntries.start `:264` 只为 manifest.plugins 创建 Loader 项。当前 combined bundle 先登记完整未改官方 factory，再登记新增 factory；external require 由已登记工厂解析，不触发第二 owner 或另一个官方 row 下载。这要求实际 composition 禁用原 standalone Loader 项（样例 patch 已做），不能在部署时同时启用原项与 wrapper。

最终独立复核：

- 新插件 `npm test`：**27 通过，0 失败，0 跳过**，含 build --check、真实 published Gateway/Cordis/Client factory、同 seq projection remount、真实 prompt 委托、turnWindow、撤权、迁移、大小配置与取消链。
- hanaccount `node scripts/bundle.mjs --check` 成功；完整 `npm test`：**155 通过、6 显式 opt-in live-network 跳过、0 失败**。没有把六项跳过称为实网成功。
- 新插件 `npm pack --dry-run --json` 含实际 ./client export 的 dist/client.bundle.js、schema、Host 与 cordis.patch.yml 共 19 文件。独立逐字节比较实际两个 tarball 所有文件与当前仓库：新插件 19 文件、hanaccount 34 文件均无差异。最终 SHA-256 分别为 `40d1e25b3ed01558d428987598f86dd2369003b69b0da889e3aec26a02c8c401` 与 `4a3f06254d1d7b749396bde768f06c3959f0dc85fb01b26a8e9cc8a6492ede57`。
- Android debug source 的 RuntimeFixtureActivity 使用 loadDataWithBaseURL 的 `https://session-cache-fixture.invalid/`；debug manifest 与 .debug applicationId 单独登记，没有打开产品 MainActivity 或请求业务服务器。fixture 直接 import 生产 cache-db/sync-owner；read 模式只读、不重建 A/B。独立 esbuild write:false 构建与已打包 asset 字节一致，APK ZIP 内 asset 也一致，asset SHA-256 `6b48469fed9c911cabb60c6251bc8fc1fa1c95e9ccf04898b26a5983f10a1dd4`，APK SHA-256 `a89cbf7fc15313e4941f79e17d3abd6a4d6bf60cb1b2bba8776583500ef79249`。
- [设备证据](/D:/0HAN/Work/deepseek-harness-mobile/artifacts/session-cache-device-force-stop-20261008.md)记录 WRITE PID 22597、force-stop 后 READ PID 22808。审核者另直接读取 MuMu logcat，实际 `10-08 02:13:11.872 22808 22808 I SessionCacheFixture` 的 READ_OK 包含 A/B cursor=5、coverage=[[0,5]]、全部 seq 0..5、oldPage A=[0,1,2]/B=[0,1] 及 epoch-A:5/epoch-B:5 tokens，证据不是通用平台 IDB 示例。

**结论：当前代码与本地打包候选通过审核。** 零官方源码改动路径、真持久 raw 事务与历史差量、公开官方 owner/prompt 接线在所列静态与本地 fixture 范围成立。设备 fixture 验证真实 WebView 进程 force-stop 后生产缓存模块读回，RPC 数据源仍是确定性本地 fixture；没有验收实际产品 profile 安装、实网 Cookie/Host RPC、产品 UI A→B→A 或响应耗时，也没有发布、部署或访问生产会话。以上边界是后续产品验收范围，不是未修代码缺陷。


## 用户授权部署后的独立审核（部署证据待核）

部署执行者为同一 Luna；Sol 仅检查配置、具体新差异及证据。目标固定为 生产服务器 / dsh.wannian.fun 与 MuMu com.labteto.dshmobile.debug 覆盖安装，不清应用数据。保留 hanui，更新 hanaccount 与新增插件，官方源码与 published factory 不改。

旧 `artifacts/plugin-stage-a-rollout-20261007/rollout.py` 的健康标准仅为 auth_ready、匿名保护拒绝与登录页，且备份只有旧两插件/认证数据/nginx，不足以证明本次缓存部署成立。已向 Luna 明确：本轮还需 active profile/cordis/lock 与原 standalone disabled 配置备份，回滚同时恢复原 owner composition 和包引用；认证数据不盲目覆盖部署后的登录/撤权事实。

| 本轮部署门槛 | 需核实际证据 |
| --- | --- |
| 包与配置 | 安装目录/两侧 client hash 与已审候选相同；hanui 前后 hash 不变；实际 profile 含更新 hanaccount 与新 cache bundle；原 standalone controller Loader 项禁用，cache 项唯一 |
| Host 健康 | 原 Controller public Host child、sessionQuery、hanaccountRpcAuthorization、typert 服务就绪；capabilities/open/page schema 实际注册；双身份内存 grant、Host scope 与 no-store response；匿名守卫继续拒绝 |
| Client 装配 | 实际 __DSH_BOOT__ 无 standalone controller 项；ModuleLoader 可解析 combined bundle 自登记原 factory；真实 Cordis isolated remote.session 与官方 sessions/Agent 单一 owner，无 injection/duplicate owner 错误 |
| App 覆盖安装 | adb install -r，不卸载/清数据；回读已安装 base.apk hash，实际打开产品 origin，不混入 .invalid fixture |
| 真缓存与真增量 | 两个已有会话与旧页的 IDB raw/coverage/cursor/token；A→B→A 重开缓存；实际无变更 warm open wire durable bodies=0（仍允许 opening/projection 元数据）；force-stop 后新 PID 与同 scope/完整 IDB 读回 |
| 回滚 | 恢复此前 active profile/包引用/原 owner、启动并验证 guard 与官方会话；新插件移除与状态目录分开，不丢历史或覆盖认证状态 |

本节当前仅为审核门槛；实际服务器/产品 App 部署与 wire/IDB 证据到达前，不将本地候选通过结论升级为部署成功。


部署预检配置审核补充：Luna 的只读目标调查确认 active profile 为 `/home/ubuntu/.dsh/profiles/web`，现有 base/web-app/hanui/wechat/autoupdate/plugin-repo/repo-testprobe/hanaccount bundles；hanaccount 与 hanui 目标分别仍为既有 symlink 路径。审核同意保留所有既有 bundle 顺序，在 hanaccount 后追加 cache，不用样例 profile 替换整份实际 profile。原 controller effective config 如非空须转入 wrapper 的 `config.sessionController`，回退须恢复原 effective standalone 状态。

独立入口依赖门槛已发给 Luna：新增插件 symlink 的 Node ESM realpath 解析从其真实目录开始，profile node_modules 中可以 resolve 不等于插件入口能 import 官方 peers；须用已安装真实 `src/index.js` 入口验证公共 imports 与实际版本/路径，解决路径限于新增插件侧，不改官方 npm 文件。offline lock 更新须保留既有依赖与精确官方版本，autoupdate bundle 保持；起服后再核版本/factory hash，随后确认实际 grant/query/typert 注入与三个 endpoint 登记。脚本及 live 证据尚待到达，此补充是预检要求和配置方向审核，不是服务器执行成功证据。


### 部署入口与脚本的新差异审核

- Host applyHost 返回 Service 的疑点已排除：精确 Cordis `lib/index.js:1067` 将带 prototype 的普通函数作构造器执行，取返回实例后仅调用 `[symbols.init]`，并非把 Service 当 effect。独立真实 Context + TypertRegistry + 默认导出 plugin 入口 `fiber.await()` 得到 active state=2、sessionCacheSync 服务与 contribution 已登记、errors=[]；dispose 后服务移除。该隔离测试未提供全部 agent/LLM 依赖，所以仅证明 Host 入口生命周期合法，不冒充完整官方 Host child 就绪。
- rollout.py 首版确定性 P1：service_active 使用 check=True 调用 systemctl is-active；正常 inactive 的 exit3 会抛，导致 stop 后断言无法执行、起服轮询提前失败。已反馈 Luna，等待 check=False/状态回归闭环。另已要求 stage→deploy 之间核 profile package/lock/cordis 基线未漂移，以及实际所有 peer 版本/factory hash断言。
- 真实 stage 暴露新插件漏声明 zod runtime 依赖，正式 active 配置未改。当前 package.json 与 lock 根已补 dependencies.zod=4.6.5；审核 bare runtime imports 只有 zod、已声明官方 peers 和 Node builtins。仍须更新实际 tarball/hash 并从 target 真实入口验证 zod 与所有官方 peer；不能把 profile 可解析或临时链接当包依赖正确。
- 原 Controller 仅需确认精确 nativeOpen/listWorkSliceMs effective 配置（官方 schema 不是 onlyNativeOpen）；空用户 overlay 不代表官方 bundle 默认行无配置。待安装 alpha1 bundle row 或实际 Loader entry config 证据。


新版 rollout 脚本 SHA-256 `8279a8e6bb4f01110a71d968666aad84ebdaa06ecae34918ff92f1c537531415` 已复核：service_active 用 check=False，独立 `self-test` active0/inactive3 与异常拒绝通过；7 个官方 peer exact version、原 Controller factory hash 和 zod version 已断言；deploy 前 profile_digest 与 backup manifest 比较。新 tarball `095078d70abb99d613e9006ac689668270d0fec287e61e686f8a4dc6393f580b` 逐文件与当前源码无差异，runtime zod 声明在包内；独立 profile 3 回归与 build check 通过。Luna 报真实 stage import 成功、active 配置未变，现场输出尚待落盘复核。

**部署 P1：实际 row ID 错配（初版发现，现已关闭）。** Luna 的 installed alpha1 web-app patch 读取发现官方行是 `id: session-controller`，无 config；当前 cache own patch 则使用 `id: api-session-controller`。不能凭 name 同名推断原行禁用；要求精确 patch composer 实际组合验证原 Controller row 唯一且 disabled，cache row 唯一，再更新配置回归、tarball hash 与重 stage。这个目标新事实未被此前镜像 sample 字符串的测试覆盖，已立即反馈唯一实施者 Luna 并抄主代理。原 config={} 保留 default 的来源已由官方 row 无 config 与空 profile overrides说明，只涉及 nativeOpen/listWorkSliceMs。


### 部署前修订复核：可以执行已授权 rollout

实际已安装 alpha1 `dsh-web-app/cordis.patch.yml` 只读 fixture SHA-256 `3073a35dbb1dafd933143e7c299452256073f9f7f5f129aeeeba85a55fa85192`，官方 row id 确为 session-controller，未提供 config；空 profile overrides 下 wrapper `sessionController:{}` 保留原 nativeOpen/listWorkSliceMs 默认。精确 cordis-plugin-include 1.0.10-alpha.1 的 applyEntryPatches 确实按 entryMap 的 id 匹配，name 仅检查目标名称；旧 api-session-controller 的 patch 会警告后跳过，原 P1 成立。

当前已修新插件 patch 为 session-controller。独立定向运行 profile-config **4 项通过**，其中以精确官方 web-app fixture 先生成 rows，再实际 applyEntryPatches 新插件 layer：官方行恰好一条且 disabled=true、cache 行恰好一条；不是镜像字符串断言。build --check 通过。新实际 tarball逐文件与当前仓库无差异，SHA-256 **873bc6eb56746d8ca9e9264c6de0401a321b66a211fb05b2f0132ab371a1c926**，包内 zod runtime 声明与正确 patch 俱在，替代上文两个历史 candidate hash；hanaccount tarball未变。Luna 完整测试29/29通过，本次审核只重复受影响的4项与构建检查，复用先前已审缓存逻辑证据。

rollout.py 当前 SHA-256 **48941fef6222c95ec84461cab5b41d16f4e631c599e4dd97c7a2f217b1d6db4b**：inactive exit3 改为正常 false、独立 self-test 通过；所有精确官方 peer 与 zod/import/factory hash门槛、stage→deploy profile 基线比较、恢复 profile/lock/cordis/node_modules/Hanaccount 与原 owner、auth 数据不自动回退均已复核。新增插件原先不存在，回退移除其代码而保留非活跃 Host log-state；nginx 只在恢复 guard 后还原。Luna 报重 stage 真实入口与精确 peers通过、active 尚未切。

**已直接通知 Luna 可继续执行用户已授权的 rollout，无需再次询问用户。** 此结论是修订候选/部署脚本可执行，不代表已部署成功；实际 Host child/注册、客户端 Boot graph、产品 origin 缓存与 warm wire、force-stop 新 PID 证据仍须复核。


### 产品入口 403 的窄诊断与修订（代码已审核，实际策略证据待核）

Luna 报维护切换起服 guard通过、恢复 nginx 后产品 App auth/me 403 access_denied。独立代码判别：无 Cookie 的 auth/me 正常为 200 authenticated/nativeAuthenticated=false，首页通过门禁后为 200 登录页；缺身份本身不能解释本次 403。gate 的 IP block 返回同 code 403；api 先调用 bridge.fence，精确官方 Host/Origin fence 拒绝也映射相同 code，因此先核实际 CDP target/origin、Nginx Host/Origin/IP 转发、只读 auth 配置与访客 IP-block reason。loopback健康使用 X-Real-IP 127，不证明产品客户端外网 IP 已放行。不得新增白名单或关闭限制；已证部署配置漂移才恢复原配置，真实出口改变留给用户作权限决定。凭据仅用已有站点 credential 在内存恢复，不猜密码、不写认证快照回生产。

新耦合问题已反馈并由主代理纳入窄修：classifyMeFailure 原把任何401/403当 unauthenticated，cache observeAuth 会据此 clearPartition；临时 IP/Host fence 403 即可能误清仍有效账号缓存。要求 access_denied 保持 unavailable + last confirmed scope，暂停 grant/业务访问并退休旧内存 grant；仅明确 me200失效、logout 或确认 scope 改变清分区。已向 Luna 给出真实 AuthSession→Owner 的定向回归要求，等待新diff/生成物/实际包复核，尚不把产品入口或部署验收写为成功。


403 窄修最终复核：hanaccount 仅将 **403 + code access_denied** 分类为 unavailable，保留 last confirmed scope、退休内存 grant且 requestRpcGrant 拒绝；其他现有401/403分类维持，identity_revoked 对照仍清身份。本次没有修改白名单、可信代理、密码或认证事实。新增插件在 unavailable 中止已有操作并卸官方 owner，但保留 IDB 分区；仅 authenticated 挂 owner，恢复同scope重新挂；明确注销/me200false或确认不同scope才清旧分区。

独立定向 auth-session **13 通过**；sync-owner 与真实 published Cordis **5 通过**；两侧 bundle/build check 成功。额外只读组合验证使用实际 AuthSession、SyncOwner 与 IDB：403 后 raw保留、scope相同、stream中止、grant拒绝；恢复同scope重新取得 grant（调用计数由1到2，确认旧grant退休）；实际 me200false 后 raw=[]。另在实际 Cordis fixture 初始 mount 期间注入 unavailable，最终活动 owner=0，没有保留可访问 owner。审核者没有实施代码或生成 bundle。

两实际修复包逐文件与当前仓库无差异：hanaccount SHA-256 **e99b70f69e120409875473fed35bc99a24b072b31b16f4804b92d93903df9eda**；cache SHA-256 **eade7d0c3c56acc378af35f581a61154e3305ea107ec4b9105c51cf5cccb20dc**，替代此前部署候选。Luna 报全量 hanaccount157通过/6跳过/0失败、cache29通过。本次只重跑受影响检查，复用未变 raw/delta/pack 证据。已通知唯一执行者 Luna 可继续本轮授权的代码候选更新；不将候选通过视为已上线或实网缓存验收。

Luna 报访客 lastReason=ip_blocked，客户端出口<客户端出口/32>未获新增权限。仍需只读 current-vs-stopped auth 配置安全字段、nginx hash 与访客记录诊断证据落盘复核；不打印密码/hash/Cookie、回写 auth snapshot、添加 IP 或关闭 IP 限制。该门禁拒绝不能凭手动重新登录解决，部署配置如有偏移只恢复已证原值；真实新出口放行必须由用户决定。服务器 guard 健康不等于产品 App IDB/wire/force-stop 通过。


IP 诊断安全摘要已读取：[ip-gate-diagnostic.json](/D:/0HAN/Work/deepseek-harness-mobile/artifacts/session-cache-403-fix-20261008/ip-gate-diagnostic.json)。它记录实际产品 origin匹配、首页/authMe/status均403、visitor.reason=ip_blocked且客户端不匹配allow/deny；active与stopped安全配置字段一致（IP限制开、trustProxy默认true、allow3/deny0、密码已配），nginx原头设置与部署前字节一致，policyChanged=false。由此支持现有 IP 门禁拒绝的诊断，不能靠重新登录解决；访客来源不在已有许可内，审核没有申请或实施权限扩大。摘要不含凭据/IP地址，仅将具体出口私下报主代理供用户判断。

**R2发布脚本236666初版的问题（现已关闭）。** 新版已将上传区与rollback独立目录分离，但独立读 deploy 确认替换源码后缺 systemctl start，健康轮询必失败；且 phase 在全部健康前仍为 staged，except调用rollback又拒绝staged，安装/健康异常不能恢复R1。两项已发 Luna修复，要求持久installing/replacing phase、保护危险步骤、start后poll，并用mock成功/异常流程验证回退不触碰auth/profile。代码包通过不等于该脚本可执行，等待脚本具体修订。


R2发布脚本最终修订 SHA-256 **923244b3d5de2b4ff2379578f51fdedbf0080073e8992bb6e90dacca0437381d** 已独立复核：首个 live写前持久phase=deploying，try覆盖maintenance/stop/两包替换，随后实际target入口import→systemctl start→健康轮询；rollback允许deploying并恢复R1两包，保留profile/auth/policy。独立执行 `test_rollout_r2.py` **2项通过**：正常替换/真实入口校验调用/start顺序，以及注入R2健康失败恢复R1两目录、启动服务并还原nginx，profile保持原字节且无认证恢复路径。上传目录与独立R2rollback目录分离，原R1备份不改。已通知同一Luna可执行用户已授权的R2代码更新；没有授权扩大IP许可，现场结果尚待核。


### 用户新授权：仅批准<客户端出口/32>（实际变更待核）

主代理传达用户明确批准只加 `<客户端出口/32>`，替代上文该出口未许可的历史状态；不批准扩大网段、关闭IP限制或绕过账号身份。已向Luna给出维护停服下读取当前config.json、仅追加allow规则、完整保留其它keys/deny/password及state/sessions/token/passkey/peer字节的操作约束；原auth snapshot不回写，R2代码回滚不恢复authdata因此保留此用户批准规则。固定字段saveConfig可能忽略未知keys，须以持久JSON除了allow以外全深等证明窄范围，而非仅宣称调用setter。

独立调用现用 ip.js 实际 matcher：该/32合法且仅匹配<客户端出口IP>，邻近.17/.19、其它网段与loopback不匹配。等待安全配置diff（不输出密码hash）、原state指纹、已安装R2两包/guard与WebView login page200、authme200false结果。App无已有Cookie或站点凭据时需要人类正常登录，不伪造身份/绕过鉴权，也不将登录页200称为实际缓存同步验收通过。


### 正常 grant TTL 到期续接（已确认缺口，待窄修）

独立精确已发布代码证据：hanaccount rpc-authorization默认120s（上限300s）的timer退休lease并abort rpc_grant_expired；新Host open合并lease信号传原follow。当前owner.follow仅重试session_cut_changed_retry。Gateway `remote-stream.js:113` 仅 RemoteStreamCarrierError 启动新generation；其它错误terminal，`:192`归一为RemoteError。官方Controller `lib/client.js:2884` failEventStream清events且openState=error，未自动重拿grant。另HostGateway `lib/index.js:1424` rpcFailure只保留RemoteError marker；普通authError的code会折叠为gateway/internal，不能只添加Client同名code catch冒充续约成立。

已要求Luna仅新增插件/现有授权模块补typed-expiry或proactive renewal路径，取得fresh grant后经published Supervisor新generation以已committed manifest续接，不延长TTL，不修改官方Gateway/Controller，不重传老raw；logout/revoke/403/scope-change不得按到期重试。fakeclock回归须用实际published Supervisor/Journal，至少两个TTL周期仍能接新live并提交IDB、官方owner不落error；并发续约不得force请求互相取消。候选diff/测试与包待到达，尚未声称持续同步已完成。

Nginx原字节复核仍在进行：早期R0记录3cd9cc…674f与R1/R2当前fc8f…ad31不同，已要求读取R0backup raw SHA/CRLFcount而非用R2before宣称最初未变；若read_text换行归一，最终应恢复R0原bytes并保留中间hash的明确记录，不产生新的语义修改或重写旧备份。


### /32变更后的起服诊断（新差异待闭环）

Luna报approved /32已经按唯一allow差异写入，但起服systemd active、status503 auth_unavailable。安全journal分两处：Hanaccount createStore/readJson EACCES config.json；Typert loader报新插件 service.tags must be an array。前者与sudo执行atomic writeJson新inode默认0600/root owner一致，修恢复与state/dataDir同uid/gid1000:1001和mode600后守卫auth_ready、保护401通过，批准IP保留。首版/32脚本审核只核字段/状态差异，遗漏sudo atomic rename后的所有权，这是审核遗漏；已要求脚本持久保存/恢复原owner/mode并验证实际服务用户可读，不能以内容不变推断权限正确。不得恢复旧auth snapshot或撤销批准规则。

独立确认 service.tags属于实际Host模型装配缺口：精确alpha1 TypertServiceModel extendsTypertDocumentation要求tags array，官方generated model中tags=[]，新protocol.model service缺该字段；Registry.register浅validate不覆盖loader完整model，因此此前registryfixture成功不证明完整loader通过。已要求最窄补tags=[]，用实际published loader完整模型验证，更新生成物与包；仅做tags常量断言不足。R3续租候选还未实施，持续同步门槛仍待闭环。

Luna报已完成R0 nginx binary恢复，5877bytes/158CRLF与原hash3cd9cc…674f；需对应安全证据落盘供独立确认，不再称中间fc8f归一文件是原始基线。

### R3续租候选定向审核（尚未作为完成结论）

当前Client以实际公开Gateway RemoteStreamCarrierError让原Supervisor自动启动下一generation，保留原Journal与绑定；Host仅将rpc_grant_expired转为RemoteError，其它撤销/门禁失败保持终止语义。per-scope共享force flight、按expiry共享timer，403立即终止；网络/超时/429/5xx最多3次，不再无界循环。grant取得后校验当前scope/auth，unavailable/logout切断lifetime并清timer，dispose退订。package外部依赖包含原Gateway/client与Controller/client，真实class通过原已登记Gateway factory取得，无官方源码修改。

独立执行sync-owner + host-sync **10/10通过**，hanaccount rpc-authorization **6/6通过**。新增测试使用精确已发布RemoteStream与RemoteJournal；两个会话各仅initial open一次，两周期自动reopen，同周期force计数为1，没有手动恢复或failed回调。当前fixture仅在每次opening后以records catch-up追加新seq，未发送live event、未readSession断言持久cursor/token，也未显式断言下一请求manifest；因此暂仅证明自动重连和共享续租，不能称两周期live落盘/旧body零重传已经测到。已要求唯一实施者补最窄真实live与已提交manifest断言，再检查当前生成物/包与完整model validator。

hanaccount的authorizeGrant/isCurrent已先核当前账号，再以clock expiry分类，delayed timer入口对照通过。实际timer callback仍直接退休expired；已要求验证撤销账号与定时回调竞争不将其当可续租授权。此项是待验证窄边界，不宣称已发生权限绕过；Host重新签发仍经过真实账号认证。

/32 updater脚本已持久补保存原config stat、与state owner前置匹配，并在atomic replace后chown/chmod新inode，内容断言保持只有批准allow追加、state字节不变。先前sudo所有权复发路径由代码修正；实际服务用户读权限与现场恢复结果仍以安全证据核验，不回写旧认证快照。

### 最近会话启动的独立定向判断（Hanui候选待审）

App本轮只恢复站点document URL；旧native lastSessionId设置已在既有迁移中退休，不承担官方会话导航。Hanui当前startup-target.v1保存scope内bookmark：有效bookmark可恢复，但缺失/无效bookmark直接terminal Home，尚无当前授权ready列表最近nonblank fallback。合法sessions Provider卸载又把startupAssembly永久设为unsupported，reset/授权恢复后Provider重装可能不能再装启动协调器。这两处与“直接最近会话”的目标存在具体差距；缓存raw持久不自动补导航。

主代理已授权唯一Luna仅修改Hanui这两处，替代早期Hanui不变约束；App/core仍不改。审核边界：有效同scope bookmark优先；不存在/已删/blank时，等当前授权list ready再选最近nonblank；空ready列表停Home，loading/error不能当空；显式用户Home/改选其它会话取消该document的自动恢复意图，Provider teardown/remount及后续list更新不得劫持。合法依赖卸重与真实unsupported/deadline分别处理，保留历史ready/owned/paint链；新scope不借旧账号记录。等待窄diff与公开Workspace生命周期回归。

MuMu debug的一次authMe200false、rows0、无bookmark且无login form，与用户另一现场已登录反馈不能互相替代。现用Hanaccount登录页应含body.han-login与form/password/serverHost；需同CDP target的URL/origin/文档marker与无凭据Cookie摘要确认App包/页面/账号来源。没有据此让用户重复登录、伪造身份或恢复旧认证state。

### 产品实网缓存验收（Client/App 证据已落盘，2026-10-08）

证据目录：[artifacts/session-cache-product-accept-20261008/](../../artifacts/session-cache-product-accept-20261008/EVIDENCE.md)。

| 部署门槛项 | 实网结果 |
| --- | --- |
| Client 装配 | `__DSH_BOOT__` 含唯一启用 `dsh-session-cache-sync`；batch 拉取其 `client.js`；无独立 session-controller Client 行；origin=`https://dsh.wannian.fun/` |
| 真缓存 | IDB v4：两会话 + 214 records；A `coverage[[0,209]]` cursor/token；B `coverage[[0,3]]`；同 scope partition |
| A→B→A | `PASS_ABA_ZERO_HISTORY_WS`：回 A 仅 `opening`+`caught-up`，历史 body=0，UI 143 slots 恢复 |
| warm 无变更 | `PASS_WARM_ZERO_HISTORY_WS`（force-stop 前后各一次） |
| force-stop | `PASS_FORCE_STOP_IDB_PERSIST`：PID 16580→17258，sessions/records/coverage 不变，UI ready |

Host 只读复核（同目录 `host-profile.json`）：profile bundles 在 hanaccount 后挂 cache；`session-controller` disabled + cache insert；`tags: []` 在 protocol 与 typert.generated；`dsh-web.service` active，20:15 重启后无 tags loader 错误。

仍未由本目录闭合：服务端改 A 后差量样、停留收 live 前进 cursor、回滚演练。不把上述缺口写成未修代码缺陷；核心「已缓存历史无变更不重传」在产品 App 已有可复核证据。