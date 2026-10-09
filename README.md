<p align="center">
  <img src="docs/images/banner.jpg" alt="DSH Mobile" width="100%">
</p>

<h1 align="center">DSH Mobile</h1>

<p align="center">
  在 Android 上打开你自己的 DeepSeek Harness 网站。<br>
  聊天、审批、工作区由服务器上的官方网页负责；本应用只提供隔离的 HTTPS WebView 与系统能力。
</p>

<p align="center">
  <a href="https://dshm.zyphite.com"><img alt="Website" src="https://img.shields.io/badge/website-dshm.zyphite.com-4176E6?style=flat-square"></a>
  <a href="https://github.com/sorsama/deepseek-harness-mobile/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/sorsama/deepseek-harness-mobile?style=flat-square"></a>
  <a href="https://github.com/sorsama/deepseek-harness-mobile/actions/workflows/ci.yml"><img alt="CI" src="https://img.shields.io/github/actions/workflow/status/sorsama/deepseek-harness-mobile/ci.yml?branch=main&style=flat-square"></a>
  <img alt="Android 9.0+" src="https://img.shields.io/badge/Android-9.0%2B-3DDC84?style=flat-square">
  <a href="LICENSE"><img alt="MIT" src="https://img.shields.io/badge/license-MIT-blue?style=flat-square"></a>
</p>

这是 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 的**非官方** Android 容器，不是原生重写的聊天客户端。当前 APK 只编译 `app/src/shell`：加载 HTTPS 官网、处理文件选择/下载/通知，登录与业务协议都在网页和服务器插件里完成。

旧版 Compose 原生客户端、Relay、二维码配对、启动 Token 链接已从仓库移除，不能当作本构建的使用说明。

**[dshm.zyphite.com](https://dshm.zyphite.com)** 是对外项目页。本仓库说明以本文件为准。

---

## 当前行为

- 默认打开 `https://dsh.wannian.fun/`；已保存的其它 HTTPS 站点会继续使用。
- 在网页上输入操作员密码登录（`dsh-local-hanaccount` 的 `password-native-v1`）。
- 可选 `HanApp` 桥：仅当前精确 origin 的主框架可请求通知权限、展示通知、读写本机加密密码、确认后切换网站。
- 同一源 HTTPS GET 下载（上限 25 MiB）；系统文件选择器上传；禁止混合内容与任意 JS 接口。
- 本地退出会清除浏览数据、Keystore 中的已存密码，以及静态资源磁盘缓存。

原生设置入口尚未接到界面。切换服务器依赖网页桥 `changeWebsite`，或等待后续补上的设置项。

## 环境要求

- Android 9.0 及以上（minSdk 28）。
- 系统 WebView 需支持 document-start 脚本与完整浏览数据删除，否则部分增强或切站不可用。
- 服务器提供可信 HTTPS（仅信任 Android **系统** CA，不信任用户安装的 CA，不允许 HTTP 登录）。
- 安装兼容的 `dsh-local-hanaccount`（声明 `password-native-v1`）以及手机布局用的 `dsh-mobile-hanui`。仅看 Harness 版本号不能确认可登录。

部署见 [harness/README.md](harness/README.md)。安全边界见 [docs/SECURITY.md](docs/SECURITY.md)。三模块职责见 [docs/THREE-MODULE-INTEGRATION.md](docs/THREE-MODULE-INTEGRATION.md)。

## 快速开始

1. 在服务器上按常规流程安装密码插件与 hanui，把 Harness 放在回环地址后，用反向代理提供 HTTPS。
2. 安装本仓库构建的 APK。首次启动默认进入上述网站；在登录页输入密码。
3. 业务会话、模型、工作区都在网页里操作。认证失败只显示网页登录页，不会回退到已删除的原生协议。

本构建**尚未**作为可对外公网发布的完成态：认证失败关闭与完整官方网页端到端验收仍待完成。

## 构建

```sh
./gradlew :app:assembleDebug      # 调试 APK
./gradlew :app:assembleRelease    # 发布 APK（配置了 keystore 环境变量时会签名）
```

版本号来自 git 标签工作流导出的 `DSH_VERSION_NAME`；本地构建回退到 `app/build.gradle.kts` 中的字面量。开发约定见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 仓库结构

| 路径 | 内容 |
|---|---|
| `app/src/shell/` | **当前 APK**：WebView 壳、站点仓库、桥、下载、兼容脚本 |
| `app/src/main/` | Manifest 与资源（图标、主题、网络安全配置） |
| `brand/` | 启动图标源 SVG（不进 APK 模块图） |
| `docs/` | 架构、协议、兼容性、安全、三模块契约 |
| `docs/plans/` | 当前工作基准 |
| `docs/archive/` | 迁移摘要（非契约；原文见 git 历史） |
| `harness/` | HTTPS 部署说明 |

Gradle 只包含 `:app`。`dsh-local-hanaccount` / `dsh-mobile-hanui` 为独立插件检出（可放在工作区旁路目录，已在 `.gitignore`），不随本 App 模块编译。

## 许可证

[MIT](LICENSE)。第三方材料见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。DeepSeek Harness 及其品牌归各自所有者；本项目是独立的社区远程容器。
