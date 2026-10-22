# livetv —— 极简直播 App

给老 Android TV 用的央视/卫视直播客户端。约 300 行 Java，一个 `VideoView` + 一个 `ListView`，没有网络库、没有图片库、没有 Gradle。

包名 `com.local.livetv`，minSdk 21 / targetSdk 22，APK 约 25 KB。

## 为什么自己写

- OKTV（影视仓系）的「网络直播」是内置平台模块，不吃 `config` 里的 `lives`，只有虎牙/斗鱼/B站等，没有央视。
- my-tv 依赖央视频接口，在这台机器上直接「状态错误」。
- 其它直播 App 要么体积大，要么塞广告，要么 minSdk 超标。

## 构建

```bash
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk17
export KEYSTORE=/path/to/your.jks
export KS_ALIAS=youralias
export KS_PASS=yourpass
python build.py          # 产物 ./livetv.apk
```

`build.py` 只用 `aapt + javac + d8 + zipalign + apksigner`，签名强制 v1（Android 6 只认 v1）。

## 安装

```bash
adb install -r -g livetv.apk
adb shell am start -n com.local.livetv/.MainActivity
```

## 操作

| 按键 | 行为 |
|---|---|
| 上 / 下 | 换台（立即播放） |
| OK / MENU | 显示频道列表，方向键选择 |
| 返回 | 列表打开时收起列表，否则退出 |

播放 6 秒后列表自动隐藏，屏幕只留画面。记住上次看的频道（`SharedPreferences: livetv/last`）。

**卡流自动换源**：收到 `MEDIA_INFO_BUFFERING_START` 后起一个 12 秒定时器，期间没等到 `BUFFERING_END` 就自动切到该台的下一个源；所有源都失败才弹回频道列表，不会黑屏干等。

## 频道表

`assets/channels.txt`，UTF-8，一行一个台：

```
名称|主源URL|备用源URL|...
```

主源失败会自动换下一个源。当前 60 个台：CCTV-1…17、CGTN 英语/纪录/西语、28 个卫视、翡翠台、金鹰卡通、10 个央视付费频道。

改完频道表重新 `python build.py` 即可，不需要改代码。

## 已知限制

- **不支持 IPv6 源**。本机（MStar MS838AS13）的 `MediaPlayer` 走 IPv6 时 `DataSource_M3U8` 直接 `httpResponseCode=0` 死循环，所以频道表里全是 IPv4 源。虽然电视有全局 IPv6，也没用。
- 源是公开的第三方 IPTV 源，随时可能失效；失效了换 URL 即可，App 本身不用动。
- 这批源是 1080p / 5.4 Mbps，本机 WiFi 有效吞吐只有约 9 Mbps，偶发缓冲属正常；插网线可解决。
- 无 EPG、无回看、无收藏分组。
- 只测过 Android 6 / 1080p UI，更高版本应该也能跑（没验证）。

## 频道源怎么来的（供参考）

1. **抓候选**：聚合源 `http://193.123.86.190:14888/TV/iptv.php`（188 条）＋社区列表 [guovin/iptv-api](https://github.com/Guovin/iptv-api)、[iptv-org](https://github.com/iptv-org/iptv)、[YanG-1989/Gather](https://github.com/YanG-1989/Gather)、vbskycn、suxuang 等，去重后约 4200 条 URL。
2. **定目标**：列 78 个想要的频道（CCTV 全系、CGTN、各卫视、港澳、卡通、付费），逐台去找，而不是"有什么源就上什么台"。
3. **串行测速**：每个候选先 `GET` playlist 确认首行是 `#EXTM3U`，再下几个分片算**真实 Mbps**，低于 0.9 Mbps 直接丢。并发别开太高——大批量并发会被源站限速，测出来的速度全是假的（吃过一次亏：444 个候选只活 37 个）。
4. **择优**：优先国内 CDN——`101.66.198.84:9901/tsfile/live/NNNN_1.m3u8`（电信，20–30 Mbps，CCTV 与卫视有固定编号）、`ali-m-l.cztv.com`（浙江，50–80）、`hlsal-ldvt.qing.mgtv.com`（湖南）、`event.pull.hebtv.com`（河北）、`120.76.248.139`（bestv）。
5. **修数据**：拆开被连写的畸形 URL；把混进 CCTV 频道的省级 CDN（cztv/hebtv/zohi 等）剔掉——它们会把 CCTV-13 播成浙江台；给 CCTV 前置固定编号源；去重（CGTN 的法语/阿语与西语同 URL）。
6. **复核**：60 条逐条再验一遍 `#EXTM3U` 存活才写进 `channels.txt`。

`channels.full.txt` 是合并后的全集（含未通过探测的），`channels.txt` 是最终上线的 60 个。

## 实测数据（本机 65U6900C）

| 项目 | 数值 |
|---|---|
| 源码率 | 1080p / **5.42 Mbps**（同一套转码，多台一致） |
| 源站到 PC 瞬时速度 | 4.1 / 18.2 / 26.3 Mbps 之间跳 |
| 电视 WiFi | 5GHz、RSSI −51、协商 867 Mbps |
| 电视有效吞吐 | **≈ 9 Mbps**（`adb push` 8 MB 用 6.64 s） |
| 首帧 | 0.7–13 s，多数 1–3 s |

结论：源要 5.4 Mbps，链路给 9 Mbps 且会抖，偶发缓冲是物理层决定的，换源治不好。**插网线（eth0，100M 全双工）是最有效的一步。**
