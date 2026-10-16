#!/usr/bin/env python3
"""Build and test with installed SDK tools; never connects to a device or creates a key."""
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent
API_SHA256 = 'f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25'


def main():
    windows = os.name == 'nt'
    sdk = Path(os.environ['ANDROID_HOME'])
    java = Path(os.environ['JAVA_HOME']) / 'bin'
    android = sdk / 'platforms/android-36/android.jar'
    tools = sdk / 'build-tools/37.0.0'
    api = Path(os.environ['XPOSED_API_JAR'])
    if hashlib.sha256(api.read_bytes()).hexdigest() != API_SHA256:
        raise SystemExit('Unexpected Xposed API 82 jar; see README for URL and hash')
    out = Path(os.environ.get('BUILD_DIR', Path.home() / '.cache/motoappclone')).resolve()
    if out == ROOT or ROOT in out.parents:
        raise SystemExit('BUILD_DIR must be outside the source tree')
    out.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, PATH=str(java) + os.pathsep + os.environ.get('PATH', ''),
               PYTHONDONTWRITEBYTECODE='1')

    def run(*args):
        subprocess.run([str(arg) for arg in args], check=True, env=env)

    def jtool(name):
        return java / (name + ('.exe' if windows else ''))

    def tool(name):
        suffix = ('.bat' if name in ('d8', 'apksigner') else '.exe') if windows else ''
        return tools / (name + suffix)

    run(sys.executable, '-B', '-m', 'unittest', 'discover', '-s', ROOT / 'tests', '-p', 'test_*.py')
    if os.environ.get('FIRMWARE_DIR'):
        run(sys.executable, '-B', ROOT / 'tools/audit_firmware.py', os.environ['FIRMWARE_DIR'])
    with tempfile.TemporaryDirectory(prefix='build-', dir=out) as temporary:
        build = Path(temporary)
        for folder in ('classes', 'tests', 'runtime-tests', 'dex'):
            (build / folder).mkdir()
        sources = sorted((ROOT / 'src').rglob('*.java'))
        run(jtool('javac'), '--release', '8', '-cp', os.pathsep.join(map(str, (android, api))),
            '-d', build / 'classes', *sources)
        run(jtool('javac'), '--release', '8', '-cp', build / 'classes', '-d', build / 'tests',
            ROOT / 'tests/ClonePolicyTest.java')
        run(jtool('java'), '-ea', '-cp', os.pathsep.join(map(str, (build / 'classes', build / 'tests'))),
            'com.lixingchi.motoappclone.ClonePolicyTest')
        run(jtool('javac'), '--release', '8', '-d', build / 'runtime-tests',
            ROOT / 'src/com/lixingchi/motoappclone/ClonePolicy.java',
            ROOT / 'src/com/lixingchi/motoappclone/RuntimeFixes.java',
            *sorted((ROOT / 'tests/runtime').rglob('*.java')))
        run(jtool('java'), '-ea', '-cp', build / 'runtime-tests',
            'com.lixingchi.motoappclone.RuntimeHooksTest')
        run(tool('d8'), '--lib', android, '--classpath', api, '--min-api', '36',
            '--output', build / 'dex', *sorted((build / 'classes').rglob('*.class')))
        run(tool('aapt2'), 'compile', '--dir', ROOT / 'res', '-o', build / 'resources.zip')
        run(tool('aapt2'), 'link', '-o', build / 'unsigned.apk', '-I', android,
            '--manifest', ROOT / 'AndroidManifest.xml', build / 'resources.zip')
        with zipfile.ZipFile(build / 'unsigned.apk', 'a', compression=zipfile.ZIP_DEFLATED) as apk:
            apk.write(build / 'dex/classes.dex', 'classes.dex')
            apk.write(ROOT / 'assets/xposed_init', 'assets/xposed_init')
        unsigned = out / 'MotoAppClone-unsigned.apk'
        run(tool('zipalign'), '-f', '4', build / 'unsigned.apk', unsigned)
        run(tool('zipalign'), '-c', '4', unsigned)
        output = unsigned
        # Signing is optional and uses an existing key. Never generate credentials in build caches.
        if 'SIGNING_KEYSTORE' in os.environ:
            key = Path(os.environ['SIGNING_KEYSTORE']).resolve()
            if not key.is_file():
                raise SystemExit('SIGNING_KEYSTORE must name an existing key')
            if ROOT in key.parents or '.cache' in key.parts:
                raise SystemExit('Keep signing credentials outside source and cache directories')
            output = out / 'MotoAppClone.apk'
            run(tool('apksigner'), 'sign', '--ks', key,
                '--ks-pass', 'env:SIGNING_STORE_PASSWORD', '--key-pass', 'env:SIGNING_KEY_PASSWORD',
                '--ks-key-alias', os.environ['SIGNING_KEY_ALIAS'], '--out', output, unsigned)
            run(tool('apksigner'), 'verify', '--verbose', output)
            run(tool('zipalign'), '-c', '4', output)
        print(hashlib.sha256(output.read_bytes()).hexdigest(), output)


if __name__ == '__main__':
    main()
