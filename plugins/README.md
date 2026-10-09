# 自制插件检出

本目录集中放置与 DSH Mobile 协作的三个**独立**插件仓库（各自有独立 git 历史，不进入 `:app` Gradle 图，也不随本仓提交）。

| 目录 | 包名 | 职责 |
|---|---|---|
| `dsh-local-hanaccount/` | `dsh-local-hanaccount` | 密码登录、门禁、官方浏览器身份与 RPC 授权 |
| `dsh-mobile-hanui/` | `dsh-mobile-hanui` | 手机网页布局、启动恢复、平台请求与图片 Adapter |
| `dsh-session-cache-sync/` | `dsh-session-cache-sync` | 会话历史持久缓存与差量同步（Host+Client） |

检出示例（在本仓库根目录）：

```sh
git clone <hanaccount-remote> plugins/dsh-local-hanaccount
git clone <hanui-remote> plugins/dsh-mobile-hanui
git clone <session-cache-remote> plugins/dsh-session-cache-sync
```

契约见 [docs/THREE-MODULE-INTEGRATION.md](../docs/THREE-MODULE-INTEGRATION.md)；部署见 [harness/README.md](../harness/README.md)。
