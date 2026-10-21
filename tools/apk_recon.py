# -*- coding: utf-8 -*-
"""Generic APK string recon: endpoints, appids, keys, speech-engine hints.

Usage: python apk_recon.py <apk> [outdir]
"""
import os
import re
import sys
import zipfile

apk = sys.argv[1]
out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
    os.environ.get("TEMP", "."), os.path.splitext(os.path.basename(apk))[0] + "_x")

if not os.path.isdir(out):
    os.makedirs(out)
    with zipfile.ZipFile(apk) as z:
        z.extractall(out)

KEY = re.compile(
    rb"(wss?://[^\s\"'<>]{4,120}|https?://[^\s\"'<>]{4,120}|"
    rb"APPID|appid|api_?key|apiKey|api_?secret|APISecret|"
    rb"msp\.do|iat-api|xfyun|voicecloud|open\.voicecloud|"
    rb"SpeechRecognizer|SpeechConstant|Msc[A-Za-z]*Recognizer|"
    rb"xiri2?\.hal|recognize\.service|walleve)", re.I)


def scan(path, label, limit=120):
    data = open(path, "rb").read()
    seen, uniq = set(), []
    for m in re.finditer(rb"[ -~]{4,240}", data):
        s = m.group()
        if KEY.search(s):
            t = s.decode("ascii", "ignore").strip()
            if t and t not in seen:
                seen.add(t)
                uniq.append(t)
    if uniq:
        print("\n=== %s : %d hits ===" % (label, len(uniq)))
        for t in uniq[:limit]:
            print("   ", t[:180])


for base, _, files in os.walk(out):
    for f in sorted(files):
        p = os.path.join(base, f)
        low = f.lower()
        size = os.path.getsize(p)
        if low.endswith((".dex", ".so")) or (low.endswith((".xml", ".json", ".cfg", ".dat")) and size < 300000):
            scan(p, os.path.relpath(p, out))
