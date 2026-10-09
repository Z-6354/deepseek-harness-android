# App 阶段 A 更新计划

日期：2026-10-07。状态：App 阶段 A 代码已实现；JVM/build 与 fixture APK 验证以本计划末尾记录为准。插件未接入，设备 WebView fixture 未执行，也未安装、部署或提交。

审查补丁：修复读 token 消费到流登记间的 revoke 窗口、transfer Future 回调与清理锁序、已发布但未被 metadata 引用的 blob 回收、同键 remove/clear 后写入复活，以及 cleanup preferences/fence 两个进程崩溃窗口。私有 store 的磁盘扫描延迟到首个 probe 的后台 IO，不在 WebView 构造时扫描。对应单测和 instrumentation 源码已更新；设备用例仍只编译。

关联：[启动重构评估](./2026-10-07-startup-rearchitecture-assessment.md)、[三层架构计划](./2026-10-07-runtime-architecture-plan.md)、[协议](../PROTOCOL.md)、[安全](../SECURITY.md)。当前工作区已有大量未提交修改；实施时先记录文件状态和基线，不重置、覆盖或清理既有成果。

## 1. 本阶段交付范围

App 提供三项可独立验证的能力：

1. 将启动和静态资源诊断细分到任务、消费者、取消及兜底原因，明确真实慢点。
2. 分开静态资源生产任务与文档消费资格，在保留身份保护的前提下管理合并、取消、原子发布与清理。
3. 增加有界、按来源隔离的私有文件缓存能力，为后续网页图片 Adapter 提供字节写入和流式读取。原生不理解 DSH 会话、附件 RPC、resumeScope 的认证含义。

本阶段不实现网页图片 Adapter、会话历史快照、认证前移、官方拆包、程序资源 manifest 或原生聊天。与旧 hanui/hanaccount 完全兼容；未更新网页时新能力无人调用，不改变页面业务。

App 单独更新不能宣称第一阶段 7.4 秒变成 5 秒，也不能宣称历史图片已实现跨重启命中。前者依赖三层共同改造，后者依赖网页读取接缝。维持真实官方说明弹窗终点；pageReady、原生揭罩和弹窗可用分别测量，不提前发 ready。

## 2. 已核实现与约束

- [WebsiteBridge.kt](../../app/src/shell/java/com/labteto/dshmobile/browser/WebsiteBridge.kt) 已检查主框架、精确 sourceOrigin、commit 和文档 generation；[BrowserRuntime.kt](../../app/src/shell/java/com/labteto/dshmobile/browser/BrowserRuntime.kt) 再检查 browser/document 双轴 lease。
- [BridgeProtocol.kt](../../app/src/shell/java/com/labteto/dshmobile/browser/BridgeProtocol.kt) 当前只处理最大 16384 字符的小 JSON。保留旧格式；不能把图片 Base64 放入该协议。
- [StaticAssetCache.kt](../../app/src/shell/java/com/labteto/dshmobile/browser/StaticAssetCache.kt) 已支持本地文件流命中；首次获取仍完整读入内存、写盘后发布。owns 失效后的 Skip 可能触发默认请求，但尚未证明是异常重传根因。
- [BrowserEnvironment.kt](../../app/src/shell/java/com/labteto/dshmobile/browser/BrowserEnvironment.kt) 的 clean 当前主要删除 WebStorage，新私有文件必须加入同一事务。[SiteRepository.kt](../../app/src/shell/java/com/labteto/dshmobile/browser/SiteRepository.kt) 已有 cleanup nonce 与永久 WebView storage suffix，不能用轮换 suffix 替代清理。
- App 已在 Application 调用 BrowserSession.warmup，不把已有预热列为新增收益。
- AndroidX WebKit 为 1.16.0。官方支持 ArrayBuffer 消息，但必须运行时检查 `WEB_MESSAGE_ARRAY_BUFFER`。参考 [WebMessageCompat](https://developer.android.com/reference/androidx/webkit/WebMessageCompat)、[WebViewCompat](https://developer.android.com/reference/androidx/webkit/WebViewCompat)、[WebViewFeature](https://developer.android.com/reference/androidx/webkit/WebViewFeature)。
- 当前协议记录部分旧 WebView 使用 JavaScriptReplyProxy 回复会原生崩溃；**所有新增 JSON 回复仍走既有 BridgeReplyDelivery，不使用 replyProxy.postMessage**。不发送原生到网页的大字节消息。

## 3. 信任与寿命模型

### 3.1 明确授权归属

可信同源网页负责：AuthSession 当前有效、官方 binding 与当前历史确认附件访问资格、身份变化时清理/停用缓存、删除失效。App 只验证来源、原生签发的句柄、文档 lease 与文件安全代际，不独立验证服务器会话授权。

网页提供的 `bindingLabel` 只用于选择缓存分区，绝非授权凭证。建议网页未来使用登录 scope 的摘要作为标签；同源脚本能访问同源存储，不声称此设计能防御可信站点自身 XSS。不同 origin 不共享任何句柄或字节。当前 resumeScope 是每次登录的作用域，不能伪称稳定账号隔离或跨重新登录复用。

没有新增 signed grant 协议，也没有原生调用 DSH 认证接口。网页在认证未知/失败、会话未授权时不得请求分区或读取；此规则属于后续网页 Adapter 的强制契约，而非 App 从一个 `authenticated:true` 字符串推导的能力。

### 3.2 三种寿命

| 对象 | 归属与失效 |
|---|---|
| 文档消费句柄 | `DocumentLease(browserInstance, documentGeneration)`；导航/renderer gone/close 即失效 |
| 文件安全代际 | App 持久随机 `privateFilesEpoch`，与 active site owner 绑定；仅删除/退出/换站事务轮换，不轮换 WebView suffix |
| 分区 | native 随机 partitionId；索引键为 active owner、固定 namespace、bindingLabel 摘要、安全代际；进程重启可重新获取新文档句柄 |

所有异步写入持有安全代际，提交前在同一串行存储执行器内复核。文档变化不允许旧请求发布桥结果；安全代际变化则连文件发布也禁止。App 私有文件存放在 `noBackupFilesDir/private-cache-v1`，不进入云备份或共享外部目录；容量管理和清理属于 App。进程重启时删除未发布临时文件，已发布条目校验失败视为 miss。

网页自动登出并不必然通知原生；因此未来网页 Adapter 必须明确发送 revoke/clear。未接入的旧网页不使用本能力；不能以“App 已更新”声称服务器退出后的文件删除链路已完成。

## 4. 选定的接口与字节传输

### 4.1 能力协商

旧 `capabilities` 增加可选 `privateFiles` 对象，不改变 version=1：

`{version:1, transport:'arraybuffer-upload-same-origin-read', maxEntryBytes:33554432, chunkBytes:262144, maxTransfers:2}`。

只有 WEB_MESSAGE_LISTENER、WEB_MESSAGE_ARRAY_BUFFER 与 App 当前清理门闩允许时返回该对象，并携带 `state:'probe-required'`；私有目录及故障恢复在第一个 probe 的后台 IO 中惰性初始化，不增加WebView构造时目录扫描。不能仅凭 ArrayBuffer feature 宣告完整文件能力已可用。每个文档先执行下述无私有数据探针，成功才转为 `state:'ready'` 并接受实际分区/文件请求；存储初始化/校验失败后能力缺省，网页继续官方原有附件读取，不以 Base64、fetch POST 或弱桥替代。不改旧 pageReady/pageLifecycle 行为。

`privateFiles.probe` 的 `{step:'begin'}` 返回临时transferId、probeId和受控同源probe URL；网页先发送一帧32字节ArrayBuffer并收到ACK，再fetch该URL读取原生随机32字节（不提供任何用户文件），用 `{step:'confirm',probeId,sha256}` 确认读回摘要。原生必须同时观察到有效二进制帧、实际本地GET及匹配读回摘要，且lease未变，才设ready。探针30秒过期且最多每文档一次；CSP/SW/缓存阻断则不能通过。探针仅验证传输可用性，不验证服务器授权；probe token与随机字节均不持久化或记录日志。

### 4.2 小 JSON 控制面

所有方法使用既有 `{version,id,type,payload}`、大小与字段白名单。以下句柄均为 native 随机至少 128 bit，未记录到日志；网页不可指定 origin、磁盘路径或原生安全代际。

| type | payload | result / 语义 |
|---|---|---|
| privateFiles.open | `{namespace:'private-cache-v1', bindingLabel}`，标签为 64 个字符的小写 hex 摘要 | `{partitionHandle}`；当前文档临时句柄指向持久分区，标签仅分区选择 |
| privateFiles.lookup | `{partitionHandle,key}`，key 为网页规范化身份/版本摘要的 64 个字符的小写 hex | `{hit:false}` 或 `{hit:true,bytes,mime,sha256}`；不直接签发读 URL |
| privateFiles.beginWrite | `{partitionHandle,key,bytes,mime}` | `{transferId,chunkBytes}`；暂存，不覆盖已发布项；bytes 为 1..32MiB |
| privateFiles.commitWrite | `{transferId}` | `{bytes,sha256}`；完整性校验及原子发布后成功 |
| privateFiles.abortWrite | `{transferId}` | 幂等取消并删除暂存 |
| privateFiles.openRead | `{partitionHandle,key}` | `{url,expiresInMs:30000}`；短时、单次文件流 URL |
| privateFiles.remove | `{partitionHandle,key}` | 幂等删除该条目并撤销其读能力 |
| privateFiles.clearPartition | `{partitionHandle}` | 显式网页清理请求，只清该句柄分区并撤销其传输/读能力，不接受 origin/path/all |
| privateFiles.releasePartition | `{partitionHandle}` | 撤销该文档句柄与未完成传输/读能力，保留持久文件供未来已授权网页重新获取 |

错误为固定枚举：unsupported、stale_document、invalid_request、unavailable、not_found、quota、integrity、expired。不能返回本地路径、scope、token、内部异常文本。

open/beginWrite/openRead 要求当前有效主文档及前台；已开始的流在可见性变化时可完成，但文档或安全代际失效立即停止。release/abort/clear 可由仍有效的后台文档完成，便于认证撤销。网页清理不弹额外原生确认，仅删除自己来源和句柄内缓存；站点级全部删除仍由现有 App 设置/退出流程拥有。

首版 MIME 白名单为 image/png、image/jpeg、image/webp、image/gif、application/octet-stream。原生不解析附件业务；image 类型发布前校验有限文件头，不整图解码；不允许 text/html、JS、SVG 或执行内容。八位字节缓存不能用来启动程序资源。

### 4.3 上传：ArrayBuffer 单向分块

新增 `HanPrivateFileWriter` WebMessageListener，允许 origin 与 HanApp 相同；再次校验 WebView 实例、mainFrame、sourceOrigin、commit、DocumentLease 和 transfer 所属分区。只接收 TYPE_ARRAY_BUFFER，不调用 message.data 解析二进制。

每帧：16 byte native transfer nonce + 4 byte unsigned big-endian seq + 4 byte unsigned big-endian payloadLength + payload。序号从 0 开始；payloadLength 必须等于实际长度且 ≤256KiB；总长度不可超过声明 bytes。客户端一次仅发送一帧并等待 ACK，原生至多两个 transfer，并有全局 512KiB 待写 payload 预算。

接收回调只做长度、句柄和次序检查，将有界块交 IO 执行器；写完才使用原有 BridgeReplyDelivery 向 HanApp.onmessage 发小 ACK，correlation id 规定为 `pf:<transferId>:<seq>`。未来网页 Adapter 在发送前登记这个 id。ACK 包含下一序号和累计已写 bytes。磁盘 IO、摘要计算、文件头检测不在 UI 线程执行。

乱序/重放/重复或超量帧终止 transfer，不默默拼接。10 秒无进度或 60 秒总时长自动终止；每个 terminal 状态最多一个回复。commit 要求累计长度完全一致；流式 SHA256 随写计算，fsync 后原子发布，已有目标条目在成功发布前可继续读。

**不使用 fetch POST 上传**：shouldInterceptRequest 读不到请求体，不能把它设计成接收字节接口。二进制功能需要最小设备 fixture 证明；不支持时禁用本能力，不引入循环 Base64 降级。

### 4.4 读取：同源一次性能力 URL

固定保留路径 `https://<active-origin>/.__hanapp_private__/v1/read/<native-random-token>`，无 query、无文件名/分区/附件身份。token 仅驻留内存，绑定分区、条目、当前 WebView/DocumentLease 和文件安全代际；30 秒内首次 GET 原子消费，打开流后不因 token 超时截断，但受到文档/撤销 fence 限制。

BrowserRuntime 的 shouldInterceptRequest 在静态缓存之前处理整个保留路径。仅 GET、非主框架、精确 origin；不支持 Range。无效 token 返回固定 404，非法方法/Range 返回固定拒绝；**所有保留路径失败都返回本地响应，绝不 fall through 发给服务器**。顶层导航也显式阻止，避免 URL token 出现在网页历史。

成功用 WebResourceResponse 直接输出受控文件流、经验证 MIME、Content-Length、Cache-Control:no-store、X-Content-Type-Options:nosniff、Cross-Origin-Resource-Policy:same-origin；无 Set-Cookie，无 Access-Control-Allow-Origin。打开流时对条目加pin，普通LRU不得淘汰正在读取的条目；流close解除pin。包装流每次read检查文档/分区/安全代际，撤销主动close；清理先关闭所有lease流再删除，不能只撤销URL而留下读取中的旧文件。不是任意 URL 下载代理，不接受外部 URL；不将 Cookie 附到任何额外原生网络请求。

同键新写入不得覆盖读取中的文件快照；固定条目版本，旧版本在最后读者释放后回收。取消、读取异常与显式删除也需要幂等释放 pin，不能只在正常 EOF 时释放。

网页可 fetch 此同源 URL 得到 Blob，再建本页 Blob URL；不持久化读 token/Blob URL。沿用页面 CSP 的 connect-src self 和 img-src blob/self，不自动放宽 CSP。CSP 或 Service Worker 阻止受控读取时，能力探针失败则网页退回官方读取；不能绕开站点策略。

安全能力来自先经主文档桥获取的不可猜 token；资源拦截本身不能证明发起 frame 身份。没有跨源 CORS且 token 不外传，不声称可抵御已掌握 token 的同源恶意脚本。fixture 必须覆盖 token 外泄边界、子框架无法领取能力、跨导航失效、保留路径不会走网络。

## 5. 容量、删除与 B 迁移

- 单条32MiB、单分区128MiB、整个私有缓存256MiB、最多1024条；LRU按成功读取更新。两个写入总预留空间计入配额，超额先淘汰非活跃条目，不够返回 quota；IO失败不损坏旧条目。
- 不自动扫描网页附件或预取历史。首屏优先级、并发需求由网页 Adapter 决定，App只实施硬上限。
- beginCleanup 必须先持久化 blocked 状态与新 privateFilesEpoch，再撤销内存句柄/任务，阻止新的 reader/writer。崩溃后仍以 pending transaction 重试。
- BrowserEnvironment.clean 的删除工作同时等待 WebStorage 清理和私有目录删除；均成功才 completeCleanup。失败保持 cleanupPending、不可创建 WebView/文件任务；不以失败回调冒充完成。
- 文件删除前关闭正在读写的流，晚 ACK、晚 rename、晚任务 completion 均不能重新发布；同步点为存储执行器内安全代际校验与原子提交。前进/后退/普通reload只退役文档能力，不清持久图片分区。
- App 本地退出/换站清全部旧owner私有文件；网页会话注销/删除由明确 clearPartition/remove 接入。只有网页 update 后才能验收该链路。
- private-cache-v1 与 immutable_assets 目录/metadata完全独立；将来 B 只复用此文件能力和清理 fence，不迁移图片进程序包，不随 JS bundle rev 强制删除图片。首版只允许固定 namespace；未来 key/schema 升级须增加受支持版本的能力协商与显式迁移，网页不能自行创建任意 namespace，旧 App 遇到未知版本回退 miss；首版不建设 manifest/签名/程序包管理器。

## 6. 静态资源生命周期重构

先补分类记录，再改任务归属。为资源任务生成仅本进程随机 taskId，记录 producerStarted、joined、headers、bodyRead、diskPublish、consumerDetach、ownershipLost、defaultFallback、cancelReason、bytes/duration。诊断不输出完整 URL、Cookie、scope、附件 key 或用户内容；同一 URL 的关联仅使用本进程加盐摘要。

生产 lease 定义为 native site owner + 静态缓存安全代际，消费 lease 为现有 DocumentLease。生产只服务同来源、同完整URL/版本与同安全代际的请求；不跨站/身份清理合并。实际 Cookie 不作为长期缓存key或日志。

当前 owns 入场校验拆为 producerAllowed 与 consumerAllowed，不能简单删除。仅文档导航退出时取消旧消费；同站点同安全代际任务可完成严格 eligible 检查并入库，旧文档不获得新响应/回调。close/destroy取消无消费者任务；站点切换、退出、清缓存安全代际变化则强制取消并禁止入库。producer在headers与最终发布两处检查安全代际，后者不可省。

把 Skip 分清“未接管资源”与“已接管但消费者退役”：前者保留WebView默认路径，后者返回本地失败/关闭响应，避免默认路径无意重传。已接管网络失败的重试策略在同一生产任务内统一为至多一次明确失败返回，页面reload是新请求；不在插件/原生/WebView三层同时自动重试。只有日志和对照测试能证实是否减少异常重传。

首次完整缓冲/落盘顺序先保留，避免本轮同时引入未完成文件发布与流复用复杂度。原生静态缓存现有 MIME、来源、no-store/private、Vary、Set-Cookie、版本固定校验全部保留。该整理具有可维护价值，但不预设它就是12.6秒重传的根因。

## 7. 实施顺序与检查点

| 步骤 | 交付物与主要文件 | 完成证据 |
|---|---|---|
| P0 固定基线 | 记录git状态、APK/WebView/插件版本；读取本计划和既有协议；不重建整个工程结构 | 未覆盖既有未提交修改；确认旧行为fixture |
| P1 诊断 | RuntimeDiagnostics、StaticAssetCache分类，Application/onCreate/firstDocument补阶段；所有时间保留各自clock domain | RuntimeDiagnosticsTest、静态资源fixture可关联一次任务多个消费者；不触发pageReady |
| P2 安全代际与私有存储 | 新PrivateFileStore、私有metadata与持久fence；SiteRepository/CleanupCoordinator整合 | 纯JVM临时目录测试覆盖提交、损坏、配额、重启、清理晚写入 |
| P3 字节最小闭环 | PrivateFileTransfer模块+二进制入站listener+受控read拦截；先测试专用网页fixture，不接DSH业务 | 实际WebView支持时write/read相等；不支持时capability缺省；无Base64/网络回退/旧replyProxy路径 |
| P4 完整桥与寿命 | BridgeProtocol白名单、capabilities、partition/control请求、BrowserRuntime生命周期归属；MainActivity只转交平台能力 | source/mainFrame/双lease、超限、乱序、超时、旧回调、导航和renderer gone用例 |
| P5 静态生产/消费解耦 | StaticAssetCache任务owner与BrowserRuntime消费lease接缝 | 同版本并发合并、导航中headers晚到、退出中body晚到、清理后不得发布；对照日志说明变化 |
| P6 兼容与文档 | 更新PROTOCOL/SECURITY/COMPATIBILITY/THREE-MODULE-INTEGRATION，交付网页调用fixture及未来Adapter契约 | 下表矩阵、构建、设备fixture证据；明确尚未接入图片业务与未部署 |

模块接口围绕行为：PrivateFileStore负责原子数据/配额/代际，PrivateFileTransfer负责有界协议/读能力，BrowserRuntime负责接线与文档寿命。不要增加通用任务总线，也不要只把MainActivity的方法搬到另一个巨类。

本轮命令：`./gradlew.bat --rerun-tasks :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`；结果为 12 个测试套件、80 个 JVM 测试通过，0 failures/errors/skips；debug APK 与 androidTest APK 全量重建成功。androidTest APK 仅构建，未安装或执行，其中新增两种 SharedPreferences/文件磁盘恢复情形。WebView fixture 包含等待真实 `Committed` 后启动、逐条控制请求/二进制帧超时和明确失败结果；它尚未设备验收，因此不能宣称真实 WebView 二进制闭环成功。插件未接入，未进行生产站点请求。当前代码没有证明 7.4 秒降至 5 秒；此性能结果仍待匹配版本的真实测量。

## 8. 必需验证矩阵

| 场景 | 必须结果 |
|---|---|
| 新App + 旧hanui/hanaccount | 页面/认证/通知/下载保持可用；无文件功能调用，无新缓存业务 |
| 旧App + 新测试网页Adapter | 能力缺失回退原有网络读取；不发送未知大消息 |
| 新App + 新测试fixture | 二进制写入、重启后重新获取分区、读回字节一致；未持久化token/BlobURL |
| 无ArrayBuffer支持/存储失败/CSP不允许 | 持久cache不可用但页面可用，不设置全局启动失败 |
| 非主frame/错origin/旧lease/错partition/伪造scope标签 | 不能获得不属于当前owner的句柄；标签不变成授权结论；不泄露磁盘路径 |
| 缺帧/重复帧/长度不符/2并发以上/超时 | transfer有限失败，临时文件清理，其他请求不被无界占用 |
| 导航/renderer gone/Activity销毁/进程重启 | 旧读URL/transfer不能再用；已完整文件按安全代际可重新打开 |
| read流与write提交中执行退出/换站/clearPartition | 先撤销能力与任务，再删除；晚完成不复活文件；删除失败保持blocked |
| 静态headers晚到/文档epoch推进/多个消费者 | 不串文档、不跨安全代际；证明不会因默认fallback无意再发同一已接管请求 |
| 不同版本静态资源/私有cache schema/B未来升级 | 不混用JS版本；私有数据与程序资源寿命分离；未知版本安全miss |

纯单元测试不能代替WebView二进制/资源拦截/CSP/ServiceWorker行为。P3如果真实设备最小闭环失败，停止公开privateFiles capability，报告具体原因；不转用未经授权的新HTTP服务器、任意本地文件URL或弱权限桥。

## 9. 阶段验收与后续接入

App阶段验收是：新能力独立fixture可用且容量/寿命有界；旧插件兼容；清理事务完整；诊断能区分生产/消费和失败；静态改动无身份保护回退。第一阶段启动按原真实弹窗终点复测并披露数据，不承诺5–6秒；无收益也如实记录。

后续网页图片Adapter必须先完成AuthSession有效判断与官方binding/附件身份核验，再open分区、lookup、领取读URL；miss后调用官方readAttachment，将bytes分块写入，当前显示可以继续用原Blob，不等落盘。logout/身份变化先release/clear，删除remove；同登录跨重启命中才算业务验收。不能缓存Cookie/token/整段RPC，不调用原生DSH接口。

会话历史持久化、稳定跨登录账号身份、签名授权票据以及B程序资源分发均另立评估，不在本次App计划提前建设。用户批准本计划不自动等同批准代码实施或部署。


## 10. 最终本地交付记录

2026-10-07：用户指定 Luna 完成本轮 App 代码更新，Astra 独立审查及针对修正的复核通过。审查发现的撤销读写空窗、锁反转、清理崩溃恢复和孤儿文件配额问题均已修复。最后补充修正双传输连续 ACK 的回压预留释放顺序，新增确定性交错回归，并由 Astra 定向复核通过。

最终验证为 12 套件、80 项 JVM 测试，0 failures/errors/skips；App 与 androidTest APK 全量构建成功。记录在 `artifacts/app-stage-a-luna-backpressure-20261007/`；冻结源码清单 SHA-256 为 `877770D867A89FD6649A96E0A4719E027D926FB99097B5529200F45FE8433FDE`。debug APK SHA-256 为 `25EFCFDB9B51D7510FA1F73C132522D9E4C69091B635966E37711FB198208889`。

本地代码交付完成，不代表设备行为与产品效果验收完成：没有安装/部署、没有执行设备 fixture、两个插件没有更新或接入；真实历史图片跨重启复用、旧插件实际运行兼容和第一阶段启动速度仍待匹配版本组合实测。未提交或推送。
