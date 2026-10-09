# HTTPS 服务器设置

当前 Android 壳要求服务器安装声明 `password-native-v1` 的本地插件。安装或编译 App **不会**升级生产服务器。按你平时的运维流程部署插件，先验证 status 与双 Cookie 桥，再让手机打开该网站。协议见 [docs/PROTOCOL.md](../docs/PROTOCOL.md)，安全边界见 [docs/SECURITY.md](../docs/SECURITY.md)。

把 Harness 绑在回环地址，前面放 HTTPS 反向代理。证书必须被 Android **系统** CA 信任（不信任用户安装的 CA）。配置插件的可信代理，保留原始 Host，不要信任任意调用方的转发头。在服务器本机设置操作员密码。该密码等于完整工具权限。

例如用 Caddy：

```caddyfile
agent.home {
    reverse_proxy 127.0.0.1:3080
}
```

手机必须能解析该主机名，并且信任其证书。在网站登录页输入密码。登录与 Cookie 请求拒绝重定向和 HTTP。本设置不包含启动 Token、二维码配对、Relay 或外部凭据 URL。

`cordis.patch.lan.yml` 只是旧的监听地址辅助，不是推荐的密码登录方案，也不能用来代替 HTTPS。不要把未认证的明文 Harness 端口当成登录入口。
