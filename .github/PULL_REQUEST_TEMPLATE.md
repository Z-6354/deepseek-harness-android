<!--
感谢贡献。不需要的章节请删。约定见 CONTRIBUTING.md，产品说明见 docs/。
-->

## 改了什么、为什么

<!-- 解决什么问题。若修 issue：Fixes #123 -->

## 实际怎么验证的

<!-- 写你跑过的，不要写“应该可以”。例如：MuMu WebView 110 + 本机 HTTPS 插件；仅 JVM 单测。 -->

- [ ] 真实 HTTPS 网站（地址与插件版本：…）
- [ ] `./gradlew :app:testDebugUnitTest`
- [ ] 仅静态阅读 / 未跑

## 检查清单

- [ ] `./gradlew :app:testDebugUnitTest` 通过
- [ ] `./gradlew :app:lintDebug` 无 error
- [ ] 当前 APK 源码在 `app/src/shell`；不要把未编译的旧原生客户端当成已上线行为
- [ ] 未恢复 Relay、明文 HTTP 登录、信任全部证书或 `addJavascriptInterface`
- [ ] 用户可见行为若变化，已更新中文 `README.md` / `docs/`
- [ ] UI 变化附截图或录屏

## 截图

<!-- 有界面变化时附上。 -->

## 给审查者

<!-- 不确定、有意不做、希望第二意见的事项。 -->
