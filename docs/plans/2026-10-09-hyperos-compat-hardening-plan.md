# 计划：HyperOS 兼容加固 + 与第三阶段合流

日期：2026-10-09。依据 [审查](2026-10-09-hyperos-webview-mitigation-review.md)。

**契约裁决：方案 A**（2026-10-09 已选并开始落地）。

## 目标

在保留 K80 无 SIGSEGV 的前提下：

1. 明确并闭合「首文档兼容」产品契约（有 polyfill 或有文档化降级）。
2. 让设备测覆盖生产安装时序。
3. 不破坏第三阶段「有缓存 toReady 中位 ≤5s」叙事；任何原生改动必须有分段对照。

## 约束

- 不改官方 Harness 核心字节。
- 不恢复「createBrowser 前等 warmup settled」串行门控（phase3 已回退）。
- 不把假 pageReady / 仿会话 UI 当提速。
- 遮罩仍只挡视觉，不挡管道。

## 工作切片（建议顺序）

### 1. 契约裁决（先定再改）

三选一，验收标准随之变化：

| 方案 | 行为 | 适用 |
|---|---|---|
| **A（推荐）** | 首 commit 后 install；若探测到缺 API 且本代文档未跑过 compat → **同 origin 软 reload 一次**，二次导航吃到 document-start | 既保 HyperOS，又保旧 WebView 首屏 |
| **B** | 维持现状；文档改为「首文档依赖系统 WebView 原生 API；compat 仅覆盖后续导航」 | 接受旧缺口机型首屏风险 |
| **C** | 尝试「WebView 创建后、loadUrl 前极短延迟 / 空文档 settle 再 install」 | 需 K80 对照；失败则回到 A |

裁决产出：更新 `COMPATIBILITY.md` 一句产品句 + 本计划勾选选定方案。默认倾向 **A**。

### 2. 实现选定方案（仅 App）

主要触点：

- `BrowserRuntime.ensureCompatibilityInstalled` / `commit`
- 可选：一次 `needsCompatReload` 标志（按 lease/generation，防环）
- **禁止** `evaluateJavascript` 晚补（与现 `WebCompatibility` 注释一致，除非契约显式改写）

验收：

- K80：force-stop 冷启 ×5 无 Fatal；可到会话 / pageReady
- 缺 API 夹具（设备测）：首屏最终具备 polyfill（A）或文档化降级（B）

### 3. 测试对齐

| 测 | 改什么 |
|---|---|
| `WebCompatibilityDeviceTest` | 增加「先 load → commit 路径 install → 同 origin 再导航」用例；保留「提前 install」作对照可选 |
| 单元 | 若引入 reload 门闩，对 generation / 防环做本地测 |
| 烟雾 | K80 +（可选）MuMu：冷启、更新对话框路径、切站清理后回站 |

### 4. 与第三阶段合流（有对照才动）

在 compat 契约闭合后，再开对照（证据写入 `artifacts/`）：

| 实验 | 假设 | 失败即回退 |
|---|---|---|
| 条件 warmup | 非 HyperOS 或 `startUpWebView` 完成后再建 Activity WebView **且不串行挡 loadUrl** | wall 中位相对 ungate 基线变差 |
| 大脚本 / 插件后置 | 压 `commit→pageReady`（基线 ~2.3 s） | 直达会话回退或功能缺 |
| 测法 | 沿用 phase3 分段脚本；样本隔离：有缓存 force-stop | 混入首开全量 / 清 IDB |

不在本计划扩 listReady 等待裁剪（phase3 已证无显著收益）。

### 5. 文档与版本

- 更新 `COMPATIBILITY.md`、必要时 `ARCHITECTURE.md` 一句
- `docs/plans/README.md` 挂上本计划与审查
- 行为变更后升 `0.12.12+`，发布更新频道前再跑 K80 烟雾

## 不做

- 为 HyperOS 单独 fork 一套业务插件
- 重开 session-cache / 欢迎专项
- 把更新检查重新放到主线程或罩未落地时弹对话框
- 无对照恢复 Application 全局 warmup

## Done

1. 契约方案 A/B/C 已选并写入 `COMPATIBILITY.md`。  
2. 生产时序有设备测；K80 无 SIGSEGV 回归。  
3. 若选 A：缺 API 夹具首屏最终有 polyfill，且无 reload 环。  
4. 与 phase3 的下一步实验清单不互相覆盖；任一 native 实验有 JSON 对照与去留结论。

## 建议实施序

1. 裁决契约（A）→ 2. 实现 + 测 → 3. K80 烟雾 → 4. 发版 → 5. 再开 phase3 对照实验
