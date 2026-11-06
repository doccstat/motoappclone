#!/usr/bin/env python3
"""Build and test with installed SDK tools; never connects to a device or creates a key."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent
API_SHA256 = 'f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25'


def certificate_digest(value):
    digest = value.replace(':', '').strip().lower()
    if not re.fullmatch(r'[0-9a-f]{64}', digest):
        raise ValueError('SIGNING_CERT_SHA256 must be a 64-hex-digit certificate SHA-256 fingerprint')
    return digest


def verify_certificate(signer, apk, env, expected):
    result = subprocess.run([str(signer), 'verify', '--verbose', '--print-certs', str(apk)],
                            check=True, env=env, capture_output=True, text=True)
    if expected is not None:
        signers = re.findall(r'^Number of signers:\s*(\d+)\s*$', result.stdout, re.MULTILINE)
        digests = re.findall(r'certificate SHA-256 digest:\s*([0-9A-Fa-f:]+)', result.stdout)
        if signers != ['1'] or not digests or any(certificate_digest(value) != expected for value in digests):
            raise SystemExit('APK certificate does not match SIGNING_CERT_SHA256')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', action='store_true',
                        help='require an existing signing key and verify the pinned certificate')
    args = parser.parse_args()
    expected_signer = None
    if args.release:
        required = ('SIGNING_KEYSTORE', 'SIGNING_STORE_PASSWORD',
                    'SIGNING_KEY_PASSWORD', 'SIGNING_KEY_ALIAS')
        missing = [name for name in required if not os.environ.get(name)]
        if missing:
            raise SystemExit('Release signing requires: ' + ', '.join(missing))
        if not Path(os.environ['SIGNING_KEYSTORE']).is_file():
            raise SystemExit('Release keystore does not exist; refusing to generate a replacement')
        try:
            expected_signer = certificate_digest(os.environ.get('SIGNING_CERT_SHA256', ''))
        except ValueError as error:
            raise SystemExit(str(error))
    windows = os.name == 'nt'
    sdk = Path(os.environ['ANDROID_HOME'])
    java = Path(os.environ['JAVA_HOME']) / 'bin'
    android = sdk / 'platforms/android-36/android.jar'
    tools = Path(os.environ.get('BUILD_TOOLS', sdk / 'build-tools/37.0.0'))
    api = Path(os.environ['XPOSED_API_JAR'])
    if hashlib.sha256(api.read_bytes()).hexdigest() != API_SHA256:
        raise SystemExit('Unexpected Xposed API 82 jar; see BUILDING.md for URL and hash')
    out = Path(os.environ.get('BUILD_DIR', Path.home() / '.cache/motoappclone/build')).resolve()
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
            ROOT / 'src/com/lixingchi/motoappclone/Hooks.java',
            ROOT / 'src/com/lixingchi/motoappclone/Deoptimization.java',
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
            signed = build / 'signed.apk'
            run(tool('apksigner'), 'sign', '--ks', key,
                '--ks-pass', 'env:SIGNING_STORE_PASSWORD', '--key-pass', 'env:SIGNING_KEY_PASSWORD',
                '--ks-key-alias', os.environ['SIGNING_KEY_ALIAS'], '--out', signed, unsigned)
            verify_certificate(tool('apksigner'), signed, env, expected_signer)
            run(tool('zipalign'), '-c', '4', signed)
            output = out / 'MotoAppClone.apk'
            shutil.copyfile(signed, output)
        checksum = hashlib.sha256(output.read_bytes()).hexdigest()
        (out / (output.name + '.sha256')).write_text(f'{checksum}  {output.name}\n', encoding='ascii')
        print(checksum, output)


if __name__ == '__main__':
    main()
