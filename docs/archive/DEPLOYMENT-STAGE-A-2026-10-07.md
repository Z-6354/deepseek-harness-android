# A 阶段安装与服务器更新

日期：2026-10-07（Asia/Shanghai）。本次由用户明确授权安装最新版 App 并更新服务器两个插件。

## 已交付

- MuMu `127.0.0.1:16384` 覆盖安装 `com.labteto.dshmobile.debug`，版本 0.12.2；未卸载或清除应用数据。回读已安装 base.apk 的 SHA256 为 `25EFCFDB9B51D7510FA1F73C132522D9E4C69091B635966E37711FB198208889`，与本地通过验证的 A 阶段 APK 相同。未给用户实体手机安装，也不是 release 签名升级。
- 生产服务器 / `https://dsh.wannian.fun` 更新 hanaccount 2.4.2 与 hanui 0.2.9 的本轮源码包，保留已有 symlink、配置和认证数据；没有版本 bump、提交、发布或修改官方 DSH 核心。
- hanaccount 已安装 client SHA256：`9e6861a9e462e81ff0fdc93c0a5817625634f4d2b1890eee0b5027b169a921fa`。
- hanui 已安装 client SHA256：`e636248fd59e05f1b83f5f3c380bd694fe19b24f28df5e9cf9ecf4e6a6c858c9`。

## 访问恢复与认证证据

维护期间关闭该 vhost 五个 dsh_web proxy 入口，停服务后备份认证存储，再覆盖两个自制插件。服务重新启动且 loopback 检查通过后恢复公网入口；nginx 配置恢复为备份的相同字节。服务 active，守卫为 `auth_ready` / `npm:0.2.1-alpha.1`。匿名 `/assets/`、`/plugins/`、`/api/file` 探针均 401；原始 `/han-relay-ws` 匿名升级握手被关闭。匿名首页正常提供登录界面。

使用 App 原有 Cookie 在内存中完成四种身份验证，没有打印或保存 Cookie 或用户提供的密码。无身份、仅 gate、仅 native 均无 resumeScope；两种身份都有效时 auth/me 返回 authenticated/nativeAuthenticated 与 scope。仅 gate 首页正常 303 恢复官方 Cookie；仅 native 返回登录界面；双身份返回官方 HTML。

实际 HTML 包含 head 认证 bootstrap。它位于官方小型 `dsh-client-modules` 初始化脚本之后、body 业务入口之前，因此不能把它描述为“所有外部脚本之前”。App 在公网恢复后重新打开：document complete，bootstrap protocol 1，hanui 已加载，官方预览说明弹窗可见；该文档仅观察到一次初始 auth/me 请求。直接插件 script hash 验证不适用当前批次加载方式；安装目录哈希已核对，实际客户端启动已确认。

服务器受限备份：`/home/ubuntu/dsh-stage-a-rollout-20261007/` 的 `plugins.before.tar.gz`、`auth-data.before.tar.gz`、`nginx.before`。需要回退时先关闭入口、停服务、恢复插件代码，启动并核验守卫，再恢复 nginx。认证数据只供必要恢复参考，不应覆盖部署后新增登录或撤销事实。

## 已知限制与验收边界

现有兼容限制 `deploymentReady=false` 仍然存在；本次 auth_ready、实际身份矩阵和匿名拒绝检查不代表该标志变成 true。

模拟器 WebView 为 110.0.5481.154.1。真实 Android instrumentation 以 `AssumptionViolatedException` 跳过 ArrayBuffer 桥测试；App 页面有 HanApp，但无 HanPrivateFileWriter。这说明该设备不支持本轮二进制图片缓存能力，hanui 回退官方图片加载。本次未证明原生图片文件写入、跨进程缓存命中或图片提速；需要具备对应桥能力的 WebView 设备再验收。

App 已实际重新打开，但 am start TotalTime 只测 Activity 启动，并非“点击 App → 官方预览弹窗可交互”终点。本次没有完成该终点的精确计时，不能宣称原 7.4 秒已经下降，也不据此判断最近对话恢复已修复。

本地交付基础仍为两插件 272 项通过、6 项 live 测试跳过，App 80 项 JVM 测试通过。部署证据位于 `artifacts/plugin-stage-a-rollout-20261007/`：APK 回读、auth-matrix.log、app-live.json、设备原始跳过日志和截图。
