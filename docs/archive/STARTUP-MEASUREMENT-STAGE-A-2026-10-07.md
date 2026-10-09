# A 阶段部署后启动实测

2026-10-07，Asia/Shanghai。MuMu `127.0.0.1:16384`、WebView 110.0.5481.154.1，连接正式站点 `https://dsh.wannian.fun`。本次只测量，没有修改 App、插件或服务器。

已回读已安装 APK：`com.labteto.dshmobile.debug` / 0.12.2，SHA256 `25EFCFDB9B51D7510FA1F73C132522D9E4C69091B635966E37711FB198208889`，与已验证 A 阶段 APK 一致。服务器两个插件为本次更新后的构建。

## 方法与终点

每轮 `am force-stop` 并确认 PID 消失，随后 `am start -W` 真实打开 App，新 PID、新 WebView 文档；保留登录、磁盘缓存和官方预览说明状态。每轮保存截图、该 PID 原生阶段日志、Navigation/Resource Timing、逐次就绪观察。

起点是主机单调时钟发出 adb 启动命令之前。终点要求官方“预览版说明”标题和“继续”按钮可见、按钮未禁用、网页命中测试可点击；经两次 requestAnimationFrame 后，还须收到同一 browser/document 的原生 visualComplete。终点截图核对真实页面、启动遮罩已消失。没有点击继续、导航到会话、清除缓存、重新登录或浏览器 reload。

计时包含 adb 传输与观察开销，是观测上界。本次最后一次未就绪与首次就绪的间隔为约 0.16–0.20 秒。原生 visualComplete 只证明该阶段视觉提交，不能单独替代官方弹窗可操作终点；`am start` TotalTime 也不作为总加载耗时。

## 结果

| 冷启动轮次 | 官方弹窗可操作观测时间 | 前一次未就绪观测时间 |
| --- | ---: | ---: |
| 1 | 9.531 秒 | 9.375 秒 |
| 2 | 6.421 秒 | 6.234 秒 |
| 3 | 7.687 秒 | 7.500 秒 |
| 4 | 8.234 秒 | 8.031 秒 |
| 5 | 7.750 秒 | 7.547 秒 |

中位数 **7.75 秒**，平均 **7.92 秒**，范围 **6.42–9.53 秒**。只有五个样本，不报告 P95。此处是保留缓存的进程冷启动，不是清空数据后的首次安装启动，也不是热恢复。

与此前约 7.4 秒相比，本批样本没有显示明显改善。此前数字并非本次同环境受控 A/B 基线，因此不能据此严格断言退化或归因于某项代码。

## 阶段示例

第 5 轮恰好为本批中位数样本。下表采用同一主机单调时钟的原生事件接收点和最终页面观测点；原生记录也保存了独立 nativeBeginMs，二者不混算。

| 阶段 | 分段耗时 | 从启动命令累计 |
| --- | ---: | ---: |
| 启动命令 → WebView loadUrl | 1.578 秒 | 1.578 秒 |
| loadUrl → documentStarted | 2.109 秒 | 3.687 秒 |
| documentStarted → documentCommitted | 0.938 秒 | 4.625 秒 |
| documentCommitted → hanui factory | 0.984 秒 | 5.609 秒 |
| factory → native visualComplete | 0.188 秒 | 5.797 秒 |
| native visualComplete → 官方弹窗可操作 | 1.953 秒 | 7.750 秒 |

最后一段是观察到的间隔，尚未定位其内部原因。本次目标是计时，不因测量结果继续改代码。图片二进制桥在当前 WebView 不受支持，图片缓存效果不在本次验收结论中；最近对话恢复时间也未测量。

原始证据：`artifacts/startup-stage-a-measurement-20261007/summary.json`、`samples.json`，各 `sample-N/` 的 `result.json`、`native-trace.txt`、`am-start.txt`、`endpoint.png`。可复现命令：`python artifacts/startup-stage-a-measurement-20261007/measure.py`。
