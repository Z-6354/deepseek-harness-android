# 安全

完整信任模型见 [docs/SECURITY.md](docs/SECURITY.md)。把 Harness 暴露到网络前请先读完。

## 报告漏洞

请勿开公开 issue，任选其一：

- GitHub **[Report a vulnerability](https://github.com/sorsama/deepseek-harness-mobile/security/advisories/new)**
- 邮件 **sor@zyphite.com**

请说明攻击者能做什么、复现步骤、App 版本、服务器/插件版本，以及网站 HTTPS 地址形态（不要附密码或 Cookie）。

## 已知模型，不视为本应用漏洞

操作员密码授予完整 Harness 工具权限（含在服务器上执行命令）。这是认证插件的设计，不是本应用额外放大的权限。登录后的手机应视为拥有该权限。

需要报告的是：绕过 HTTPS/证书校验、从错误 origin 读出密码、未授权打开网站数据等超出该模型的问题。
