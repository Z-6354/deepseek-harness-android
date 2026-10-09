# 会话磁盘缓存：Luna实施brief

2026-10-08。执行者只用一个Luna完成全部编码、构建、修复及本地验证；Sol负责本brief与最终独立审核。方案依据：[静态研究](2026-10-08-session-persistent-cache-research.md)、[独立审核](2026-10-08-session-persistent-cache-review.md)。零官方修改的公开调用链已确认，不再启动新一轮方案研究。

## 目标与权限

每个打开过的会话将已取得历史写磁盘。A→B→A、页面重建、App force-stop后复用；未变不重复传已缓存历史，有新增/修改/删除只传相应差量或失效范围，旧历史分页同样落盘。禁止“浏览器全量下载后diff”、只存内存/DOM/sessionId、独立仿制聊天UI。

可新增独立插件目录 `D:/0HAN/Work/dsh-session-cache-sync`，修改该新插件及自己的依赖/测试/构建产物；必要时少量接线hanui、hanaccount。可运行本地隔离Host、浏览器fixture、在线MuMu模拟器测试；仅使用测试会话/数据，不连接生产。不得写 `D:/0HAN/Work/deepseek-harness`、既有官方安装包或其compiled files；只读调用公共官方入口允许。不得commit、push、publish、生产部署或删除既有用户修改。App尽量零业务代码修改。

## 已有工作保护

开工baseline已保存于 `D:/0HAN/Work/deepseek-harness-mobile/artifacts/session-cache-preimplementation-20261008/`：每repo有fingerprint.json（HEAD、status、dirty文件SHA256/尺寸）、working-tree.diff、files/原内容副本。读取它后再改；有新并发变化先核对文件，不能覆盖。brief和后续审核文档是baseline之后新增的正常文件。

| 仓库 | 正确位置 | baseline HEAD | dirty文件数 |
|---|---|---|---|
| App/研究 | D:/0HAN/Work/deepseek-harness-mobile | 09247bf31d197d2ca831dcb48f8e181da069fe16 | 54 |
| hanui | D:/0HAN/Work/dsh-mobile-hanui | 3fbbf6dc7588c333c98e1a260d6a5c5cff283fa3 | 27 |
| hanaccount | D:/0HAN/Work/deepseek-harness-mobile/dsh-local-hanaccount | 26460080b92174478572e4dfac694d374369cf03 | 22 |

实现读取当前真实仓库，不从checkpoint覆盖源码。不得使用不存在的兄弟hanaccount目录。既有启动、图片和认证改动均保留。新插件目录当前不存在；可创建，不需要清理旧目录。

## 五类交付

| 部分 | 工作 |
|---|---|
| 新插件 | Host包装、生成Remote贡献、差量/identity、Client官方factory加载/服务隔离、IDB、真实bundle与本地fixture，是主要实施位置 |
| hanui | 仅如需要接新插件状态/诊断；沿已有真实会话导航与renderer，禁止私写Binding或新增聊天UI |
| hanaccount | 复用hanaccountAuth的scope/status/invalidate；若Host业务准入需新增face，只新增通用公开校验，不搬历史业务进去 |
| App | 复用稳定WebView目录和清理；新增debug测试fixture可接受，不引入DSH原生RPC/DTO/ArrayBuffer桥需求 |
| 官方源码/发布包 | **零修改**；原公开Host/Client入口、typert贡献与factory只读复用 |

## 装配顺序：固定执行

1. 新插件package同时有Host main、Client export、dsh.client、bundle patch、生成host/remote-client贡献与明确alpha1依赖。插件版本能力协议v1，配置包含启用、持久目录、配额/事件字节上限、同步批次、官方版本声明；未知官方版本拒绝缓存增强，明确报兼容错误。
2. 本地profile bundle配置禁用原顶层官方Session Controller Loader row；新插件Host调用公开`ctx.plugin(SessionController, officialConfig)`保留官方Host。注册官方公开`./typert`的TYPERT贡献（与同profile既有注册去重，禁止double-register），保留原session/fileReferences/skills生成Remote。新插件注册自己的`sessionCacheSync`namespace与schema。原Host只作为子fiber，不形成独立官方Client graph row。用实际alpha1Loader/ClientModuleRegistry fixture证明graph与贡献数量。
3. Client发布bundle先附带原官方client.js的factory注册原字节，再注册新插件自己的factory。保持原factory id；记录来源版本/hash，不给原文件改字节，不换导出。通过自己的factory收到的公开require取得原`/client`的apply/inject。dsh.client.immediately只代表stage-one预取，**不保证apply顺序、不属于固定bootstrap batch**。依赖声明列出原apply所需Gateway/fileUpload/其它实际服务包。
4. 新插件Client获取根scope原remote.session，创建`ctx.isolate('remote.session', label)`。先在子scope提供完整兼容namespace，再在该子scope`ctx.plugin`挂官方apply并保存Fiber。remote/sessions/typert/connection不隔离。Cordis tracker将官方`ctx.remote.session`解析到该子scope；UI得到原官方owner。
5. compat只改变follow/page；其它公开session方法原样委托根namespace，保留参数、RemoteResult、信号、业务错误。`$stream/$on`仍原Gateway。退出/卸载按fiber effect撤销。绝不修改rootRemote方法、Session private成员、readonly Binding或事件源。
6. epoch/reset改变seq空间时，取消旧流，public dispose自己保存的官方Client Fiber，完成磁盘代际切换后重新挂载。必须重建manager/projections/scopes/sessions，不能仅fail journal再retain旧Session，否则旧projection水位残留。消费者靠Cordis服务生命周期重订阅，允许异常reset重画。

## 建议源码模块与职责

可以合并小文件，避免通用框架；命名只是实施定位。

| 文件 | 主要函数/职责 |
|---|---|
| src/index.ts | applyHost、官方子fiber、官方/插件Typert贡献注册与撤销、Host配置 |
| src/host/sync-service.ts | capabilities/open/page；公共SessionController.follow取得cut/权威baseline；公共sessionQuery读取精确raw cut；Remote错误/准入 |
| src/host/log-state.ts | loadIdentity、verifyResume、diffCoveredRanges、issueToken、commitIdentity；插件专属持久目录，原子元数据 |
| src/protocol.ts | v1请求/帧、record验证、address/epoch/cursor/coverage、限额与错误码；正式生成Typert输入/结果schema |
| src/client.entry.ts | applyClient、auth生命周期、根namespace保存、隔离compat服务、官方apply Fiber拥有与reset重挂 |
| src/client/compat-session.ts | follow/page适配官方alpha1帧；其它业务方法委托；单snapshot与mixed-live顺序 |
| src/client/cache-db.ts | openDatabase/readWindow/commitRange/commitDelta/invalidate/clearPartition；IDB事务、meta+records+token同提交 |
| src/client/sync-owner.ts | 合并缓存与catch-up、版本/身份/generation、raw持久提交、bounded buffering与取消 |
| scripts/build.mjs | 生成schema、bundle与原factory附带；真实生成文件一致性与官方字节hash检查 |
| scripts/local-fixture.* | 受支持dsh profile启动本地隔离测试Host；测试origin/认证，不连接生产 |
| tests/ + fixtures/ | 单元、实际Cordis/原factory/官方renderer、真实Host wire、浏览器IDB/模拟器数据 |

## 协议与持久结构

新namespace公开methods建议capabilities、open（stream）、page（unary）。请求含protocol=1、完整SessionAddress、窗口选项；resume含最后**已提交**token及coverage/hash清单。token不是凭据，每次请求重新验证当前准入/scope及官方可见性。sessionQuery是读取能力，不是授权器；无任意sessionId泄露。Gateway peer准入不能替代业务可见性。沿现有hanaccount gate，不新开裸HTTP接口。

Host.open同进程调用**公开**SessionController.follow：取首snapshot的header/cursor S/projections/assistant baseline，丢弃其records，不发浏览器。取得sessionQuery immutable观察并裁至S，检查同源日志及address，cold或resume形成所需records。follow后续mixed durable event/assistant帧保持官方原序。官方snapshot生成的旧窗口只在Host内部计算，不计客户端重复传输。

| 插件帧 | 约束 |
|---|---|
| opening | accepted C、cut S、log identity/epoch、官方header/projections/assistant baseline、hasMore；声明cold/resume，允许历史records为空 |
| records batch | 连续from..to、raw官方records、nextResumeToken(to)、cut S；分批限事件/字节，不越gap |
| caught-up | C+1..S已交付，无变化时records为0；最后有效token |
| live | 包裹原官方event/assistant-stream，durable event带可提交token；保留mixed原序，不跨seq/revision空间排序 |
| changedRanges/reset | 原地变化/截短/重建/格式变化的明确原因、旧范围、新epoch、proved未变范围和seq mapping；无mapping时才异常全局reset |
| gone/forbidden/error | 权威结果与原错误分类；api-session/removed只离开live registry，不能当永久删除 |

普通surface replacement/官方删除业务事件按新增日志处理。物理原地变更需Host对**所有Client缓存覆盖范围**核验内容hash，插件保存变更元数据/对应range mapping；不能只看cursor最大值、mtime或随机epoch。cold persistence revision只是来源提示。正常Host重启保持插件identity；兼容记录映射在本地迁移，网络仅取失效范围。未兼容全局迁移reset单列异常，不能把每次删改全量reset。

| IDB store | key与内容 |
|---|---|
| partitions | origin+认证resumeScope+cache schema；清理generation、状态 |
| sessions | 完整address+epoch；header、committed cursor/token、连续coverage、oldest hasMore、容量/版本 |
| records | partition/address/epoch/整数seq；type/time/data/surfaceOp/sourceEventSeqs/ignorable等原始durable字段 |
| staging（如需要） | 新代际的临时记录/映射；最终原子active meta切换，中途崩溃不混epoch |

只存取得的历史，不预下载整个会话。未读旧页不表示已缓存。records+coverage+cursor+token同一readwrite事务，等待complete；能用strict durability则用，不等pagehide保存。网络await在事务外。临时assistant chunks、attempt基线、pending echo/blob URL不作为durable记录；已到raw settlement即使展示hold，也先纳入持久事务，不由eventSource订阅猜cursor。配额失败回滚、旧token保留，不能静默回退内存后称持久成功。

Client compat.follow在本地完成缓存+C+1..S合并，返回**唯一官方SessionFollowFrame snapshot**，records为完整连续[L,S]窗口且保留已读老页；再发S后的原帧。同generation第二snapshot会被官方journal拒绝，禁止先旧缓存snapshot再当前snapshot。不要求联网同步之前先画缓存；用户目标是磁盘复用与差量网络。compat.page以经过当前epoch/cut确认的IDB页优先，缺口才向插件Host取得并存盘，保留官方turn alignment和paging大小。

## 实施与验证顺序

1. 新目录基础包、配置和公开wrapper装配；最先验证原官方Host/Client各一份、原官方factory字节hash、实际owner/renderer/发送一致。不得用假的ISessions替身作为装配完成证据。
2. Host协议、持久identity与token/range、实际generated schema，测试新增/替换/删除/跨页引用/截短/迁移、订阅cut竞态、信号与准入。需要服务端读完整raw log可以，但浏览器wire只允许缺失范围。控制快照与当前assistant基线可全传，历史正文不得重复。
3. IDB与Client compat，事务/gap/重复/低cursor/晚回/多写者/配额，测settlement先到end后到或丢失；字节界限不能截断单record导致假连续。
4. 真实生成bundle注册+VM/实际Cordis fixture；装入未修改官方factory，跑真实uiConversation/ui-chat，不仅源码函数测试。reset公开Fiber重挂清projection水位、保持scope归属。只正常生成新增插件文件。
5. 本地dsh profile+测试会话和浏览器IDB闭环；支持时npm test/build/check、node --check生成bundle、npm pack --dry-run。只跑变更相关检查；如果触及hanui/hanaccount，沿其当前AGENTS/脚本构建与测试，不能只手改生成文件。
6. MuMu本地fixture：ADB `C:/Users/han/AppData/Local/Android/platform-tools/adb.exe`；先read-only确认devices与目标包。用debug/local测试origin或隔离fixtureAPK，不覆盖生产站点/真实历史。等待transaction complete标记后force-stop重启，读回A/B及已翻页历史；必要debug fixture仅放App debug/test面。

| 硬验收 | 必须保存证据 |
|---|---|
| 首次A+旧两页、打开B | raw记录和metadata落盘；UI消息、工具卡、附件引用可读；IDB readback |
| A→B→A重复 | 原owner/renderer真实可读，已缓存历史wire records=0（无变化）；已有页不重发 |
| force-stop后A/B | transaction complete后新进程磁盘恢复；不是旧DOM/内存；无变化零重复历史records |
| 新增/替换/支持的删除/原地变更 | UI收敛，网络只suffix/changed range/墓碑；不全量浏览器diff |
| 活动assistant与重连 | 权威baseline/mixed顺序，raw settlement不丢盘；cursor不超过commit |
| 注销、403、网络失败、账号scope变更 | 退出撤旧视图/读写、晚回不复活；网络失败不误当logout删盘 |
| 重启Host、epoch reset、配额与事务中杀死 | identity稳定；reset新owner/projections；事务全有或全无；只补未提交小段 |

证据只记录RPC模式、范围、record数、字节、generation、脱敏测试标识和耗时，不记录凭据/真实正文。分别报告cacheReadable、inputReady、syncCommitted，不虚构秒数。MuMu某桥缺ArrayBuffer不影响IDB消息存储。若设备/fixture失败继续在同一Luna内修复，未通过的硬项明确保留，不把测试限制误写成理论不可行。

## 向Sol提交审核

交付新插件完整代码、可安装tarball、本地profile/fixture、执行命令结果、network-range与IDB/force-stop证据、三repo新增差异清单及baseline变化核对。说明官方源码/原包字节零写入、为何需要既有插件接线。Sol只最终审核，不另派执行agent。发现实现问题由同一Luna修复，再审其实际差异；不得先提交/部署再等待审核。

## 实施咨询补充：hanaccount通用RPC授权

精确alpha1 `HostConnectionService.admit(req)`对所有已认证浏览器返回同一`this.operator`；Gateway invocation.peer不是浏览器登录身份。`dshLocalHanaccount.peers`是外部配对存储，也不是Gateway身份表。因此禁止peerId→最后resumeScope映射，禁止仅Client自报scope授权。公开读取并比对scope也不能证明调用者拥有对应Cookie。

本次允许hanaccount新增一个通用授权face，不含会话历史业务。建议`src/lib/rpc-authorization.js`提供`createRpcAuthorization({store,connection,bridge,isCurrent,config})`，由当前index.js拥有并通过`ctx.provide('hanaccountRpcAuthorization', face)`公开。已有dshLocalHanaccount服务保留；无需修改官方Connection/Gateway。

| 接口 | 精确语义 |
|---|---|
| Host `issueForRequest(req)` | 仅HTTP请求服务调用：parseCookies(req)[COOKIE]→store.sessionFromToken(token,{touch:false})，bridge.authenticated(req)、gate的IP/代理检查及adapter.isCurrent均有效；connection.admit(req)确认运输peer。生成32字节随机不透明grant，在有界**内存**Map中绑定account token、该peer、authoritative store.resumeScope(token)、到期时间/清理generation。不接收Client提供的scope或account token |
| HTTP `POST /dsh-local-hanaccount/api/auth/rpc-grant` | 在api.js既有requireAuth(req,res)、authenticatedToken赋值和revalidate()之后处理，body空对象；不要加PUBLIC_API_SUFFIXES。调用issueForRequest并再次revalidate，返回`{protocol:1,grant,scope,expiresAt}`，沿现有no-store JSON响应。通过现有gate先验证cookie/native/IP；不签发给只有native cookie、只有scope、Bearer配对或operator本地调用者 |
| Host `authorizeGrant({peer,grant,expectedScope?})` | 每RPC入口调用：adapter/store仍活动、grant存在未过期、stored peer相同、原account session仍有效；从store得到当前scope。expectedScope仅可额外拒绝不一致，绝非授权来源。返回`{scope,expiresAt,signal,isCurrent()}`；scope由Host得出。signal在到期/注销/密码更新/身份失效/卸载时abort；stream合并invocation.signal并持续isCurrent核验，不只在open时验证 |
| Client `hanaccountAuth.requestRpcGrant({signal,force?})` | 经现有createAuthRequest、same-origin credentials、POST取得lease；只在内存存grant/expiry，按当前scope+auth generation合并inflight。拒绝server scope与当前已确认auth scope不同；logout/invalidate/dispose退休await并清grant，网络失败不伪造成注销。过期重新取得lease，再从已提交resumeToken重连 |
| 新缓存插件请求 | 带内存grant及可选expectedScope；Host调用authorizer，所有metadata/token/partition的scope使用返回值。Client不是scope权威。grant只属于请求认证，不存IDB/缓存records/Host持久log metadata，不写日志或fixture证据。resumeToken仍是非认证同步元数据 |

RPC grant是短期Bearer proof，不要假称PeerScope已隔离账号；随机值不可猜且只有验证Cookie的HTTP响应获得它。配置有限TTL/总数/每account数量，清理过期；同账号多窗口的独立grant不能互相注销。重启Host后grant自然失效，Client重新请求；IDB历史仍在。监听现有store.onSecurityChange重新校验/abort对应grant，时间到期也主动abort静默stream；避免recheck回调因sessionFromToken删除过期session再触发securityChange的递归。api.js已有revalidate既保护认证状态也保护异步晚回，继续保留。

必要测试：伪scope无grant、grant来自另一scope、native-only cookie、已注销/过期grant、同Operator两个不同account session并发、篡改/空grant、签发晚回、Host/app重启续期、无限silent stream过期、未授权请求零历史records。通用face/请求器分别属于hanaccountHost/Client，缓存插件只消费，不读取hanaccount内部store。官方源码仍零写入。

## 实施咨询补充：Client公开namespace装配

固定使用namespace隔离，不隔离整个remote。兼容face是插件自己的普通对象（或自有Service），不是生成schema注册出来的替代官方端点。以下是调用顺序说明，不要求写入官方文件：

1. 在未隔离的插件ctx读取原namespace：`rootSession = ctx.get('remote.session')`。所有官方与sessionCacheSync的生成Remote贡献都在此根scope挂载，**不要**在隔离子scope调用`$mount`生成第二份官方session namespace。
2. `scoped = ctx.isolate('remote.session', Symbol('session-cache-compat'))`。构造compat自有对象，follow/page为插件实现；其它session方法来自公开`TYPERT_REMOTE.descriptors`中namespace=session的method集合，逐个用函数原样调用`rootSession[method](...args)`。不用Gateway私有methods Map，不改rootSession的任何property。官方session方法为该生成贡献中的direct调用，业务参数/信号/RemoteResult必须保持。
3. `compatFiber = scoped.plugin({name:'session-cache-compat-face', apply(child) { child.provide('remote.session', compat) }})`，等待公开`compatFiber.await()`。provide由ReflectService的公开effect拥有；同名不同isolation store key不与根provider冲突。使用独立provider子fiber，避免在尚未ACTIVE的父fiber provide后等待依赖自身的子fiber产生循环。
4. 再用`scoped.plugin({name:'session-cache-official-session', inject:officialClient.inject, apply:officialClient.apply})`挂原Client，保存Fiber并等待公开await。原inject仍包含remote.session，但在该scope取得compat；其它服务从根继承。Cordis remote Service tracker的associate=remote把`ctx.remote.session`解析到caller ctx的remote.session，故内部Session拿到compat，原`$stream/$on`保持根服务且由官方子fiber正常拥有生命周期。
5. 卸载/reset先dispose官方Client Fiber，再dispose compat Fiber；挂新epoch重新按3→4顺序创建。原root namespace与原Host不随缓存Client reset被替换；官方Client整个重建消除projection旧水位。

贡献类型必须分清：Host公开`ctx.typert.register(TYPERT)`接受`TypertContribution={package,face,schemas,model,invocations}`；Client公开`ctx.remote.$mount(TYPERT_REMOTE)`接受`TypertRemoteContribution={package,descriptors}`并返回Promise<异步disposer>。`ctx.typert.remotes.register`只登记descriptor，**不能代替$mount生成可调用namespace**。插件自己的两个贡献用自己的唯一package；原官方贡献保留原package并只挂一次。

Host去重检查公开`ctx.typert.getPackage(officialPackage,'host')`和`ctx.typert.local.get('session/follow')`；Client检查公开`ctx.typert.remotes.get('session/follow')`、完整session endpoint集合及根namespace。profile明确一个贡献owner：如现有apiRemotes拥有官方贡献，插件等待其公共namespace并复用；由新插件拥有时在其根scope$mount一次，并禁止该profile另一owner再挂官方贡献。不要用“看到undefined就并发mount”与apiRemotes竞速，部分已挂不是可补注册的成功状态。实际fixture验收贡献/namespace各一次，记录公开registry endpoint清单而非private表。
