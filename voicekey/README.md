# voicekey —— 把遥控器语音键变成输入法

电视自带讯飞语音助手，语音键只能唤起助手，说出来的字进不了输入框。这个 IME 把语音键接到任意 App 的输入框上：按语音键 → 说一句话 → 文字直接上屏。

包名 `com.iflytek.sample.view`（刻意与讯飞自带示例客户端同名，见下文「原理」），minSdk 21 / targetSdk 22。

> 这是**针对特定机型**（TCL/东芝 Android 6 电视，带 `com.iflytek.xiri`）的实现，不是通用语音输入法。

## 用法

| 操作 | 行为 |
|---|---|
| 遥控器**语音键** | 开始听，说完自动上屏 |
| **菜单键**短按 | 在「🎤 语音」和「⌨ 键盘」两页之间切换 |
| **菜单键**长按 0.6 s | 切换到下一个输入法（回拼音键盘） |
| 面板右上「切换输入法」 | 同上 |

键盘页有数字页和字母页，方向键移动、OK 输入，够用来打 IP、密码、NAS 地址。

## 安装

```bash
adb install -r -g voicekey.apk
adb shell ime enable com.iflytek.sample.view/.VkImeService
adb shell ime set    com.iflytek.sample.view/.VkImeService
adb shell pm grant com.iflytek.sample.view android.permission.WRITE_SECURE_SETTINGS
```

注意顺序：**不要**在本 IME 正被设为默认时覆盖安装，否则 adb 会掉线。要更新时先 `ime set com.android.inputmethod.pinyin/.PinyinIME`，装完再切回来。

回滚：

```bash
adb shell ime set com.android.inputmethod.pinyin/.PinyinIME
adb uninstall com.iflytek.sample.view
```

## 原理

三段拼起来：

### 1. 语音键（厂商键 4113）会投递给当前输入法

TCL 框架把遥控器语音键转成 `keyCode=4113` 的注入事件，同时派发给「输入法窗口」和语音助手。所以只要自己是默认输入法，`onKeyDown(4113)` 就能收到。一次按键会连发 7~8 个 down，代码里做了去抖。

前提：`com.iflytek.xiri` 必须装着且没被冻结——4113 的派发链路经过它。

### 2. 识别不自己做，借 xiri 的引擎

麦克风被助手独占：普通 App `AudioRecord.read()` 恒返回 0（`getState()=1`，`getMinBufferSize=640`），同一时刻 xiri 自己在录音。所以不要抢麦克风。

xiri 里有个**无权限保护、exported** 的 AIDL 服务：

```
component  com.iflytek.xiri / tv.yuyin.tcl.aidl.WriteRecognizerService
descriptor tv.yuyin.tcl.aidl.IWriteRecognizerAidlInterface
transact   1 = start()   2 = write(byte[] bytes, int length)   3 = stop()   4 = cancel()
```

灌 16 kHz / 单声道 / PCM16 的裸音频进去就能流式识别。`XiriAsr.java` 是这套调用的封装（最终版没用它，因为拿不到麦克风；留着给能拿到音频的场景）。

### 3. 结果怎么取：劫持 UI 回调

`WriteRecognizerService` 不回传结果。但 xiri 的 `MainView` 会**隐式 bind** action `com.iflytek.xiri.voice.VIEW`——哪个包声明了这个 action 就绑谁。

本 App 的 `RelayService` 声明了该 action（priority 1000），于是每条 UI 更新都会到我们手里：

```
Bundle{action=10020; obj=测}      // 流式中间结果
Bundle{action=10005; obj=你好你好}  // 最终结果
```

取到 `obj` 后原样转发给 `com.tcl.walleve`（真正的语音助手），保持助手本身可用；把 walleve 冻结后，TTS 播报和悬浮窗就没了，只剩文字。

IME 监听 `RelayService.lastText`，稳定 800 ms 后 `commitText()`。

## 已知限制

- 依赖 `com.iflytek.xiri`；换机型/换固件就没有了。
- 面板用 fullscreen(extract) 模式，因为**只有这个模式下框架才把方向键交给 IME**（inline 模式只透传 MENU 和 4113）。代价是面板上方多一条 extract 输入框。
- App 自己的 `EditText` 会先吃掉方向键（移动光标），所以「用方向键走到键盘页」在部分输入框里不灵，用菜单键翻页更可靠。
- 麦克风不能自己录，所以没有「离线识别」这条路。

## 构建

同 [`../livetv`](../livetv)：

```bash
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk17
export KEYSTORE=/path/to/your.jks
export KS_ALIAS=youralias
export KS_PASS=yourpass
python build.py
```
