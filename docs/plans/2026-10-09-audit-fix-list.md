# 2026-10-09 代码审计修复清单

范围：Android 壳（`MainActivity`、私有文件服务、更新模块、静态缓存）与三个自研插件（`dsh-local-hanaccount`、`dsh-mobile-hanui`、`dsh-session-cache-sync`）。
方法：四路只读审计，加父代理对关键引用逐条回读源码。**没有真机**（`adb devices` 为空），所有结论来自读码与 JVM/Node 测试，设备行为未验证。

严重度：P0 可直接利用 / P1 削弱安全边界 / P2 真实缺陷 / P3 质量。工作量：S 小改、L 需设计或评审。
状态：✅ 已修并带测试（修复前测试失败已核对）　⏳ 小任务，尚未修　🔶 大任务，留给你评审

## A. 已修复（本仓库已推送；插件改动见 A2）

### A1. Android 壳

| ID | 严重度 | 位置 | 问题 | 状态 |
|---|---|---|---|---|
| A-01 | P2 | `update/AppUpdateInstaller.kt` `safeApkFileName` | 清单里的 `apkName` 为 `..` 或 `.` 时，经字符过滤后仍解析到 `updates/` 的上级目录 | ✅ |
| A-02 | P2 | `update/*Source.kt`、`AppUpdateInstaller.downloadClient` | `followSslRedirects(true)` 允许 https→http 降级跟随 | ✅ |
| A-03 | P2 | `browser/StaticAssetCache.kt` | 分块响应无 Content-Length，`body.bytes()` 在 32 MiB 检查前已全量入内存（对照实验：修复前读取 100 MB） | ✅ |
| A-04 | P2 | `update/AppUpdateLocator.kt` | 托管源超时/DNS/TLS 抛异常时不会回退到 GitHub，只有 HTTP 失败才回退 | ✅ |
| A-05 | P2 | `update/HostedAppUpdateSource.kt` | 清单字段为对象/数组时 `.jsonPrimitive` 抛异常 | ✅ |
| A-06 | P2 | `MainActivity.startAutoUpdateCheck` | Loading/Visual/Restored 每个事件都重新拉取，GitHub 匿名限额 60 次/小时 | ✅ 每次启动一次 |
| A-07 | P2 | `MainActivity` 构造 `AppUpdateInstaller(this)` | Activity 被阻塞下载持有，最长 180 秒 | ✅ 改用 `applicationContext`（下载本身仍不可取消，见 B-02） |
| A-08 | P2 | `browser/SiteRepository.rememberDocument` | 最后文档 URL 连同 `?token=` 等查询串明文写入 SharedPreferences，下次冷启动回放 | ✅ 只存 `scheme://authority/path`，读取时也清洗旧值 |
| A-09 | P3 | `update/*Source.kt` | 清单先 `.string()` 再判长度，超大清单被整体缓冲 | ✅ |
| A-10 | P3 | `MainActivity` 重新加载对话框 | `site!!` 在对话框打开期间可能被登出置空 | ✅ |
| A-11 | P3 | `docs/` | 断链（`CHANGELOG.md:417`、归档审核 2 处）；已核对非证据类链接 0 断链 | ✅ |
| A-12 | P3 | `scripts/test-session-cache-force-stop.ps1` | 硬编码 `C:/Users/han/...adb.exe` | ✅ 改读 `$env:ADB`，默认 `adb` |

### A2. `dsh-local-hanaccount`（改动在插件仓库工作区，**未提交**）

插件仓库里本来就有约 35 个未提交文件（你的在途工作），我没有替你提交或混入，只改了 3 个源文件并新增 1 个测试文件。

| ID | 严重度 | 位置 | 问题 | 状态 |
|---|---|---|---|---|
| P-01 | P1 | `src/api.js` `auth/login` | 锁定检查在 `await readOperationBody()` 之前，N 个并发请求全部通过，5 次限制被一次突发绕过 | ✅ 请求体读完后再检查一次 |
| P-02 | P2 | `src/lib/store.js` | Cookie `dsh_gate_token=__proto__` 命中继承属性，触发同步 fsync 写盘与安全广播（未认证即可触发） | ✅ `Object.hasOwn` |
| P-03 | P2 | `src/lib/key-auth.js` | `createVerify('ed25519')` 抛异常被吞成 `false`，SSH 密钥登录永远失败（失败即关闭，非绕过） | ✅ `crypto.verify(null, …)` |
| P-04 | P2 | `src/lib/key-auth.js` | 密钥 id 每次加载随机生成，`DELETE keys/:id` 匹配不到，密钥无法撤销。P-03 修好后会升为 P1，所以一并修 | ✅ id 由公钥哈希派生 |

测试：`test/audit-2026-10-09.test.js` 6 个用例，其中 4 个在撤掉修复后失败，另 2 个是配套的正向用例。并发登录用例走真实的 `createApiHandler`。插件全量 171 项，165 通过、0 失败、6 跳过（跳过项是原有的需要 `DSH_ATTACK_OPT_IN` 的实攻击用例）。

## B. 小任务，尚未修（⏳）

这些我都确认过位置，改动都不大，只是这一轮没来得及做。建议下一批直接处理。

### Android 壳
| ID | 严重度 | 位置 | 说明 |
|---|---|---|---|
| B-01 | P2 | `MainActivity` 约 :492/:534 | 更新下载完成时若应用已退到后台，Android 10+ 会静默拦截 `startActivity`，但界面仍显示"请在系统界面确认安装"。应保存 Ready 结果，在下次 `onResume` 安装 |
| B-02 | P2 | `AppUpdateInstaller.kt:90` | 阻塞 `execute()` 不响应协程取消。用 `invokeOnCancellation { call.cancel() }` |
| B-03 | P2 | `BrowserRuntime.kt:243` | `onPause` 里 UI 线程调用 `CookieManager.flush()`，而代码注释自己说过部分 OEM 上这会让 Chromium 崩溃 |
| B-04 | P2 | `MainActivity` :175/:179/:967 | 内容提供者 I/O（`openFileDescriptor`、`openOutputStream`、`deletePartial`）在主线程，云盘类提供者可能 ANR |
| B-05 | P2 | `PrivateFileStore.kt:124,193` | `lookup`/`openSnapshot` 在全局锁内对最大 32 MiB 文件整体重算哈希。改为按（revision、长度、mtime）缓存已验证状态 |
| B-06 | P2 | `NativeReadRegistry.kt:69` | 已消费的读取令牌永不过期；32 个泄漏槽后 `openRead` 返回 quota，且每个槽还钉住一个旧 revision |
| B-07 | P2 | `SiteRepository.kt:100` 等 | 本机登出时 `deleteForCleanup` 的返回值被忽略；`DELETE_BROWSING_DATA` 不受支持时，下次启动不会重试私有文件清理 |
| B-08 | P3 | `MainActivity` 对话框 | `onDestroy` 只关进度框，其它对话框可能泄漏窗口；`configChanges` 缺 `uiMode|locale|fontScale|density`，深色模式切换会在更新中重建 Activity |
| B-09 | P3 | `MainActivity:728-729` | 通知点击后进程被杀，从最近任务恢复时会回放旧通知 URL，应在 `savedInstanceState != null` 时忽略 |
| B-10 | P3 | 多处 | `catch (Exception)` 吞掉 `CancellationException` 并弹"下载不可用"；多处吞异常不记日志（`MainActivity` :153/:165/:191/:291/:800/:973、`PrivateFile*`、`BrowserStorage`） |
| B-11 | P3 | `MainActivity` :806 | 静默 `return` 不回复，页面 Promise 只能等超时 |
| B-12 | P3 | `GitHubAppUpdateSource.kt:75-80` | 生产代码里允许 `http://127.0.0.1`（被网络安全配置挡住，但测试专用放行不该随包发布） |
| B-13 | P3 | `AppUpdateInstaller.kt:54` | SHA-256 可选；畸形哈希被静默降级为 `null` 而跳过校验。签名与 versionCode 校验仍在，所以只是纵深防御 |
| B-14 | P3 | 死代码 | `MainActivity` 未用 import 6 处和 `committedUrl`、`DshApplication` 的 `BrowserSession` import、`LaunchCoverState.onDeadline/onTimeout`、`BrowserStorage` 的 SDK<28 分支（minSdk=28）；`BrowserPolicyTest:140` 钉住死代码；`ARCHITECTURE.md:17` 与 `:40` 自相矛盾 |
| B-15 | P3 | `DshApplication.kt` | `retireLegacyBackgroundWork` 每次启动都调用 WorkManager 与 `stopService`，应加一次性标志 |
| B-16 | P3 | `PrivateFile*` | 快照钉住在异常时泄漏、`scheduleAtFixedRate` 一次异常即永久停摆、`close()` 后惰性创建 `transfers`、`maxFiles` 可被并发写入超过、损坏条目不清理、`readFence` 每次操作读盘 |
| B-17 | P3 | 测试 | `PrivateFileStoreTest:31-43,126-139` 末尾 `assertNull(lookup)` 是空断言（从未写入过）；`:77` 名为 LRU 实际只有一个候选；`PrivateFileService` 无 JVM 测试 |
| B-18 | P3 | `docs/THIRD_PARTY_NOTICES.md`、`LICENSE` | 声称不内置第三方物；品牌素材与版权人（"DSH Mobile contributors"）需要你确认 |
| B-19 | P3 | `.github/workflows` | CI 未跑 JS 兼容测试（需 `--expose-gc`）和插件测试；Release 在缺密钥时静默产出未签名 APK |

### 插件
| ID | 严重度 | 位置 | 说明 |
|---|---|---|---|
| B-20 | P1 | `dsh-session-cache-sync/src/client/sync-owner.js:138-146` | `fenceScope` 只清理本次页面加载里见过的分区；登出或服务端会话过期后换新 scope，旧分区里的完整明文历史永久残留。改用 `cachePartitionKey(origin, scope)` 直接清，并在 `activatePartition` 时清掉同 origin 的其它分区 |
| B-21 | P2 | `cache-db.js:137-146` | `activatePartition` 无条件写 `retired:false`，可复活刚被清空的分区 |
| B-22 | P2 | `cache-db.js` 多处 | 整库 `getAll()` 在事务内（`activateStaging`/`clearPartition`/`stageOpening`），大缓存上有崩溃 WebView 的风险，改用 `IDBKeyRange` |
| B-23 | P2 | `dsh-session-cache-sync/src/host/log-state.js`、`index.js:9` | 每次登录新 scope 产生新状态文件且从不清理；默认目录是相对 cwd 的 `.dsh-session-cache-sync` |
| B-24 | P2 | `dsh-mobile-hanui/src/image-adapter.js:57,113` | `void cache.invalidateSession(id)` 无 `.catch`；`mutateLedger` 在 64 行上限时拒绝，造成未处理的 rejection 且不清理该会话分区 |
| B-25 | P2 | `dsh-mobile-hanui/src/image-cache.js:41-51` | 一个损坏的 localStorage 值使 `ledgerInvalid` 持续整个页面生命周期，登出清理在空 `catch {}` 里静默失败 |
| B-26 | P2 | `dsh-local-hanaccount` `store.js:79,165` | 会话表只在查询该 token 时才删除过期项，无定时清理；白名单 IP 的无 cookie GET 每次新建持久会话 |
| B-27 | P2 | `dsh-local-hanaccount` `passkey.js` | `requireUserVerification:false`，且 passkey 登录路径没有锁定与失败计数 |
| B-28 | P2 | `dsh-local-hanaccount` `visitors.js`、`passkey-store.js` | 未认证请求触发同步 fsync 整文件重写，字段无长度上限 |
| B-29 | P3 | 其余 P3 | IPv6 字符串相等匹配、`deny` 可覆盖回环导致自锁、`allow: 0.0.0.0/0`、`PUT config` 无需旧密码、`lockout` 配置未校验、`peers/connect` SSRF 无超时、会话 token 明文存盘、`dataDir` 非 0700 等 |

## C. 大任务，留给你评审（🔶）

| ID | 严重度 | 位置 | 内容 | 为什么不直接改 |
|---|---|---|---|---|
| L-01 | **P1** | `dsh-local-hanaccount` `ip.js:118-127,197-210`、`store.js:28-35`、`gate.js:180` | **回环代理失败即放行**：`trustProxy` 默认开且回环永远在白名单。任何从 127.0.0.1 连入且不带 `X-Real-IP`/`X-Forwarded-For` 的代理（frp、`ssh -R`、cloudflared 改写 Host、tailscale serve）都被当作本机操作员，直接获得免登录；未设密码时 `auth/setup` 也会放行，首个访客接管实例。另外 XFF 取第一跳（客户端可控） | 要把代理信任改成显式 opt-in，会改变默认行为和部署方式，需要你定策略 |
| L-02 | **P1** | 同上 `api.js:232`、`store.js:199-202` | 锁定按 IP 字符串计数，IPv6 /64 内可轮换约 2^64 个地址；`state.lockouts` 无上限、每次失败整文件重写；`scryptSync` 阻塞事件循环，可叠加成 CPU 型 DoS | 要定 IPv6 聚合粒度、全局失败计数与退避、scrypt 异步化，是行为变更 |
| L-03 | P2 | `sync-owner.js:27-32,185-192` | **缓存无淘汰且每次 open 上送全部哈希**：`maxSessionEvents/maxSessionBytes` 已定义但从未使用，`touchedAt` 只写不读；20 万事件的会话每次打开约 15 MB+ 请求，超过传输上限后该会话永久同步失败 | 需要协议改动（区间/Merkle 摘要或分级 reset） |
| L-04 | P2 | `PrivateFileService.kt:81-90` 等 | **锁内磁盘 I/O 可阻塞 UI 线程**：`bindDocument` 在 UI 线程持服务锁调用 `revokeAll`，而后者会等待持注册表锁做 `FileInputStream.read` 的读者，读者又等持全局锁做哈希/fsync 的写者。32 MiB `commitWrite` 期间导航会卡数秒 | 涉及锁层次重构，需要真机复现与压测 |
| L-05 | P2 | `MainActivity:821,827,699`、`PlatformCredentialStore` | Keystore 与 `commit()` 在主线程；HyperOS/三星 TEE 上可达数百毫秒。回包需回到主线程并保持租约校验 | 改动覆盖桥接回复路径，需要真机验证 |
| L-06 | P2 | `MainActivity:201-216,160,182` | 图片保存：对话框写 25 MiB，blob 路径实际只有 8 MiB；同步 XHR 加逐字符拼接再把约 11 MB base64 过 `evaluateJavascript`；跨源 https 图长按提示自相矛盾 | 需要重写传输方式（分块） |
| L-07 | P3 | `PrivateFileStore.kt:246` | 围栏文件损坏后私有文件被永久禁用，无自愈路径 | 属设计问题 |
| L-08 | P3 | 发布 | 目前没有任何 Release/tag，Actions 密钥为空（无签名密钥），`remote-latest.json` 仍是 `0.0.0` 占位；`isMinifyEnabled=false` | 涉及签名密钥与发布流程，需要你来配置 |
| L-09 | P3 | 真机验证 | HyperOS 方案"已完成"项仍缺：K80 冷启动烟测×5、缺失 API 夹具真机测试、phase3 对比实验。本次完全没有设备 | 需要设备 |

## D. 同时完成的仓库收尾（GitHub 侧）

- 新仓库 `Z-6354/deepseek-harness-android`：公开、非 fork、MIT、默认分支 `main`；历史中服务器 IP、私钥、令牌、keystore 口令 0 命中（已对全部提交逐一 `git grep`）。
- 已开启：Secret scanning、Push protection、Private vulnerability reporting（`SECURITY.md` 里写的就是这个入口，之前是关的）、Dependabot 安全更新与漏洞告警。
- 新增：`.github/dependabot.yml`（Gradle 与 Actions，每周）、`.gitattributes`（固定 `gradlew` 为 LF，Linux CI 依赖它；已确认不改动任何已提交文件）。
- 分支保护**未开启**（`main` 目前无保护）。个人仓库开启后会影响你直接推送，留给你决定。
- 基线：JVM 单测 102/102、lint 通过。

## E. 审计覆盖的盲区

- `dsh-mobile-hanui` 的 `client.js`（约 250 KB 生成物）只做了 grep，没有逐行读，也没核对生成物与 `src` 一致（`hanaccount` 的 `bundle.mjs --check` 通过）。
- `hanaccount` 的 `client.entry.js`、`login-page.js` 未读，测试正文只读了一部分。
- `basic.test.js:374` 与 `:401`（"excluded prefix …"）名称相互矛盾，且 `isAuthExcluded`/`shouldWrapPrefix` 疑似未被使用，需要核实是否为失效断言。
- 所有 Android 行为结论都没有经过真机。
