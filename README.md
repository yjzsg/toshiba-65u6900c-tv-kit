# 东芝 65U6900C 电视改造套件

一台 2019 年的入门级 4K 电视（TCL 代工，机芯 MS838AS13，Android 6.0 / API 23，4×Cortex-A53 1.5 GHz，2 GB RAM，无 GMS，系统早已停更）的实测改造记录与自研组件。

原厂系统卡顿、广告多、预装多、桌面不可换、遥控器语音键被讯飞助手独占。这套东西把能改的都改了，全部可回滚。

> 仅用于**你自己拥有**的设备。改动涉及系统分区、包管理与厂商私有服务，误操作可能导致无法开机，风险自负。

## 目录

| 目录 | 内容 |
|---|---|
| [`livetv/`](livetv/) | 自建 Android TV 直播 App（`com.local.livetv`），37 个央视/卫视频道，遥控器换台 |
| [`voicekey/`](voicekey/) | 把遥控器语音键做成**语音输入法**（`com.iflytek.sample.view`），说句话直接上屏到任意输入框 |
| [`launcher/`](launcher/) | [cleanyourtv](https://github.com/Gathin23/cleanyourtv) v2.5 的 fork：API 23 移植 + 天气卡 / 程序坞编辑 / 长按卸载 / 图标统一 |
| [`tools/`](tools/) | 设备侧工具：TCL root 通道、APK 结构侦察、部署脚本 |
| [`docs/`](docs/) | [东芝65U6900C-优化记录.md](docs/东芝65U6900C-优化记录.md) —— 二十余节完整实测记录（内存/CPU 口径、包清单、踩坑、回滚命令） |

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

## 构建前提

所有 App 都不依赖 Android Studio，用 `aapt + javac + d8 + zipalign + apksigner` 直接打包，见各子目录的 `build.py`。需要：

- JDK 17
- Android SDK build-tools 34（`aapt` / `zipalign` / `apksigner`）
- `platforms/android-34/android.jar`
- 一个自己的签名 keystore

`launcher/` 例外，它是 Gradle 工程，需要 JDK 17 + Android SDK Platform 34。

## 许可

本仓库自有代码为 MIT。`launcher/` 基于上游 [cleanyourtv](https://github.com/Gathin23/cleanyourtv) 亦为 MIT（Copyright © 2026 Gathin），补丁文件保留上游版权声明。
