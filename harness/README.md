# HTTPS 服务器设置

生产环境见 **[docs/DEPLOYMENT.md](../docs/DEPLOYMENT.md)**（主机见部署文档，站点 `https://dsh.wannian.fun/`，GitHub `Z-6354/deepseek-harness-android`）。

当前 Android 壳要求服务器安装声明 `password-native-v1` 的本地插件。安装或编译 App **不会**升级生产服务器。按运维流程部署插件，先验证 status 与双 Cookie 桥，再让手机打开该网站。协议见 [docs/PROTOCOL.md](../docs/PROTOCOL.md)，安全边界见 [docs/SECURITY.md](../docs/SECURITY.md)。

把 Harness 绑在回环地址，前面放 HTTPS 反向代理。证书必须被 Android **系统** CA 信任（不信任用户安装的 CA）。配置插件的可信代理，保留原始 Host，并设置 `X-Real-IP`，不要信任任意调用方的转发头。在服务器本机设置操作员密码。该密码等于完整工具权限。

本仓生产 Nginx 将 `dsh.wannian.fun` 反代到 `127.0.0.1:3080`，并把 `/dsha/` 静态目录用于 App 更新清单与 APK（绕过 Harness 门禁）。细节与发布脚本见部署文档。

手机必须能解析该主机名，并且信任其证书。在网站登录页输入密码。登录与 Cookie 请求拒绝重定向和 HTTP。不要把未认证的明文 Harness 端口当成登录入口。

自制插件源码检出见仓库 [`plugins/`](../plugins/README.md)。
