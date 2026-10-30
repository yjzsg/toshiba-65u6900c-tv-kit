# homekey —— Home 键修复

一个只有 60 行 Java 的无障碍服务，专门在这台 TCL 电视上把 **Home 键**接回来。

包名 `com.local.homekey`，minSdk 21 / targetSdk 22，APK 约 13 KB。

## 为什么需要它

这台电视（TCL 代工 MS838AS13，Android 6.0）的 `PhoneWindowManager.startDockOrHome()` 是**硬编码**的：

```
D WindowManager: ###startDockOrHome: mHomeIntent = ... cmp=com.tcl.cyberui/.MainActivity
```

它固定去启动原厂桌面 `com.tcl.cyberui/.MainActivity`，而这个组件在本机是坏的：

| 版本 | 位置 | 结果 |
|---|---|---|
| 6.0.41（TCL OTA 更新版，44 MB） | `/data/app/com.tcl.cyberui-1` | `Activity class does not exist` |
| 3.2.00（系统版） | `/system/priv-app/CyberUI` | 启动即崩：`ClassNotFoundException: com.tcl.cyberui.LauncherApp` |

于是 Home 键按下去毫无反应。系统分区改不了，`pm enable` 也被拒（`package uid=1000`），所以走**无障碍服务**这条路。

## 原理

关键不在于拦截按键，而在于：**系统处理 Home 键时，仍然会广播 `ACTION_CLOSE_SYSTEM_DIALOGS` 且 `reason=homekey`**（PhoneWindowManager 的标准行为）。这个广播发给所有注册者，绕开了 Android 6 无障碍按键过滤那套「同一时刻只有一个服务能过滤」的顺序问题。

所以这个服务只做一件事：

```java
IntentFilter f = new IntentFilter("android.intent.action.CLOSE_SYSTEM_DIALOGS");
registerReceiver(homeBroadcast, f);

// 收到 reason=homekey 就把 Aerial 拉回前台
Intent i = new Intent(Intent.ACTION_MAIN);
i.addCategory(Intent.CATEGORY_HOME);
i.setComponent(new ComponentName("app.orchard.tv", "app.orchard.tv.MainActivity"));
i.addFlags(NEW_TASK | RESET_TASK_IF_NEEDED | SINGLE_TOP);
startActivity(i);
```

实测日志：

```
HomeKey : CLOSE_SYSTEM_DIALOGS reason=homekey
HomeKey : homekey broadcast; bringing Aerial to front
focus: app.orchard.tv/app.orchard.tv.MainActivity
```

重启后依然有效。

## 安装

```bash
adb install -r homekey.apk
adb shell settings put secure enabled_accessibility_services \
  app.orchard.tv/app.orchard.tv.guard.HomeGuardService:com.local.homekey/com.local.homekey.HomeKeyService
adb shell settings put secure accessibility_enabled 1
```

两个服务要一起启用： Aerial 的 `HomeGuardService` 负责开机时把原厂桌面抢下来，这个负责 Home 键本身。

## 构建

```bash
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk17
export KEYSTORE=/path/to/your.jks
export KS_ALIAS=youralias
export KS_PASS=yourpass
python build.py          # 产物 ./homekey.apk
```

## 两个坑

- `canRequestFilterKeyEvents="true"` 光写在 manifest 里不够，运行时还要 `serviceInfo.flags |= FLAG_REQUEST_FILTER_KEY_EVENTS` 再 `setServiceInfo(info)`，否则收不到按键（虽然这个服务最终走的是广播路径）。
- manifest 里的 `accessibilityFlags` 在 Android 6 上要写 `flagRequestFilterKeyEvents`（枚举值 = 32），写 `0` 会直接编译报错。
