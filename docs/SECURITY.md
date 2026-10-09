# 安全

当前客户端打开普通 HTTPS 网站，在网页上使用操作员密码。服务器需要本地插件的 `password-native-v1` 桥。Relay、配对二维码、证书钉扎、Bearer、外部启动 Token **已删除**，运行时没有兼容回退。安装本 APK 不会升级你的生产服务器。部署见 [harness/README.md](../harness/README.md)。

## 权限与传输

操作员密码等于完整的 Harness 权限（含会在服务器上执行命令的工具）。已登录的手机应视为拥有该权限。插件声明不提供受限设备身份、外推通道或离线事件补偿。

密码与已认证请求必须走 HTTPS、校验证书、精确 origin（方案+主机+端口）。只信任 Android **系统** CA，不信任用户安装的 CA，没有信任全部证书或旧钉扎路径。登录与携带 Cookie 的请求不跟随重定向。平台层匿名工具仍可能使用明文，但这不表示允许 HTTP 密码会话。反向代理必须保留目标 Host，并使用插件的可信代理配置，而不是信任任意转发头。

WebView 拒绝 SSL 错误（`SslErrorHandler.cancel`），禁止混合内容，禁止 `addJavascriptInterface`。`HanApp` 仅在主框架、精确 origin 与当前 generation 下收消息。除 `pageReady` 外还要求当前已提交的站内文档；`pageReady` 可在 commit 前进入 pending，不能携带 payload，也不能触发其它桥能力。

## 会话与撤销

认证路由在 `/dsh-local-hanaccount/api`。网页登录成功后持有两枚 Cookie：`dsh_gate_token` 与官方签发的 `dsh-auth-*`。受保护的 HTTP/WebSocket 需要两者。Cookie 须为 Secure、host-only、path `/`。原生 Cookie 没有逐枚撤销 API，门禁 Cookie 负责撤销。登出、改密、拒绝访问应立即关掉受影响套接字。

本机退出先作废本地浏览环境与已存密码，远程登出尽力而为。网页 XSS 可以读到桥返回的已存密码——自动登录按此设计，站点脚本必须与操作员密码同等对待。

## 设备存储

- 密码仅短暂出现在网页与桥的请求中；SharedPreferences 只存 Keystore AES-GCM 密文，AAD 绑定精确 origin。
- 切站默认保留其它 origin 的密文；**本地退出**会清空凭据库、排队删除浏览数据，并删除 `immutable_assets` 磁盘缓存（其中可能含带 Cookie 的请求记录）。
- 迁移只拷贝旧客户端的 HTTPS 地址元数据，不解密、不沿用旧 Token/明文 Cookie。
- 云备份与设备传输规则排除应用私有数据。

## 通知与文件

通知来自当前站点网页，经系统通道展示；需要在线连接与 Android 通知权限。这不是服务器保证的离线推送。点击意图按站点 owner 与 tag 区分，避免多条通知共用一个 `PendingIntent`。

上传、下载与工具输出共享已认证 HTTPS origin 的完整操作员权限。对待附件和工具动作应与在电脑浏览器里相同；登录不会沙箱化远端代理。下载仅允许同 origin GET，上限 25 MiB。

## 报告漏洞

请通过 GitHub **Report a vulnerability** 或邮件 **sor@zyphite.com** 私下报告。不要在公开 issue 里贴密码、Cookie、凭据 URL 或私有会话内容。
