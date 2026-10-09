# 工作基准

本目录只保留**当前阶段**工作指示。过期计划、审计与证据在 [../archive/](../archive/)（历史摘要见 [MIGRATION-HISTORY.md](../archive/MIGRATION-HISTORY.md)；更早单篇原文以 git 历史为准）。

当前有效产品契约：

- [../ARCHITECTURE.md](../ARCHITECTURE.md)
- [../COMPATIBILITY.md](../COMPATIBILITY.md)
- [../PROTOCOL.md](../PROTOCOL.md)
- [../SECURITY.md](../SECURITY.md)
- [../THREE-MODULE-INTEGRATION.md](../THREE-MODULE-INTEGRATION.md)

## 本阶段指示

| 文档 | 用途 |
|---|---|
| [2026-10-06-current-baseline.md](./2026-10-06-current-baseline.md) | 边界、冷热启定义、性能目标 |
| [2026-10-07-runtime-architecture-plan.md](./2026-10-07-runtime-architecture-plan.md) | 当前三层事实所有权、S1–S7实现与验证缺口 |
| [2026-10-07-startup-rearchitecture-assessment.md](./2026-10-07-startup-rearchitecture-assessment.md) | 启动 A/B 候选架构、条件化预算与图片持久缓存范围 |
| [2026-10-07-app-stage-a-update-plan.md](./2026-10-07-app-stage-a-update-plan.md) | 仅计划：App 资源寿命、私有文件二进制桥、清理及兼容验证 |
| [2026-10-07-plugin-stage-a-update-plan.md](./2026-10-07-plugin-stage-a-update-plan.md) | 两插件 A 阶段本地实现完成、最终验证与设备验收边界 |
| [2026-10-08-hanui-cold-start-code-review.md](./2026-10-08-hanui-cold-start-code-review.md) | Hanui 冷启动恢复缺陷审查（P0/P1/P2，只读） |
| [2026-10-08-session-cache-code-review.md](./2026-10-08-session-cache-code-review.md) | session-cache 授权/同步审查（并行线） |
| [2026-10-08-phase2-direct-session.md](./2026-10-08-phase2-direct-session.md) | 第二阶段：直达会话 / 加载罩（已部署验收） |
| [2026-10-08-phase3-cold-path.md](./2026-10-08-phase3-cold-path.md) | **当前：第三阶段** — 压冷启墙钟冲 ≤5s |
| [2026-10-09-hyperos-webview-mitigation-review.md](./2026-10-09-hyperos-webview-mitigation-review.md) | 0.12.11 HyperOS SIGSEGV 缓解只读审查 |
| [2026-10-09-hyperos-compat-hardening-plan.md](./2026-10-09-hyperos-compat-hardening-plan.md) | 下一步：首文档 compat 契约闭合 + 与 phase3 合流 |
| [2026-10-09-offline-push-design.md](./2026-10-09-offline-push-design.md) | **设计草案**：杀进程仍可达的离线推送（FCM/服务端；未实施） |

要点：不改官方 Harness 源码；只改 App 与自制插件；核心是基础功能可用并缩短到可交互时间。
**遮罩只挡视觉启动过程，不阻挡内容管道。** 与归档文冲突时以 baseline、上表审核与契约文档为准。
