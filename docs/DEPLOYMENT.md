# 部署与归属

本仓库为 **Z-6354/deepseek-harness-android** 二次开发维护，生产站点与 App 更新通道均在自有服务器上，不依赖上游公开站。

## 身份

| 项 | 值 |
|---|---|
| GitHub | https://github.com/Z-6354/deepseek-harness-android |
| 生产站点 | https://<SITE_HOST>/ |
| 服务器 | `ubuntu@<SERVER_HOST>`（SSH 密钥登录；真实主机不入库，见下方说明） |
| DSH 数据目录 | `/home/ubuntu/.dsh/` |
| App 更新目录 | `/var/www/dsha/update/`（Nginx `root /var/www` + URI `/dsha/...`） |
| 部署文档镜像 | https://<SITE_HOST>/dsha/docs/DEPLOYMENT.md |
| 更新清单 URL | https://<SITE_HOST>/dsha/update/latest.json |

`applicationId` 仍为 `com.labteto.dshmobile`（历史包名，改名会导致无法覆盖安装）。对外品牌与文档使用 **DSHA**。

## 服务器角色

- Nginx：`<SITE_HOST>` → TLS 终止后反代 `127.0.0.1:3080`（dsh-web）。
- 静态更新：`location ^~ /dsha/` → 文件系统 `/var/www/dsha/`（不经 Harness 门禁）。
- 自制插件运行时路径：`/home/ubuntu/.dsh/profiles/web/node_modules/dsh-local-hanaccount` 等。

**服务器地址不入库。** 文档与脚本里的 `<SERVER_HOST>` 为占位符；真实地址放在本机 `~/.ssh/config` 或环境变量中。发布脚本读取 `$env:DSHA_UPDATE_HOST`（形如 `ubuntu@<host>`），未设置会直接报错。

SSH 示例：

```sh
ssh ubuntu@<SERVER_HOST>
```

## 发布 App 更新（国内优先）

1. 本地或 CI 构建并签名 release APK。
2. 计算 SHA-256，写入清单。
3. 上传到服务器更新目录（需已配置 SSH 密钥）：

```sh
# 在仓库根目录；将 VERSION / APK 路径换成实际值
VERSION=0.16.0
APK=app/build/outputs/apk/release/app-release.apk
SHA=$(sha256sum "$APK" | awk '{print $1}')
BYTES=$(wc -c < "$APK" | tr -d ' ')

scp "$APK" "ubuntu@<SERVER_HOST>:/tmp/dsha-${VERSION}.apk"
ssh ubuntu@<SERVER_HOST> "sudo install -m 664 -o www-data -g www-data /tmp/dsha-${VERSION}.apk /var/www/dsha/update/dsha-${VERSION}.apk"

ssh ubuntu@<SERVER_HOST> "sudo tee /var/www/dsha/update/latest.json >/dev/null" <<EOF
{
  "versionName": "${VERSION}",
  "versionCode": $(python -c "v='${VERSION}'.split('.'); print(int(v[0])*10000+int(v[1])*100+int(v[2]))"),
  "apkUrl": "https://<SITE_HOST>/dsha/update/dsha-${VERSION}.apk",
  "apkName": "dsha-${VERSION}.apk",
  "apkBytes": ${BYTES},
  "sha256": "${SHA}",
  "releaseNotes": "见更新说明"
}
EOF
ssh ubuntu@<SERVER_HOST> 'sudo chown www-data:www-data /var/www/dsha/update/latest.json'
```

也可使用仓库脚本：[`scripts/publish-app-update.ps1`](../scripts/publish-app-update.ps1)。

App 在加载阶段后台自动检查（无设置入口），有新版本则在页面揭罩后弹出可关闭对话框：

1. `https://<SITE_HOST>/dsha/update/latest.json`（主渠道，国内可达）

安装前会校验 HTTPS、可选 SHA-256，以及 APK 签名与当前已装包一致。

## Release signing

Release APKs are signed with a dedicated key that lives **outside the repository**. The earlier 0.12.x
builds were signed with the public Android debug key, so the first build signed with the new key cannot
be installed over them: users uninstall once and reinstall (app data is cleared; they log in again).
After that every update installs normally.

1. Create the key once: `.\scripts\new-release-keystore.ps1` (writes `%USERPROFILE%\.dsha-release\`).
   **Back that folder up.** Losing it means another uninstall for every user.
2. Local build: load the four `DSH_*` lines from `release.env.txt` into the environment, then
   `./gradlew :app:assembleRelease`. Without them the task fails on purpose.
3. CI build (optional): add repository secrets `RELEASE_KEYSTORE` (base64 of the `.keystore`),
   `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`. The workflow refuses to run
   without `RELEASE_KEYSTORE`. Artifacts are kept 7 days and never published as a GitHub Release.
4. Publish with `scripts/publish-app-update.ps1`. The app verifies that an update has the same signing
   certificate as the installed copy.
## 同步自制插件到服务器

插件源码在本仓 `plugins/`。覆盖运行时模块后重启 `dsh-web`：

```sh
DEST=/home/ubuntu/.dsh/profiles/web/node_modules/dsh-local-hanaccount
scp -r plugins/dsh-local-hanaccount/src ubuntu@<SERVER_HOST>:$DEST/
ssh ubuntu@<SERVER_HOST> 'sudo systemctl restart dsh-web'
```

`dsh-mobile-hanui` / `dsh-session-cache-sync` 同理，路径对应各自 `node_modules` 包名。改完后用浏览器或 App 验证登录与首页，不要只看进程 `active`。

## Git 远程

推荐本地只保留自家 `origin`：

```sh
git remote remove origin   # 若仍指向旧上游
git remote add origin https://github.com/Z-6354/deepseek-harness-android.git
# 或已有 z6354 远程时：
git remote rename z6354 origin
git remote remove upstream 2>/dev/null || true
```

GitHub 仓库只放源码，不发布 Release/APK；手机更新只走 `<SITE_HOST>/dsha/update/latest.json`。

## 相关文档

- HTTPS / 插件要求：[harness/README.md](../harness/README.md)
- 安全：[docs/SECURITY.md](SECURITY.md)
- 协议：[docs/PROTOCOL.md](PROTOCOL.md)
