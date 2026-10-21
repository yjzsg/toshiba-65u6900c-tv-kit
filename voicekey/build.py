# -*- coding: utf-8 -*-
"""Build this APK with aapt + javac + d8 + zipalign + apksigner (no Gradle).

Required environment:
    ANDROID_HOME      Android SDK root (or ANDROID_SDK_ROOT)
    JAVA_HOME         JDK 17
    KEYSTORE          path to your .jks / .keystore
Optional:
    BUILD_TOOLS       build-tools version, default 34.0.0
    PLATFORM          platform dir name, default android-34
    KS_ALIAS          key alias, default androiddebugkey
    KS_PASS           keystore & key password, default android

Usage:  python build.py
"""
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
NAME = os.path.basename(ROOT)

SDK = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
JDK = os.environ.get('JAVA_HOME')
KEYSTORE = os.environ.get('KEYSTORE')
BT_VER = os.environ.get('BUILD_TOOLS', '34.0.0')
PLATFORM = os.environ.get('PLATFORM', 'android-34')
ALIAS = os.environ.get('KS_ALIAS', 'androiddebugkey')
KS_PASS = os.environ.get('KS_PASS', 'android')

for var, val in (('ANDROID_HOME', SDK), ('JAVA_HOME', JDK), ('KEYSTORE', KEYSTORE)):
    if not val:
        sys.exit('missing environment variable %s (see docstring)' % var)

EXE = '.exe' if os.name == 'nt' else ''
BT = os.path.join(SDK, 'build-tools', BT_VER)
JAR = os.path.join(SDK, 'platforms', PLATFORM, 'android.jar')
JAVAC = os.path.join(JDK, 'bin', 'javac' + EXE)
D8 = os.path.join(BT, 'd8.bat' if os.name == 'nt' else 'd8')
AAPT = os.path.join(BT, 'aapt' + EXE)
ZIPALIGN = os.path.join(BT, 'zipalign' + EXE)
APKSIGNER = os.path.join(BT, 'apksigner.bat' if os.name == 'nt' else 'apksigner')

for p in (JAR, JAVAC, D8, AAPT, ZIPALIGN, APKSIGNER, KEYSTORE):
    if not os.path.exists(p):
        sys.exit('not found: %s' % p)


def run(cmd, cwd=None):
    print('>>', os.path.basename(cmd[0]), ' '.join(cmd[1:3]))
    p = subprocess.run(cmd, cwd=cwd, capture_output=True)
    out = p.stdout.decode('utf-8', 'replace') + p.stderr.decode('utf-8', 'replace')
    if out.strip():
        print(out.strip()[:4000])
    if p.returncode != 0:
        sys.exit('FAILED rc=%d' % p.returncode)
    return out


def main():
    os.environ['JAVA_HOME'] = JDK
    os.environ['PATH'] = os.path.join(JDK, 'bin') + os.pathsep + os.environ.get('PATH', '')

    build = os.path.join(ROOT, 'build')
    if os.path.isdir(build):
        shutil.rmtree(build)
    classes = os.path.join(build, 'classes')
    dexout = os.path.join(build, 'dex')
    os.makedirs(classes)
    os.makedirs(dexout)

    src = os.path.join(ROOT, 'src')
    java_files = []
    for base, _, files in os.walk(src):
        java_files += [os.path.join(base, f) for f in files if f.endswith('.java')]

    run([JAVAC, '-source', '8', '-target', '8', '-nowarn', '-encoding', 'UTF-8',
         '-bootclasspath', JAR, '-d', classes] + java_files)

    class_files = []
    for base, _, files in os.walk(classes):
        class_files += [os.path.join(base, f) for f in files if f.endswith('.class')]

    run([D8, '--min-api', '21', '--lib', JAR, '--output', dexout] + class_files)

    unsigned = os.path.join(build, 'unsigned.apk')
    pkg = [AAPT, 'package', '-f',
           '-M', os.path.join(ROOT, 'AndroidManifest.xml'),
           '-S', os.path.join(ROOT, 'res')]
    assets = os.path.join(ROOT, 'assets')
    if os.path.isdir(assets):
        pkg += ['-A', assets]
    pkg += ['-I', JAR, '-F', unsigned]
    run(pkg)

    run([AAPT, 'add', unsigned, 'classes.dex'], cwd=dexout)

    aligned = os.path.join(build, 'aligned.apk')
    run([ZIPALIGN, '-p', '-f', '4', unsigned, aligned])

    signed = os.path.join(ROOT, NAME + '.apk')
    run([APKSIGNER, 'sign', '--ks', KEYSTORE,
         '--ks-pass', 'pass:' + KS_PASS, '--key-pass', 'pass:' + KS_PASS,
         '--ks-key-alias', ALIAS, '--v1-signing-enabled', 'true',
         '--v2-signing-enabled', 'false', '--out', signed, aligned])

    print('OK', signed, os.path.getsize(signed), 'bytes')


main()
