> **已退役（2026-10-30）**：这台电视的桌面已换成 [Aerial](https://github.com/pengchujin/aerial-tv)（见仓库根目录的 README）。
> 本目录的 cleanyourtv fork（tvhome）源码留档备用，电视上已卸载（pm uninstall --user 0 com.gathin.tvhome）。
> 保留它的原因：它证明了这台机器上第三方桌面能装、能接管 Home，也留下了「LEANBACK_LAUNCHER 才进桌面程序坞」的实测。
# launcher —— cleanyourtv 的 API 23 移植与补丁

[cleanyourtv](https://github.com/Gathin23/cleanyourtv) 是一个很干净的 Android TV 桌面（MIT）。上游要求 **Android 8.0（API 26）**，这台电视只有 **Android 6.0（API 23）**，所以 fork 了一份做移植，顺带补了几个自用功能。

> **基线**：cleanyourtv `v2.5`（versionCode 6）的快照。上游之后又加了 `ClockActivity`、`ambient_glow`、`icon_only_buttons` 等，本 fork 没有跟进——不是删掉的，是基线就早。

## 改了什么

| 文件 | 改动 |
|---|---|
| `app/build.gradle` | `minSdk 26 → 23`；开启 core library desugaring（`desugar_jdk_libs 2.1.5`），让 Festival / RaceWeekend 用的 `java.time` 在 API 23 上跑 |
| `Compat.java` **新** | API 23 兼容层：`startWithFallback()`（`startActivity` 找不到时回落）、`getDrawable` / `getSystemService(Class)` / `NotificationChannel` 等按版本分流 |
| `Weather.java` **新** | 天气卡：open-meteo 当前天气 + 明后两天，WMO code → 图标映射，昼夜判断 |
| `res/layout/activity_main.xml` | 顶部天气面板（大图标 + 温度 + 城市 + 明天/后天两列） |
| `res/drawable/ic_wx_*.xml` **新** | 10 个 24dp 矢量天气图标，含夜间变体（月牙 + 星） |
| `res/raw/isrg_root_x1.pem` **新** | 见下文「Let's Encrypt」 |
| `AppAdapter.java` | ①`app_banners` 配置驱动的横幅覆盖（`bundledBanner` / `loadBanner`）；②无横幅时用 `TileArt` 取图标主色做渐变底；③长按磁贴弹菜单（卸载应用 / 应用信息）；④`entryAt()` 供按键菜单取当前磁贴 |
| `MainActivity.java` | ①天气卡绑定与定时刷新；②`handleTileMenuKey()`：按 MENU 对当前聚焦磁贴弹同一个菜单；③若干 API 23 回落 |
| `HomeKeyService.java` | 见下文「Home 键」 |
| `QuickSettingsActivity.java` | 改用 `Compat.startWithFallback` |
| `res/values/strings.xml`、`values-zh-rCN/strings.xml` | 新增 `tile_uninstall` / `tile_app_info` 与天气相关文案 |
| `res/values/config.xml` | 三条横幅映射（SmartTube / 唱吧 / 多屏互动） |
| `res/drawable-nodpi/banner_*.png` **新** | 三张 480×270 横幅，渐变底 + 白色字形 + 字标 |

## 两个绕不开的坑

### Let's Encrypt：Android 6 的 CA 库里没有 ISRG Root X1

天气走 `api.open-meteo.com`（Let's Encrypt 签发）。Android 6.0（2015）的 `/system/etc/security/cacerts` 共 158 张 CA，**没有 ISRG Root X1**，TLS 握手直接失败，表现是天气永远不出现、也没有任何异常日志。

解法不是关校验，而是把根证书打进包里：`res/raw/isrg_root_x1.pem` + 一个合成的 `X509TrustManager`（先走系统默认，失败再试内置根），只对天气请求生效。

### Home 键：TCL 把 Home 写死成自家桌面

这台机器不用标准 HOME 机制，桌面由 `persist.sys.bootpackage` 指定；按 Home 键会被 `PhoneWindowManager.startDockOrHome` 强行拉回 `com.tcl.cyberui`，**按键在系统策略层就被吃掉，无障碍服务收不到**。

所以 `HomeKeyService` 不监听按键，改成看门狗：收到 `TYPE_WINDOW_STATE_CHANGED` 且包名是 `com.tcl.cyberui` 时，700 ms 节流地把自己拉回前台。

## 构建

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk      # 需要 Platform 34 + Build-Tools 34.0.0
cd launcher
./gradlew assembleRelease                     # Windows: gradlew.bat
```

产物 `launcher/app/build/outputs/apk/release/app-release.apk`。release 沿用上游配置：R8 压缩 + debug key 签名（自用足够）。

## 部署（这台电视）

TCL 不走标准 HOME，桌面要装成系统应用：

```bash
adb push app-release.apk /data/local/tmp/TvHome.apk
# 需要 root：remount /system rw
adb shell "mount -o rw,remount /system"
adb shell "cp /data/local/tmp/TvHome.apk /system/app/TvHome/TvHome.apk"
adb shell "chmod 755 /system/app/TvHome; chmod 644 /system/app/TvHome/TvHome.apk"
adb shell "chown root:root /system/app/TvHome/TvHome.apk"
adb shell "chcon u:object_r:system_file:s0 /system/app/TvHome/TvHome.apk"
adb shell "mount -o ro,remount /system"
adb shell "rm -f /data/dalvik-cache/arm64/system@app@TvHome@TvHome.apk@classes.dex"
adb reboot
```

**必须删 dalvik-cache 再重启**，否则跑的还是旧代码。装好后：

```bash
adb shell setprop persist.sys.bootpackage com.gathin.tvhome
```

> 热替换不重启会 `Resources$NotFoundException`（旧进程拿着新资源表），改完一律重启。

## 已知限制

- 天气城市默认「上海」，用 `settings put global tvhome_city <城市名>` 改。
- 桌面第一排（程序坞）长按右上角「设置」→「编辑程序坞」/「调整应用顺序」。
- 只在这台机器（1080p UI、2 GB RAM）上验证过。
