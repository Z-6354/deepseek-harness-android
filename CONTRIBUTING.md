# 参与开发

## 环境

- Android Studio（Koala 或更新）+ JDK 17+ + Android SDK 35。
- `./gradlew :app:assembleDebug` 生成当前 WebView 壳的调试 APK。
- minSdk 28（Android 9）。

## 对着真实 Harness 开发

1. 本机运行 DeepSeek Harness（默认 `dsh web`，端口 3080），并安装兼容的 `dsh-local-hanaccount` 与 `dsh-mobile-hanui`。
2. 用 HTTPS 反向代理暴露该站点；证书必须被 Android **系统** CA 信任。不要用明文 HTTP 做密码登录。
3. USB 调试时可 `adb reverse` 到本机代理端口，再在网页或桥里打开对应 `https://` 地址。
4. `harness/cordis.patch.lan.yml` 只是旧的局域网监听辅助，不是推荐的密码登录部署方式。

当前 APK 没有原生连接表单。登录发生在网站页面上。

## 仓库约定

- 打进 APK 的源码在 `app/src/shell/`；`app/src/main/` 只放 Manifest 与资源。
- 壳层字符串目前大量写在代码里；网页文案由服务器插件提供。
- 未知网页能力应降级为普通浏览器行为，不要回退到已删除的原生 `/api` 客户端。
- 工作指示见 `docs/plans/2026-10-06-current-baseline.md`；过期笔记在 `docs/archive/`。
- 合并前 CI 需要单测、lint、assemble 通过（`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`）。

## 发布

打 `v*` 标签后，Release 工作流构建 APK；配置了签名密钥则签名，否则为未签名包。
