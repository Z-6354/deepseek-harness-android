# 启动链路解耦审核（2026-10-07）

> 该审核的路由/会话持久化方案已被 [运行时架构计划](../plans/2026-10-07-runtime-architecture-plan.md) 取代；保留历史，当前实现不再采用原生 seed、短定时恢复门闩或揭罩后经主页跳转。

**状态**：Accepted（阶段 A/B 已落地：hanui 解耦 pageReady；壳侧 DocumentEpoch + LaunchCover 观察者 + 展示预算）  
**范围**：Android 壳 `app/src/shell`、网页侧 `pageReady` / 会话恢复门闩（`dsh-mobile-hanui`）  
**基准**： [2026-10-06-current-baseline.md](../plans/2026-10-06-current-baseline.md)、[../ARCHITECTURE.md](../ARCHITECTURE.md)、[../THREE-MODULE-INTEGRATION.md](../THREE-MODULE-INTEGRATION.md)、[../PROTOCOL.md](../PROTOCOL.md)  
**不改**：DeepSeek Harness 官方源码；不把业务协议搬进 App

---

## 1. 审核结论（摘要）

仓库三模块边界清晰（壳 / hanui / hanaccount），单 `:app` 结构健康。  
**问题集中在壳内启动切片**：展示遮罩、文档代际、超时预算、会话恢复与揭罩信号缠在同一状态机与 `MainActivity` 编排里。

产品原则（本审核锁定）：

> **遮罩只挡住「连接到可展示」的视觉过程；不得阻挡内容管道启动或推进。**

由此得出：加载过长与「加载超时 / 两段入场」主要是**就绪契约过宽 + 展示与管道共状态**，不是遮罩 View 本身拦住了网络。

| 症状 | 结构根因 |
|---|---|
| 假「加载超时」 | 10s 预算从 `createBrowser` 起算；`pageReady` 还被会话恢复最多再堵 ~4s |
| 两段入场感 | 超时/`Failed` 时 `keepSplash=false` 再露出品牌罩文案；Splash 与罩未保持同一展示面 |
| 冷启偏慢（感知） | 揭罩条件 = Boot 结束 +（可选）会话就位；可选工作拖住 TTFI |

---

## 2. 现状结构审核

### 2.1 仓库分层

| 路径 | 角色 | 审核意见 |
|---|---|---|
| `app/src/shell` | 生产 Kotlin / assets | 正确；唯一 APK 实现面 |
| `app/src/main` | Manifest + 资源 | 正确 |
| `docs/*.md` | 运行时契约 | 有效 |
| `docs/plans/2026-10-06-current-baseline.md` | 本阶段指示 | 有效 |
| `docs/plans/` 大量 10-04/10-05 文 | 历史计划 | 与 README「只留当前阶段」不符，应迁 `docs/archive/`（文档卫生，不阻塞解耦） |
| `artifacts/` | 取证 | 非产品路径 |
| `connection/KeepAliveWorker` | 遗留 | `DshApplication` 已取消旧任务；可后续删除 |
| `dsh-local-hanaccount` / 兄弟仓 `dsh-mobile-hanui` | 插件 | 不进 Gradle；契约经 HTTPS origin |

### 2.2 壳内类型职责（与启动相关）

| 类型 | 声称职责 | 实际越界 |
|---|---|---|
| `MainActivity` | Activity 编排 | 同时驱动 WebView 管道、罩 UI、定时器、会话持久化（~900 行上帝对象） |
| `LaunchCoverState` | 启动罩状态 | 还拥有 `generation`、超时分类、gate、restore、`onResume` 强行 visual 完成 |
| `BrowserSession` | 名称像「浏览会话」 | 实为 WebView **provider 预热**；与 `lastSessionId` 会话恢复同名混淆 |
| `WebCompatibility` + `SiteRepository` | 种子 / 元数据 | 合理；属管道侧，不应进罩门闩 |
| `WebsiteBridge` / `BridgeProtocol` | 桥 | `pageReady` 特殊放行合理；语义过载在网页侧 |
| `StaticAssetCache` 等 | 缓存/下载/清理 | 与启动正交，结构健康 |

### 2.3 测试结构缺口

| 已有 | 缺口 |
|---|---|
| `LaunchCoverStateTest` 状态机较全 | 罩与 `generation` 未分离，难测「纯观察者」 |
| `BrowserPolicyTest` 源码字符串锁 `10_000` 等 | 把实现常数当契约，阻碍预算策略调整 |
| `WebCompatibilityTest` 种子形状 | 无「种子不得阻挡揭罩」契约测 |
| 无管道编排单测 | 缺 load → commit → interactive → uncover 序列测 |

### 2.4 耦合示意（现状）

```text
MainActivity
  ├─ owns LaunchCoverState   (generation + cover + timeout + resume-uncover)
  ├─ drives WebView load/restore/destroy
  ├─ arms 10s deadline at createBrowser
  └─ pageReady (hanui: Boot ∧ sessionResume?) → uncover

内容管道与展示罩共享状态；可选会话恢复可推迟揭罩直至撞上 Failed。
```

---

## 3. 选用的软件思想与模式

按「小接口、深模块、可测缝」选型；不引入多余抽象层。

| 思想 / 模式 | 用在何处 | 不用在何处 |
|---|---|---|
| **关注点分离（SoC）** | 管道推进 vs 罩展示 vs 会话增强 | 不把三件事塞进一个 FSM |
| **单一职责（SRP）** | `DocumentEpoch` / `BrowsePipeline` / `LaunchCover` 各一事 | 禁止 `LaunchCover` 再 bump generation |
| **观察者（Observer）** | 罩订阅管道事件；罩不回调停加载 | 罩不是中介者，不调度 `loadUrl` |
| **管道 / 阶段（Pipeline）** | 冷启阶段：Warmup → Load → Document → Interactive →（Settled） | 不做成重型工作流引擎 |
| **渐进就绪** | `interactive` 揭罩；`settled` 不挡 TTFI | 不用单一 `pageReady` 兼 TTFI+TTFC |
| **开闭原则（OCP）** | 新增「等某 UI」只加 settled 类信号，不改揭罩默认 | 避免再改 `LaunchCoverState` 大 switch 塞业务 |
| **依赖倒置** | Activity 依赖管道/罩的窄接口；单测用事件序列 | 不为 DI 框架而拆 |

**明确不采用**：完整 MVC/MVP 重写 UI；EventBus 全局总线；在壳内复刻会话状态机。

---

## 4. 目标架构

### 4.1 模块与接口（深模块）

```text
┌──────────────────────────────────────────────────┐
│ MainActivity（薄适配：系统回调 → 管道 / 罩）        │
└───────────┬───────────────────────┬──────────────┘
            ▼                       ▼
   BrowsePipeline              LaunchCover
   （驱动）                      （观察者）
            │                       ▲
            └──── LaunchEvent ──────┘
```

| 模块 | 接口（调用方需知） | 实现内聚 |
|---|---|---|
| **`DocumentEpoch`** | `generation`、站内 commit URL、是否允许桥（除 pageReady） | 与罩表面无关 |
| **`BrowsePipeline`** | `start(site)` / `restore` / `load` / `destroy`；发出 `LaunchEvent`；拥有 WebView 与 seed | 预热协作、`lastDocument`、桥 bind、错误是否停导航 |
| **`LaunchCover`** | `onEvent(LaunchEvent)` → 可见性、文案、是否 keepSplash | **不停管道**；超时只改展示 |
| **Session 增强** | 种子 + hanui reconcile | 默认不进入揭罩条件 |

建议事件（罩只读）：

| 事件 | 管道含义 | 罩反应 |
|---|---|---|
| `LaunchStarted` | 新浏览器/新启动 | 进入加载表面；可 arm 展示预算 |
| `DocumentStarted` | 主框架导航开始 | 保持遮罩；刷新代际在管道 |
| `InternalCommitted` | 站内文档可提交 | 连接阶段前进；可重置/收窄预算 |
| `Interactive`（今日 `pageReady` 收窄语义） | Boot/门禁结束，可安全展示 | 请求 visual 后揭罩 |
| `VisualComplete` | 首帧就绪 | Ready；放 splash |
| `TransportFailed` / `CertificateFailed` / `StorageFailed` | 硬失败 | Failed 文案；**不 cancel 管道除非管道自己决定** |
| `DisplayBudgetExceeded` | 太久仍不可展示 | 罩提示；管道继续；若随后 `Interactive` 仍可揭罩 |
| `RestoredWithoutReload` | 热路径复用文档 | 立即 Ready |
| `GateRejected` | 401/403 等需露出页面 | 揭罩（现有行为保留） |

### 4.2 两条时间线（验收拆分）

| 名称 | 定义 | 遮罩 |
|---|---|---|
| **TTFI**（首屏可交互） | 认证态下主界面可点 | 到 `Interactive`+visual 揭开 |
| **TTFC**（内容就位） | 上次非空会话已打开或明确放弃 | **不挡**揭罩；罩下或揭罩后完成 |

预算时钟绑定 TTFI（建议从 `loadUrl` / 首次 `DocumentStarted` 起算），不绑定 TTFC，不起算于 WebView 构造整段。

### 4.3 与「罩不挡启动」的不变式

1. 发出任何罩事件**不得**成为 `loadUrl`、插件执行、会话 `open` 的前置条件。  
2. `DisplayBudgetExceeded` **不得**调用 `stopLoading` / `destroy`（除非另有用户动作）。  
3. 系统 Splash 与品牌罩只服务视觉连续；`keepSplash` 不是管道闸门。  
4. 会话恢复失败或超时只影响 TTFC，不产生「加载超时」硬失败表面（可用轻提示或不提示）。

---

## 5. 跨模块契约调整（待实现时写入 PROTOCOL / THREE-MODULE）

| 项 | 现状 | 目标 |
|---|---|---|
| `pageReady` | Boot 消失 ∧ 非 sessionResume.blocked | **仅**表示可展示（Boot/门禁结束）；语义 = `Interactive` |
| 会话恢复 | 可 `blocked=true` 推迟 `pageReady` 最多 4s | 与 `pageReady` 解耦；并行于管道；可选后续 `settled` 信号（非揭罩必需） |
| App 超时 | 10s → `Failed` + 撤 splash | 展示预算超时 → 提示态；管道继续；迟到的 `Interactive` 可揭罩 |
| 文档 | THREE-MODULE「splash+罩到 Ready」 | 保留单段视觉；补充「罩为观察者、预算不杀管道」 |

兼容策略：先改 hanui 停止用会话阻塞 `pageReady`，App 侧收窄超时语义；若需显式 `settled`，再加可选桥字段或独立事件（非本审核阻断项）。

---

## 6. 落地阶段（实现顺序）

### 阶段 A — 契约与网页（收益最大、改动面小）

1. hanui：`sessionResume` 不再挡住 `pageReady`；恢复在罩下/揭罩后继续。  
2. 确认登录页 / Boot 路径仍会发出 `pageReady`。  
3. 手工冷启：无假超时；会话仍尽量回到上次（允许揭罩后短跳转）。

### 阶段 B — 壳状态拆分

1. 从 `LaunchCoverState` 抽出 `DocumentEpoch`（generation + commit）。  
2. `LaunchCover` 仅 `onEvent`；单测用事件序列覆盖现有 `LaunchCoverStateTest` 行为中的**展示**部分。  
3. `MainActivity`：预算从 `DocumentStarted`/`loadUrl` 起算；`DisplayBudgetExceeded` 不停管道；Failed 与 Splash 同一视觉表面（避免 splash→鲸鱼两段）。  
4. 重命名或文档化 `BrowserSession` → `WebViewWarmup`（或等价注释 + ARCHITECTURE 更正）。

### 阶段 C — 编排瘦身（可选同一 PR 或随后）

1. `BrowsePipeline` 承接 `createBrowser` / Client 回调中的管道逻辑。  
2. 删除或归档 `BrowserPolicyTest` 中对魔法毫秒数的字符串锁；改为行为断言。  
3. 更新 `ARCHITECTURE.md` / `THREE-MODULE-INTEGRATION.md` / `PROTOCOL.md` 与本文一致。

### 阶段 D — 文档卫生（可并行）

1. `docs/plans/` 仅保留 baseline + 本文；其余迁归档或并入 `MIGRATION-HISTORY`。  
2. 评估删除 `KeepAliveWorker` 死代码。

---

## 7. 验收标准

| # | 标准 |
|---|---|
| A1 | 冷启有缓存：罩为单段视觉，无「Splash 再切鲸鱼再进页」的成功路径 |
| A2 | 日常冷启 TTFI 不因会话恢复门闩人为 +≤4s；会话恢复失败不出现「加载超时」硬失败 |
| A3 | 展示预算用尽后 WebView 仍继续加载；随后可展示时仍能揭罩 |
| A4 | 单元测：罩状态机无 generation 所有权；管道事件可独立测 |
| A5 | 热启（进程仍在）：不重演启动罩、不整页重载（既有基准） |
| A6 | 不改官方源码；安全门禁（精确 origin、无 `addJavascriptInterface`、ReplyProxy 规避）不变 |

性能目标仍以 baseline 为准：热启 P95 &lt; 1s；有缓存冷启可交互 P95 &lt; 5s。解耦本身不替代 composition / 缓存优化，但消除假超时与错误串行。

---

## 8. 非目标

- 预置官网 HTML 快照或 Service Worker 整站离线包  
- 修改官方 Boot / 批量禁用客户端插件（已知会弄坏 Boot）  
- 壳内实现聊天 DTO / 原生业务 WebSocket  
- 为解耦引入 DI 框架或多 Activity  

---

## 9. 决策记录

**决定**：采用「管道驱动 + 罩观察 + 渐进就绪（interactive / settled）」解耦；会话恢复移出揭罩关键路径。  

**替代方案与否决**：

| 方案 | 否决理由 |
|---|---|
| 仅将 10s 改为 20s | 不缩短加载，不消除两段 Failed 展示 |
| 继续用会话阻塞 `pageReady` 至恢复完成 | 与「罩不挡内容 / 缩短 TTFI」冲突 |
| 大爆炸重写 MainActivity UI 层 | 风险高、与本阶段目标不成比例 |

**后果**：

- 揭罩后可能出现极短的会话切换（换 TTFI）；需产品可接受。  
- PROTOCOL / hanui / 壳需同版本协调发布。  
- `LaunchCoverStateTest` 将随拆分改写为事件驱动测。

---

## 10. 审核签字栏

- [x] 产品原则：揭罩优先于「会话一定已打开」（已写入契约）  
- [x] 阶段 A（hanui）与阶段 B（壳 DocumentEpoch / 展示预算）已实现；冷启需同窗口部署 hanui + APK 验证  
- [x] PROTOCOL / THREE-MODULE / ARCHITECTURE 已与本文对齐（BrowsePipeline 抽出仍属阶段 C 可选）  
- [ ] 真机：有缓存冷启无假「加载超时」；会话仍尽量恢复（允许揭罩后短跳）  

---

## 11. 参考取证

- `artifacts/hotstart-diag/remeasure-2026-10-06-after-shell.md` — 有缓存冷启揭罩 ~3.4–3.8s；`documentCommitted→pageReady` ~1.5s  
- `artifacts/hotstart-diag/composition-first-screen-audit.md` — 插件税与激进裁剪失败记录  
- 对话结论：罩 = 视觉观察者；内容管道始终前进  
