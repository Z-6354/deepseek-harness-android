# Hanui 冷启动恢复 — 缺陷状态（终表）

日期：2026-10-08（复审 + 修复落地）。对照当前 `dsh-mobile-hanui` 源码；**冷启性能阶段已结束**，本表只跟踪功能/正确性缺陷。

审查对象：`startup.js` / `session-adapter.js` / `client.entry.js`（Startup* / watchPageReady）。

验证：`npm test` → **152 pass / 0 fail**（含 P1/P2 定向回归）。

---

## 最终修复表

| ID | 严重度 | 状态 | 摘要 | 位置 | 修复动作（待修时） |
|---|---|---|---|---|---|
| P0-1 | P0 | **已修** | `list-timeout` 后晚到目录不再强制 reopen；仅已 retained / 同 current 续跑；`home`→`restoreCanceled` | `startup.js` terminal/`list-timeout` 分支 | — |
| P0-2 | P0 | **已修** | adopt/retained 进入 history 时武装 10s `waiting`→error | `watchHistory`→`armHistoryWait` | — |
| P0-3 | P0 | **已修** | 缺 `list.ids` 时 `targetExists` 回退 `byId` | `session-adapter.js` `member` | — |
| P1-1 | P1 | **已修** | `home()` 清 scoped 偏好并清空 `targetId` | `home()` | — |
| P1-2 | P1 | **已修** | 官方 peek 仅 live 时 adopt；目录成员走 clear+open；注销清 scoped 偏好 | `resolveDiscovery` / unauth 分支 | — |
| P1-3 | P1 | **已修** | 双键空走 discovering + `restoreCandidates` | `begin` / `resolveDiscovery` | — |
| P1-4 | P1 | **已修** | 揭罩后再强开：随 P0-1 收敛 | 同上 | — |
| P1-5 | P1 | **已修** | fiber remount→`waiting`；仅 deadline / 外层 dispose→`unsupported` | `client.entry.js` scoped startup | — |
| P1-6 | P1 | **已修** | `visibilitychange` 仅 `hidden` 时 persist | `onLifecycle` | — |
| P1-7 | P1 | **已修** | 去掉 `navigationGuard` 门闩；paint 超时改为 `error`/`view-capability`（可重试） | `StartupPaintProbe` / `markPainting` | — |
| P1-8 | P1 | **已修** | `painted` 接受 `targetRetained` | `painted()` | — |
| P1-9 | P1 | **已修** | `forcePrepare` 路径始终 `cancelInitial` | `session-adapter.js` `markPrepared` | — |
| P1-10 | P1 | **已修** | blank 清旧 bookmark；未 sessionsReady 时尽力写 nonempty；retained 可写 | `remember` / `persist` | — |
| P2-1 | P2 | **已修** | debug 仅 `diagnostics.enabled`；不写 session id | `armListDeadline` / late-ready | — |
| P2-2 | P2 | **已修** | clear 抛错保留 `clearPending`，flush 重试 | `markPrepared` / `flush` | — |
| P2-3 | P2 | **已修** | 定向回归已补 | `tests/startup.test.js` | — |

---

## 待修优先级（实施序）

**无待修项。** 本表所列缺陷均已落地；hanui `npm test` 全绿。

---

## 复审备注

- `armListDeadline`（25s）在仍 `restoring` 且目录已就绪但未落地时会 `takeoverOpen`（超时前强开），与「timeout 进 home 之后」的 P0-1 不是同一路径；App 下此时品牌罩仍在，未单列 P0。  
- 冷启墙钟 / ≤5s / A1–A3 性能项：**本阶段不做**。  
- session-cache 代码审查中的协议 P0/P1 已关闭；余量为实网验收，不列入本表。  
- 生产部署：改动在 `dsh-mobile-hanui`；需 `npm run build` 后同步 `/data/dsh-mobile-hanui/src/`（本回合未部署）。
