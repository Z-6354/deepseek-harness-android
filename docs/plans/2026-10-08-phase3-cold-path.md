# 第三阶段：压冷启墙钟（native / WebView / 资源）

日期：2026-10-08。承接第二阶段设备结论：加载罩已消失、会话可直达，但有缓存 toReady 中位仍约 **7.6 s**（`am start` 已占约 5.7–7.8 s）。详细预算见 [启动架构重构评估](2026-10-07-startup-rearchitecture-assessment.md)；测时脚本见 `plugins/dsh-mobile-hanui/scripts/cdp-phase2-cold-timing.py`。

## 相对前两阶段

| 阶段 | 已交付 | 本阶段不再做 |
|---|---|---|
| 一：会话缓存 | 落盘 / 差量 / force-stop / live | 重验缓存协议 |
| 二：直达会话 | 宿主隐藏加载罩、挡路 bug、双计时测法 | 再叠「正在打开」观感改动 |
| **三：冷路径** | **压进程→文档→会话 ready 墙钟，冲有缓存中位 ≤5s** | — |

## 唯一叙事

有缓存 force-stop 冷启：从点开 App 到会话首屏可读且输入可点，**中位数 ≤5s**（同 MuMu / APK / WebView / 服务器组合；不含首开全量、不含换站清 IDB）。不靠提前假 pageReady 或仿制会话 UI。

## 现场 → 范围

| 第二阶段证据 | 第三阶段怎么做 |
|---|---|
| `am start -W` ≈ 5.7–7.8 s | **主线**：缩短 Activity / WebView 冷启与首文档路径 |
| am 返回后到 ready 多约 0.5 s | 网页恢复已短；只做有证据的残余（认证前移、非必要插件后置等） |
| `sessionFirstPaint` 打点不稳 | 稳定双计时落盘（含 `syncCaughtUp` 若有公开信号） |
| 大 JS / 静态资源 / 偶发重传 | 按评估候选 A：资源任务寿命、命中路径、重复传输闭环 |

## 工作切片（建议顺序）

1. **分段基线（先测再改）**  
   多轮记录：process → Activity → loadUrl → document commit → pageReady / visual → `sessionFirstPaint`（终点 A）→ 可选 sync（终点 B）。对齐 LaunchTrace / pageLifecycle。

2. **Native / WebView 冷路径**  
   预热是否计入、首帧文档是否串行多余等待、LaunchCover 与真实 ready 是否仍错位。只改有对照收益的项。

3. **资源与脚本**  
   静态缓存命中、异常重传、大包解析是否阻塞首阶段；不改官方 DSH 核心字节。

4. **插件调度（有证据才动）**  
   必要认证/配置前移、非必要初始化后置；不得把工作挪出计时窗口冒充提速。

5. **验收采样**  
   有缓存冷启建议 ≥10 轮（冲门禁可开 20）；报告中位 / 均值 / P95 / 最慢 / 失败；披露相对第二阶段 7.6 s 的净改善。

## 不做

- 重做 session-cache 或欢迎专项  
- 伪造欢迎 / 改官方核心包  
- 仅靠提前 pageReady、静态假会话、把同步完成算进首屏  
- 把换站清 IDB / 首开全量样本混进 ≤5s

## Done

1. 分段计时可复现，能指出最大耗时段。  
2. 有缓存冷启 toReady（终点 A）中位数 **≤5s**，或证明在完整语义下不可达并给出对照数据后停止扩大范围。  
3. 第二阶段加载罩 / 直达行为无回退；相关回归仍绿。

## 基线（第二阶段末）

| 指标 | 值 |
|---|---:|
| 样本 | 5 轮有缓存 force-stop |
| toReady 中位 | 7641 ms |
| 范围 | 6287–9832 ms |
| 证据 | [artifacts/phase2-cold-timing-20261008.json](../../artifacts/phase2-cold-timing-20261008.json) |

## 分段基线（第三阶段，有缓存 force-stop ×5）

脚本：`plugins/dsh-mobile-hanui/scripts/cdp-phase3-segment-baseline.py`（LaunchTrace 域 + `am start -W` + 墙钟 toReady）。

### 正式对照（无 WebView gate）

| 段 | 中位 (ms) |
|---|---:|
| wall toReady | **5435** |
| `am` TotalTime | 4905 |
| am 返回 → UI ready | 438 |
| begin → loadUrl | 310 |
| loadUrl → commit | 1098 |
| **commit → pageReady** | **2309** |
| 证据 | [artifacts/phase3-segment-baseline-ungated-20261008.json](../../artifacts/phase3-segment-baseline-ungated-20261008.json) |

相对第二阶段墙钟中位 7641 → **5435**（约 −2.2 s）；仍未进 ≤5s。

### 最大耗时（按墙钟与文档内拆分）

1. **`am start` TotalTime ~4.9 s** — 进程/Activity/首帧路径占墙钟大半；与 toReady 重叠，不是「am 之外另加 4.9 s」。
2. **commit → pageReady ~2.3 s** — 文档内最大段（会话恢复 / 大脚本解析等）；cached `client.js` CPU 量级约 0.8–0.9 s 属其子项证据，非独立墙钟。
3. loadUrl → commit ~1.1 s；am 返回后到 ready 残余 ~0.4 s。

### 失败对照：等 warmup settled 再建 WebView

在 `createBrowser` 前 `BrowserSession.whenSettled` 门控：wall 中位 **5708**（约 +273 ms），已回退，不保留。证据：[artifacts/phase3-segment-baseline-20261008.json](../../artifacts/phase3-segment-baseline-20261008.json)。

### 对照实验：bookmark clear 不等 workspace `listReady`

| 项 | 结果 |
|---|---|
| 改动 | `sessionsReady` 且目标存在时 clear 路径立即 `prepare+open`；`forcePrepare` 时也 `cancelInitial` |
| wall 中位 | **5440**（基线 5435） |
| commit→pageReady 中位 | 2377（基线 2309） |
| 结论 | **无显著收益**（本机有缓存冷启多半走 adopt/已就绪目录，等 `listReady` 不是主耗时） |
| 去留 | 逻辑仍保留（对齐 discovery、防目录卡住）；证据 [artifacts/phase3-segment-baseline-listready-skip-20261008.json](../../artifacts/phase3-segment-baseline-listready-skip-20261008.json) |

### 下一步（只动有对照收益的项）

`commit→pageReady` 内下一刀优先：大脚本解析 / 非必要插件后置（如图片 adapter），或与 `am` 重叠的 native 冷路径；不再做串行 warmup 门控，也不再扩 listReady 类等待裁剪除非新证据显示该段有等待。
