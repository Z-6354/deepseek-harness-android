# 参与开发

## 环境

- Android Studio（Koala 或更新）+ JDK 17+ + Android SDK 35。
- `./gradlew :app:assembleDebug` 生成当前 WebView 壳的调试 APK。
- minSdk 28（Android 9）。

## 对着真实 Harness 开发

1. 本机运行 DeepSeek Harness（默认 `dsh web`，端口 3080），并安装兼容的自制插件（检出目录 `plugins/`）。
2. 用 HTTPS 反向代理暴露该站点；证书必须被 Android **系统** CA 信任。不要用明文 HTTP 做密码登录。
3. USB 调试时可 `adb reverse` 到本机代理端口，再在网页或桥里打开对应 `https://` 地址。部署说明见 [`../harness/README.md`](../harness/README.md)。

当前 APK 没有原生连接表单。登录发生在网站页面上。

## 仓库约定

- 打进 APK 的源码在 `app/src/shell/`；`app/src/main/` 只放 Manifest 与资源。
- 自制插件统一放在 `plugins/`：`dsh-local-hanaccount`、`dsh-mobile-hanui`、`dsh-session-cache-sync`（独立 git 仓库，不进 App 模块图）。
- Gradle 入口（`settings.gradle.kts` / `gradlew*` / `gradle.properties`）必须留在仓库根；版本目录与 Wrapper 在 `gradle/`。
- 壳层字符串目前大量写在代码里；网页文案由服务器插件提供。
- 未知网页能力应降级为普通浏览器行为，不要回退到已删除的原生 `/api` 客户端。
- 工作指示见 [plans/2026-10-06-current-baseline.md](plans/2026-10-06-current-baseline.md)；过期笔记在 [archive/](archive/)。
- 合并前 CI 需要单测、lint、assemble 通过（`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`）。

## 测试

- JVM 单测：`./gradlew :app:testDebugUnitTest`。
- 开发中只跑相关的测试类，例如 `./gradlew :app:testDebugUnitTest --offline --tests '*PrivateFileStoreTest'`。全量单测加 lint 只在阶段收尾和提交前各跑一次。
- `web-compat.js` 的 JS 测试**必须**带 `--expose-gc`（其中两个用例要手动触发 GC，缺少时会断言失败）：
  `node --expose-gc --test app/src/shellJsTest/web-compat.test.mjs`。CI 目前不跑这一项，改动 `web-compat.js` 前后请手动执行。
- 设备测试在 `app/src/shellAndroidTest/`，需要真机或模拟器；涉及 HyperOS 的改动在 K80 上做 force-stop 冷启烟雾。

### 测试节奏与耗时参考

2026-10-09 至 10-10 的一次开发会话（17 轮，实际工作约 3.4 小时）里，模型生成约 80 分钟、命令执行约 50 分钟，其中 Gradle 约 21 分钟（26 次调用）。同一台机器（8 核、16 GB）上，改一个文件后重跑 `testDebugUnitTest`：冷启动约 35 秒，守护进程已热约 7 秒；打开 configuration cache 只再快约 2 秒，所以没有启用。全量单测加 lint 约 1 分钟。

由此得到的做法：

- 慢的是反复全量跑，不是单次构建。改动后先跑相关测试类，通过后再合并成一次全量。
- 查看大文件时只读需要的行范围；一次读取整段源码会产生约 2 万字符的输出，占用上下文。
- 从会话日志统计耗时时，上下文压缩会把旧的工具结果重写一遍，写入时间落在压缩那一刻，不能当作命令的真实耗时；按每个调用的第一条结果计算。

## 版本号

`app/build.gradle.kts` 的字面量只是本地构建的回退值，发布版本由 tag 经 `DSH_VERSION_NAME` 注入。回退值应跟随最近一次实际发布线，否则本地包的 versionCode 低于设备上已装版本，无法覆盖安装。每次行为变更发版时同步更新。

## 发布

GitHub 仓库只放源码，不发布 Release 或 APK。APK 由维护者用独立的发布密钥在本机签名，再通过 `scripts/publish-app-update.ps1` 发布到自托管更新渠道。没有配置 `DSH_KEYSTORE` 时 `assembleRelease` 会直接失败，不会产出未签名的包。密钥生成、环境变量和 CI 用法见 [DEPLOYMENT.md](DEPLOYMENT.md) 的 “Release signing”。

打 `v*` 标签会触发 Release 工作流：它构建并签名 APK，保存为 7 天的私有 artifact，不创建 GitHub Release。
