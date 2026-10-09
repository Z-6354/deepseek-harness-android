# 迁移历史摘要（归档）

本文合并并取代原 `docs/archive/plans/` 下约 30 份工作笔记，以及 `VALIDATION-0.11.0.md`。  
**不是**运行时契约。完整原文仍在 git 历史中（本提交删除前的树）。

有效说明：

- [../ARCHITECTURE.md](../ARCHITECTURE.md)
- [../COMPATIBILITY.md](../COMPATIBILITY.md)
- [../PROTOCOL.md](../PROTOCOL.md)
- [../SECURITY.md](../SECURITY.md)
- [../THREE-MODULE-INTEGRATION.md](../THREE-MODULE-INTEGRATION.md)
- [../plans/2026-10-06-current-baseline.md](../plans/2026-10-06-current-baseline.md)

## 时间线

| 阶段 | 大约日期 | 结论（已落地或已作废） |
|---|---|---|
| Compose 原生客户端 | ≤0.11.x / 2026-09 | `/api` + mux、Relay、配对码；验收清单已失效，不能证明当前壳 |
| 密码与通知加固 | 2026-10-04 | 密码插件 `password-native-v1`、隔离 Host 证据；原生连接/Relay 路线后续废弃 |
| 官方 WebView 迁移 | 2026-10-05 | App 改为 HTTPS 壳；业务 UI 归官方网页；hanui / hanaccount 分工固定 |
| 启动与桥稳定性 | 2026-10-06 | 避开 ReplyProxy JNI；单段启动罩；静态资源磁盘缓存；冷热启以**进程是否在内存**为准 |

## 已淘汰方向（勿再当需求）

- 原生 Compose 客户端、Relay、二维码配对、启动 Token、`:core` / mock-harness / conformance
- 信任用户 CA、HTTP 登录、退出后保留含 Cookie 头的静态缓存明文、minSdk 26
- 预置官网快照、补丁官方 Boot、把业务协议搬回 App
- 把归档里互相冲突的性能数字或「先完整取证再改代码」门禁当作现行指示（以 baseline 为准）

## 仍有效的工程要点（已写入现行文档）

- 三模块：hanui（布局）+ hanaccount（密码）+ Android 壳（WebView / 系统能力）
- 桥：`HanApp` WebMessageListener；出站不用 ReplyProxy
- 安全：仅系统 CA、精确 origin、本地退出清浏览数据与静态缓存
- 性能目标（baseline）：热启 P95 &lt; 1s；有缓存冷启 P95 &lt; 5s；不为此放宽安全门禁

## 检索原文

```sh
# 示例：查看删除前的某份计划
git log --all --full-history -- "docs/archive/plans/2026-10-05-official-webview-migration-plan.md"
git show <commit>:docs/archive/plans/2026-10-05-official-webview-migration-plan.md
```

曾归档文件名包括（不完全列举）：`*-password-only-*`、`*-sol-*-audit*`、`*-official-webview-*`、`*-three-module-*`、`*-startup-*`、`*-reply-proxy-*`、`VALIDATION-0.11.0.md`。
