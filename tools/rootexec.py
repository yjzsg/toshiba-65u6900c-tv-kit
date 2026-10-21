#!/usr/bin/env python3
"""通过 tcl.ISeekerService 以 root 执行命令。

限制：服务端把 String16 当 String8 读 —— 长度取码元个数 N，内容取前 N 字节
（UTF-16 低/高字节交错）。因此要执行长度 m 的命令，必须发 m 个码元，
其中前 ceil(m/2) 个码元携带命令字节：
    码元 i = 命令字节[2i] | (命令字节[2i+1] << 8)
其余码元为占位（内容不会被读到）。
"""
import os
import subprocess
import sys

ADB = os.environ.get('ADB', 'adb')
DEV = os.environ.get('DEVICE', '192.168.1.11:5555')


def build_arg(cmd: str) -> str:
    b = cmd.encode()
    m = len(b)
    units = []
    for i in range(0, m, 2):
        lo = b[i]
        hi = b[i + 1] if i + 1 < m else 0
        units.append(lo | (hi << 8))
    while len(units) < m:
        units.append(0x41)          # 'A' 占位
    s = ''.join(chr(u) for u in units)
    return ''.join('\\x%02x' % x for x in s.encode('utf-8'))


def run(cmd: str, timeout=1800):
    esc = build_arg(cmd)
    shell_cmd = f"service call tcl.ISeekerService 1 s16 $'{esc}' < /dev/null"
    p = subprocess.run([ADB, '-s', DEV, 'shell', shell_cmd],
                       capture_output=True, timeout=timeout)
    out = p.stdout.decode('utf-8', 'replace')
    return out


def parse(out: str) -> str:
    """从 Parcel 十六进制转储里还原可打印文本。"""
    import re
    chunks = []
    for line in out.splitlines():
        m = re.match(r'\s*0x[0-9a-f]+:\s+((?:[0-9a-f]{8}\s+){1,4})', line)
        if not m:
            continue
        for w in m.group(1).split():
            chunks.append(bytes.fromhex(w)[::-1])
    raw = b''.join(chunks)
    txt = raw.decode('utf-8', 'replace')
    return txt.strip('\x00').strip()


if __name__ == '__main__':
    cmd = ' '.join(sys.argv[1:]) or 'id'
    out = run(cmd)
    txt = parse(out)
    print(txt if txt else out[:400])
