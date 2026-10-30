# livetv —— 极简直播 App

给老 Android TV 用的直播客户端。约 700 行 Java，三个文件（`MainActivity` / `Playlist` / `Epg`），没有网络库、没有图片库、没有 Gradle。

包名 `com.local.livetv`，minSdk 21 / targetSdk 22，APK 约 33 KB。

## 为什么自己写

- OKTV（影视仓系）的「网络直播」是内置平台模块，不吃 `config` 里的 `lives`，只有虎牙/斗鱼/B站等，没有央视。
- my-tv 依赖央视频接口，在这台机器上直接「状态错误」。
- 其它直播 App 要么体积大，要么塞广告，要么 minSdk 超标。

## 2.0 的变化

2.0 不再内置频道表，改成**从 NAS 上的自建 IPTV 服务拉订阅**：

```
http://192.168.123.6:1905/txt        ← 订阅（分组,#genre# + 名,url）
http://192.168.123.6:1905/playback.xml ← EPG（XMLTV）
```

服务端是 [akiralereal/iptv](https://github.com/akiralereal/iPTV) 的 Docker 镜像（端口 1905），聚合了央视/卫视/地方台 140+ 个频道，同时代理转发各频道源。

没有网络时用 `assets/channels.txt`（37 个台）兜底。

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
adb install -r livetv.apk
adb shell am start -n com.local.livetv/.MainActivity
```

## 操作

| 按键 | 行为 |
|---|---|
| 上 / 下 | 呼出列表 → 移动选中 → 停 0.7 秒切台；按住不放一次跳 5 台 |
| 数字键 0–9 | 输入台号直跳（最多 3 位，停 1.2 秒提交） |
| 左 / 右 | 在分组之间循环跳（央视 → 卫视 → 香港 → 台湾 → …） |
| OK | 列表隐藏时呼出；列表可见时切到选中台（相同则收起） |
| OK（长按） | 收藏 / 取消收藏当前台，收藏的台排到列表最前 |
| 菜单 | 设置：改订阅地址、分组白名单、刷新订阅 |
| 返回 | 退出 |

播放 6 秒后列表自动滑出，屏幕只留画面。记住上次看的频道（`SharedPreferences`）。

## 画面

- 视频**整屏** 1920×1080，16:9 的流正好铺满，不留黑边；
- 左侧频道列表是 70% 黑的**半透明玻璃**，盖在画面上；
- 切台时右上角显示台名 + 实际分辨率，停 3 秒；
- 列表右侧显示**当前节目**（来自 EPG，没有 EPG 的台回落到分组名）。

## 画质

同一个台往往有多个源（咪咕 540p、央视频 1080p）。App 的做法：

1. 探测到的分辨率按 URL 记在 `files/res_cache.txt` 里，**下次同一个源直接跳过低清，不起播**，所以不会闪屏；
2. 首次遇到时，起播后发现宽度 < 1280 就把该源挪到队尾，先试高清；
3. 高清源全失败时，低清源排在最后兜底。

实测 CCTV-1：咪咕 960×540 → 央视频 1920×1080。

**服务端要开 H.265**（`menableH265=true`），这台 MStar MS838AS13 能硬解 H.265。

## 一个必须记住的坑

`VideoView` 的高度不够时，MStar 的 `surfaceChanged` 不会发 `com.tcl.video.fullscreen` 广播，视频层不显示 → **全屏黑屏**，而播放器其实正常解码（logcat 里 `MSTAR_VDEC_DBG` 稳定 25 fps）。

VideoView 默认按视频宽高比缩自己（1470 宽 → 826 高），正好低于那个阈值。**不要覆盖 `onMeasure` 强制拉满**——那会让 16:9 的流变形。正确做法是让视频占满整个屏幕（宽度 1920 → 高度正好 1080），列表做成覆盖层。

排查口诀：先看 logcat 里 `com.tcl.video.fullscreen` 是 true 还是 false，再看 `dumpsys SurfaceFlinger` 里 SurfaceView 层的高度，最后才怀疑源。

## 频道表

`assets/channels.txt` 是**离线兜底**用的一份精简表（37 个台）。线上跑的是服务端订阅。

## 已知限制

- **不支持 IPv6 源**。本机（MStar MS838AS13）的 `MediaPlayer` 走 IPv6 时 `DataSource_M3U8` 直接 `httpResponseCode=0` 死循环。
- 咪咕的 H.264 源普遍只有 540p；央视频那套高清源只覆盖央视/CGTN/卫视，地方台多为 720p。
- 源是公开的第三方 IPTV 源，随时可能失效。
- 无回看、无多画面。
- 只测过 Android 6 / 1080p UI，更高版本应该也能跑（没验证）。

## 实测数据（本机 65U6900C）

| 项目 | 数值 |
|---|---|
| 频道数 | 141（服务端订阅，2026-10-30） |
| 码率 | 咪咕 540p H.264 / 央视频 1080p |
| 电视 WiFi | 5GHz、RSSI −51、协商 867 Mbps |
| 电视有效吞吐 | **≈ 9 Mbps**（`adb push` 8 MB 用 6.64 s） |
| 首帧 | 0.7–13 s，多数 1–3 s |

## 频道源怎么来的（供参考）

1. **服务端**：[akiralereal/iPTV](https://github.com/akiralereal/iPTV) 的 Docker 镜像，抓取咪咕、央视频、各省广电的公开流并代理转发（`/txt` 输出订阅，`/playback.xml` 输出 EPG）。
2. **客户端**只做「拉订阅 → 分组过滤 → 归并同名台 → 逐台多源重试」，不关心源从哪来。
3. **源站代理**：`http://192.168.123.6:1905/xxx` 是服务端代理，真实地址在服务端取票时动态生成（咪咕的签名 URL 有时效，客户端自己拼会过期）。
