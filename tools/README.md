# tools —— 设备侧与 APK 侧的小工具

都是单文件、无第三方依赖（只用标准库），改这台电视时写的，单独拿出来也能用。

## `rootexec.py` —— 通过 TCL 私有服务拿 root

这台电视的 `adb root` 不可用（`ro.secure=1`），但系统里有个 `tcl.ISeekerService`，它把 `service call` 传来的参数当 shell 命令执行，**以 root 身份**。

```bash
export ADB=adb
export DEVICE=192.168.1.11:5555
python rootexec.py id
# uid=0(root) gid=0(root) context=u:r:shell:s0
```

```python
from rootexec import run, parse
print(parse(run('cat /data/system/packages.xml | head -5')))
```

### 为什么代码里要做那套字符编码

服务端把 `String16` 当 `String8` 读：长度取 UTF-16 码元个数 `N`，内容只取前 `N` 个**字节**。所以要执行长度 `m` 的命令，得发 `m` 个码元，其中前 `ceil(m/2)` 个码元把命令字节两两打包（`byte[2i] | byte[2i+1] << 8`），剩下的补占位符。`build_arg()` 就是干这个的。

返回值是 Parcel 十六进制转储，`parse()` 把每 4 字节小端还原成文本。

> 仅对带这个服务的 TCL/东芝 Android 6 电视有效。别在别的设备上试。

## `apk_recon.py` —— APK 字符串侦察

```bash
python apk_recon.py target.apk [outdir]
```

解开 APK，扫 dex / so / xml 里的 URL、域名、appid、apiKey、以及语音引擎线索（`SpeechRecognizer`、`openspeech`、`xiri` 等），按文件分组打印命中行。当初就是靠它发现语音助手用的是 `*.openspeech.cn`、而自带输入法写死了已经消失的 `ime.voicecloud.cn`。

## `man_dump.py` —— 二进制清单转储

```bash
python man_dump.py target.apk
```

不装 aapt 也能看清单：按组件类型（activity / service / receiver / provider）列出类名、`exported`、权限、进程名，以及每个组件的 intent-filter action。`WriteRecognizerService`（无权限、exported）就是这么找出来的。

## `axml.py` —— 最小 AXML 解析器

`man_dump.py` 的底座。`parse_manifest(data)` 返回 `{package, versionCode, ..., components}`，`parse_pool()` / `decode_str()` 处理字符串池。也可以直接改二进制清单里的值（改 `minSdkVersion` 就是这么做的——属性表起点是 `off + 16 + astart`，不是 `off + 8`）。

## `dexcls.py` —— 最小 DEX 解析器

```bash
python dexcls.py target.apk WriteRecognizer IWriteRecognizer
```

读 dex header（偏移 56 起 12 个 u4：string/type/proto/field/method/class_defs 的 size+off），列出名字命中子串的类及其方法签名。用来确认 AIDL 接口的方法名与事务码，不用装 jadx。

## 环境

```bash
export ADB=/path/to/adb          # rootexec 用，默认 adb
export DEVICE=192.168.1.11:5555  # rootexec 用
```
