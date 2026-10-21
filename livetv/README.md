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

## 频道表

`assets/channels.txt`，UTF-8，一行一个台：

```
名称|主源URL|备用源URL|...
```

主源失败会自动换下一个源。当前 37 个台：CCTV-1…17、10 个央视付费频道、10 个卫视。

改完频道表重新 `python build.py` 即可，不需要改代码。

## 已知限制

- **不支持 IPv6 源**。本机（MStar MS838AS13）的 `MediaPlayer` 走 IPv6 时 `DataSource_M3U8` 直接 `httpResponseCode=0` 死循环，所以频道表里全是 IPv4 源。虽然电视有全局 IPv6，也没用。
- 源是公开的第三方 IPTV 源，随时可能失效；失效了换 URL 即可，App 本身不用动。
- 无 EPG、无回看、无收藏分组。
- 只测过 Android 6 / 1080p UI，更高版本应该也能跑（没验证）。

## 频道源怎么来的（供参考）

1. 从聚合源 `http://193.123.86.190:14888/TV/iptv.php` 抓 m3u（188 条），按台名去重、只留 CCTV 与卫视。
2. 逐个 `GET` 校验：返回 200 且首行是 `#EXTM3U` 才算活源，同时跟随 302 拿到真实地址。
3. 与 [YanG-1989/Gather](https://github.com/YanG-1989/Gather)、[iptv-org](https://github.com/iptv-org/iptv) 的源交叉补充，最后探测一遍，死源剔除。

所以 `channels.full.txt` 是合并后的全集（含未通过探测的），`channels.txt` 是最终上线的 37 个。
