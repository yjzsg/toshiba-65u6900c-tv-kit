# 东芝 65U6900C 电视改造套件

一台 2019 年的入门级 4K 电视（TCL 代工，机芯 MS838AS13，Android 6.0 / API 23，4×Cortex-A53 1.5 GHz，2 GB RAM，无 GMS，系统早已停更）的实测改造记录与自研组件。

原厂系统卡顿、广告多、预装多、桌面不可换、遥控器语音键被讯飞助手独占。这套东西把能改的都改了，全部可回滚。

> 仅用于**你自己拥有**的设备。改动涉及系统分区、包管理与厂商私有服务，误操作可能导致无法开机，风险自负。

![改完之后的桌面](docs/img/home.png)

*实拍（1920×1080）：Aerial 桌面 —— 顶部天气与时钟，下面一排应用磁贴。*

## 优化前后对比

| 维度 | 原厂 | 现在 |
|---|---|---|
| 桌面 | TCL 瀑布流 `com.tcl.cyberui`，系统里唯一的 HOME 提供者，Home 键强制回弹，换不了 | Aerial（`pengchujin/aerial-tv`）：自动枚举全部应用、天气时钟、可编辑布局；Home 键用自研 `homekey/` 接回来 |
| 开机 | 先播 TCL 开机广告（`com.tcl.bootadservice`） | 直接进桌面 |
| 预装常驻 | 广告 / 统计 / 推送 / 应用商店 / OTA / 语音助手全套在跑 | 31 个 `pm hide`（4 个因语音输入法需要已恢复，实冻 27），`rollback.ps1` 一条命令还原 |
| 装第三方 APK | 被 `com.tcl.packageinstaller.service` 秒关，只能走 TCL 应用商店 | `adb install` / U 盘直装，重启后仍有效 |
| 遥控器语音键 | 拉起讯飞语音助手，弹广告，打不出字 | 语音输入法，说完直接上屏到任意输入框 |
| 看电视直播 | 无（原厂只剩已下架的 VOD 入口） | 自建直播 App + NAS 上的自建 IPTV 服务：141 个频道（央视/卫视/港澳台/地方台），带 EPG、收藏夹、高清优先 |
| 第三方 App | 0 | 10 个（其中 4 个是本仓库自研） |
| 桌面图标 | 海报位，比例各不相同 | Aerial 的统一磁贴 |

### 没有变的（也是软件优化的天花板）

真正的瓶颈是 2019 年的入门级 SoC + 2 GB RAM + Android 6，这部分改不动：

- **CPU**：整机占用约 50%（12 秒 `/proc/<pid>/stat` 增量采样，4 核合计）。其中 `MiSysSrv`（MStar 画质 / Scaler，实际 cmdline 是 `/applications/bin/MiSysSrv`，进程自行改名）单进程占 75.3% 单核 ≈ 18.8% 整机；`sitatvservice` 33.8% 单核 ≈ 8.4%。这两个是画质与信号处理链，停不得。
- **超频不可行**（三层锁）：`adb root` 失败、写 `scaling_governor` 被 `Permission denied`、`cpuinfo_max_freq=1500000` 写死在内核 OPP 表里，MStar 不公开源码。
- **内存**：`dumpsys meminfo` 口径下 Free RAM 随运行时长在 605～823 MB 之间波动（同一台机器不同时刻实测），冻结包带来的是 +73 MB free / −47 MB used 量级的改善——有意义，但不是质变。`MemFree` 本身没有参考价值，会被页面缓存吃满（Cached 常年 900 MB+）。

结论：软件侧已经做到边际，想明显提速只有外接电视盒子。

## 目录

| 目录 | 内容 |
|---|---|
| [`livetv/`](livetv/) | 自建直播 App（`com.local.livetv`）：拉 NAS 上的 IPTV 订阅，141 个频道，EPG、收藏夹、数字键选台、高清优先、半透明玻璃列表 |
| [`voicekey/`](voicekey/) | 把遥控器语音键做成**语音输入法**（`com.iflytek.sample.view`），说句话直接上屏 |
| [`homekey/`](homekey/) | Home 键修复（`com.local.homekey`）：用无障碍服务接住系统的 `homekey` 广播，把 Aerial 拉回前台 |
| [`launcher/`](launcher/) | **已退役**：[cleanyourtv](https://github.com/Gathin23/cleanyourtv) v2.5 的 fork（API 23 移植），2026-10-30 起桌面换成 Aerial，源码留档 |
| [`tools/`](tools/) | 设备侧工具：TCL root 通道、APK 结构侦察、部署脚本 |
| [`docs/`](docs/) | [东芝65U6900C-优化记录.md](docs/东芝65U6900C-优化记录.md) —— 二十八节完整实测记录（内存/CPU 口径、包清单、ADB 开关、Aerial 桌面、IPTV 部署、踩坑、回滚命令） |

## 设备事实（后续所有结论的前提）

```
persist.sys.tvname        = TSB 65U6900C-80BF
ro.software.version_id    = V8-S38AS13-LF1V041   （官方最高版，已停更）
平台                      = aosp_tsbU9800C / tsbU9800C，机芯 MS838AS13
系统                      = Android 6.0（API 23）/ MRA58K，userdebug + dev-keys
CPU                       = 4×Cortex-A53 1.5 GHz（ondemand）
内存                      = 2 GB（MemTotal 1,871,796 kB）
显示                      = 面板 3840×2160，Android UI 1920×1080（硬件上采样）
无 GMS / 无 Play 商店 / 无 dm-verity / /system ext4 ro
```

## 三条最有用的结论

1. **装不上第三方 APK 不是签名问题**，是 TCL 的 `com.tcl.packageinstaller.service` 在监视并秒关系统安装器。`pm hide com.tcl.packageinstaller.service` 之后任何 APK 都能装，且跨重启保持。
2. **遥控器语音键（厂商键 4113）会投递给当前输入法**，所以可以把语音助手变成输入法；识别本身不要自己录音（麦克风被助手独占，`AudioRecord.read()` 恒返回 0），而是复用 xiri 的 `WriteRecognizerService`（AIDL，无权限保护）并劫持它的 UI 回调通道取文字。
3. **本机 MediaPlayer 不支持 IPv6 源**（`DataSource_M3U8: httpResponseCode=0` 死循环），直播源必须用 IPv4。

## 打开 ADB 调试

这台机器**出厂就开着 ADB over TCP 5555**，不需要开发者选项，也不用遥控器输密码。证据在系统镜像里：

```
/system/build.prop:73  service.adb.tcp.port=5555
/system/build.prop:74  persist.adb.tcp.port=5555
/system/build.prop:75  persist.service.adb.enable=1
/system/build.prop:76  ro.adb.secure=1
```

只要电视和电脑在同一网段：

```bash
adb connect 192.168.1.11:5555
```

首次连接电视上会弹「允许 USB 调试吗？」，勾**一律允许**再确定（`ro.adb.secure=1`，必须授权一次；授权后公钥写进 `/data/misc/adb/adb_keys`，重启不丢）。

验证：

```bash
adb devices          # 192.168.1.11:5555   device
adb shell getprop | grep adb
# [init.svc.adbd]: [running]
# [persist.adb.tcp.port]: [5555]
# [persist.service.adb.enable]: [1]
```

### 如果 ADB 被关了

设置里没有「开发者选项」这个入口，TCL 把它藏了。系统设置 APK（`com.tcl.settings`）里确实有这套 UI——`layout_adb`、`common_adb`（标签就叫「ADB」）、`getCurAdbState`、`updateAdbText` 这些符号都在，状态读的是 `persist.sys.service.adbd.enable`（日志里是 `getCurAdbState sysconfig = running`）。

入口是 **设置 → 系统 → 系统信息**，然后遥控器依次按 **上 / 下 / 左 / 右**。

> 这条路径来自早期操作记录。写文档时在这台机器上复验，三级页面打不开——`E/com.tcl.settings.view.ThirdView: layout == null`，点「系统信息」后 Activity 直接 finish 回桌面，所以没能再走一遍。ADB 本来就开着，也没必要。

### 其它打开方式

- **有 root shell 时**（见 [`tools/`](tools/)）：
  ```bash
  setprop persist.service.adb.enable 1
  setprop persist.adb.tcp.port 5555
  stop adbd; start adbd
  ```
- **USB**：`/default.prop` 里 `persist.sys.usb.config=adb`，插 USB 也能连；但这机器没有 USB-A 主机口，实际都走网络。

### 关掉

```bash
adb shell setprop persist.service.adb.enable 0    # 重启后 adbd 不再起
```

## 构建前提

所有 App 都不依赖 Android Studio，用 `aapt + javac + d8 + zipalign + apksigner` 直接打包，见各子目录的 `build.py`。需要：

- JDK 17
- Android SDK build-tools 34（`aapt` / `zipalign` / `apksigner`）
- `platforms/android-34/android.jar`
- 一个自己的签名 keystore

`launcher/` 例外，它是 Gradle 工程，需要 JDK 17 + Android SDK Platform 34。

`homekey/` 用的是 aapt2（`livetv/` 用 aapt），因为它的 `res/xml/accessibility-service` 配置需要 aapt2 的资源处理。

## 许可

本仓库自有代码为 MIT。`launcher/` 基于上游 [cleanyourtv](https://github.com/Gathin23/cleanyourtv) 亦为 MIT（Copyright © 2026 Gathin），改动文件保留上游版权声明。

Aerial 桌面本体不随本仓库分发，从 [pengchujin/aerial-tv](https://github.com/pengchujin/aerial-tv) 自行获取（MIT）。
