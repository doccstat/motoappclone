#!/usr/bin/env python3
"""Verify a downloaded release before distribution; never signs or publishes."""
import argparse
import hashlib
import importlib.util
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('motoappclone_build', ROOT / 'build.py')
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


def verify(directory, tag, pin, tools):
    if not re.fullmatch(r'[1-9][0-9]*-[0-9]+(?:\.[0-9]+)+', tag):
        raise ValueError('Expected a versionCode-versionName release tag')
    expected = build.certificate_digest(pin)
    apk = directory / 'MotoAppClone.apk'
    checksum = hashlib.sha256(apk.read_bytes()).hexdigest()
    if (directory / 'MotoAppClone.apk.sha256').read_text(encoding='ascii') != f'{checksum}  MotoAppClone.apk\n':
        raise ValueError('Release checksum or filename does not match')
    windows = os.name == 'nt'
    build.verify_certificate(tools / ('apksigner.bat' if windows else 'apksigner'), apk, dict(os.environ), expected)
    result = subprocess.run([str(tools / ('aapt2.exe' if windows else 'aapt2')), 'dump', 'badging', str(apk)],
                            check=True, capture_output=True, text=True)
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", result.stdout, re.MULTILINE)
    code, version = tag.split('-', 1)
    if not package or package.groups() != ('com.lixingchi.motoappclone', code, version):
        raise ValueError('APK package/version does not match the release')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', required=True, type=Path)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--build-tools', required=True, type=Path)
    args = parser.parse_args()
    verify(args.directory, args.tag, os.environ.get('SIGNING_CERT_SHA256', ''), args.build_tools)
    print('Release checksum, signer, package and version verified.')
