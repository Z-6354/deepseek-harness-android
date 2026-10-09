# 工作基准

本目录只保留**当前阶段**工作指示。过期计划、审计与证据在 [../archive/plans/](../archive/plans/)。

当前有效产品契约：

- [../ARCHITECTURE.md](../ARCHITECTURE.md)
- [../COMPATIBILITY.md](../COMPATIBILITY.md)
- [../PROTOCOL.md](../PROTOCOL.md)
- [../SECURITY.md](../SECURITY.md)
- [../THREE-MODULE-INTEGRATION.md](../THREE-MODULE-INTEGRATION.md)

本阶段指示以 [2026-10-06-current-baseline.md](./2026-10-06-current-baseline.md) 为准：不改官方 Harness 源码；只改 App 与两个自制插件；新能力用新插件；核心是基础功能可用并缩短加载时间。冷热启动以进程是否在内存区分（热=常驻回前台；冷=重启/划掉后再开）。与归档文冲突时以本基准与上述契约为准。
