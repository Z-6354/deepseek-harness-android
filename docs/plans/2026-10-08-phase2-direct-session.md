# 第二阶段：直达会话（加载界面 / 速度 / bug）

日期：2026-10-08。依据产品现场与第一阶段实网证据；详细切片见 Cursor 计划 `五秒直达会话_275021b2.plan.md`；缺陷清单见 [hanui 冷启动审查](2026-10-08-hanui-cold-start-code-review.md)。

状态：**hanui 已审核并部署；MuMu 验证加载罩消失；墙钟采样完成（≤5s 未达标）**。App debug APK（含 lifecycle stage）已覆盖安装。

## 唯一叙事

有缓存的最近会话冷启/重开时：不再被第二层「正在打开最近对话…」长时间挡住；尽快看到真实对话首屏并可输入；差量同步在后台完成；挡直达的冷启 bug 有回归。

## 相对第一阶段

| 第一阶段（缓存） | 第二阶段 |
|---|---|
| 历史落盘、差量、force-stop、live — **已证** | **不重做**协议/缓存有无验收 |
| 欢迎 / 罩 / 5s 曾整块后置 | 按现场重切：欢迎移出；加载界面 + 速度 + 挡路 bug 进主线 |

## 本轮已落地

### 加载界面（宿主）

- 有 `window.HanApp` 时：恢复相位不渲染第二层全屏「正在打开…」；仅 `error` 显示重试/回主页。
- 宿主下不再从加载罩提前 `pageReady`；`watchPageReady` 在 `ready` / `home` / `canceled` / `unsupported` / `error` 时揭罩。
- Owner fiber remount 保持 `waiting`，避免误揭罩（审核中修）。

### Bug

| ID | 处理 |
|---|---|
| P0-2 | adopt/`cold` 武装 10s `waiting` |
| P0-1 | `list-timeout` 后不强制 reopen；仅已 retained 时续跑 |
| P1-4 | 与延迟 pageReady + P0-1 一并收敛 |
| P1-5 | `startupBlocked` vs remount `waiting` |
| P1-7 / P1-8 | 放宽 paint；`painted` 接受 `targetRetained` |

### 双计时测法

| 终点 | stage | 含义 |
|---|---|---|
| A | `sessionFirstPaint`（及 `bodyPaint` / `inputReady`） | 首屏可读 + 输入可点 |
| B | `syncCaughtUp`（allowlist 已开） | 差量追平（公开信号待接） |

墙钟脚本：`plugins/dsh-mobile-hanui/scripts/cdp-phase2-cold-timing.py`  
证据：`artifacts/phase2-cold-timing-20261008.json`（及 hanui `artifacts/phase2-cold-timing.json`）。

## 设备证据（2026-10-08）

**部署**

- 远端备份：`/home/ubuntu/dsh-hanui-phase2-bak-20261008-221832`
- `/data/dsh-mobile-hanui` 与本地 hash 对齐；`dsh-web` restart → active
- MuMu 覆盖安装 debug APK SHA256 `C082A801…78522C40`（含 `sessionFirstPaint` / `syncCaughtUp` 桥）

**加载罩**

- force-stop 冷启多次 CDP：`overlay=false`，`phase=ready`，`mainPhase=active`，目标会话恢复

**墙钟（有缓存回访，5 轮）**

| 指标 | 值 |
|---|---:|
| 成功 | 5/5（均无第二层罩） |
| toReady 中位数 | **7641 ms** |
| 均值 / 最小 / 最大 | 7674 / 6287 / 9832 ms |
| ≤5s 门禁 | **未达标**（`gate5s=false`） |

分解：`am start -W` 的 Activity TotalTime 已约 **5.7–7.8 s**；`toReady − amStart` 多数约 **0.5 s**（一轮约 2 s）。网页恢复路径已短，墙钟主耗在进程/WebView 冷启，不在「正在打开」罩。

诊断 `performance.mark` 本轮未采到（`localStorage` diagnostics 标志冷启后未稳定可见）；不影响墙钟结论。

## Done 核对

1. 有缓存冷启不再被第二层加载白屏长时间挡住 — **通过（设备）**
2. 双计时测法 + 有缓存墙钟样本 — **测法具备；中位数未进 ≤5s**
3. 必做 bug 定向回归 — **本地 146 通过**

## 不做 / 后续

- 不重验 session-cache 协议
- 官方欢迎专项不做
- **第三阶段**：压冷启墙钟（native / WebView / 资源）→ [2026-10-08-phase3-cold-path.md](2026-10-08-phase3-cold-path.md)
- `syncCaughtUp` 并入第三阶段双计时稳定化
