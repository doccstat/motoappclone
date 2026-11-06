#!/usr/bin/env python3
"""Export a local, read-only support report without account data or raw device logs."""
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

PACKAGE = 'com.lixingchi.motoappclone'
EVENTS = {
    'hooks registered in ': 'registered',
    'server policy active:': 'server_active',
    'REFUSED activation:': 'activation_refused',
    'creation blocked:': 'creation_blocked',
    'profile lookup failed closed:': 'lookup_failed',
    'provider publication callback failed:': 'provider_callback_failed',
    'hook rollback failed:': 'rollback_failed',
    'retained stopped clone ': 'boot_removal_prevented',
}


def log_summary(text):
    counts = Counter()
    errors = Counter()
    for line in text.splitlines():
        if 'MotoAppClone: ' not in line:
            continue
        message = line.split('MotoAppClone: ', 1)[1]
        for prefix, event in EVENTS.items():
            if message.startswith(prefix):
                counts[event] += 1
                error = re.search(r'\b([A-Za-z]+(?:Exception|Error))\b', message[len(prefix):])
                if error:
                    errors[error[1]] += 1
                break
    return {'events': dict(counts), 'error_types': dict(errors)}


def collect(adb, serial=None):
    prefix = [adb] + (['-s', serial] if serial else [])
    results = {}

    def query(name, *args):
        try:
            result = subprocess.run(prefix + ['shell', *args], capture_output=True,
                                    text=True, encoding='utf-8', errors='replace', timeout=15)
            results[name] = {'exit_code': result.returncode}
            return result.stdout if result.returncode == 0 else ''
        except (OSError, subprocess.TimeoutExpired) as error:
            results[name] = {'error_type': type(error).__name__}
            return ''

    device = {name: query(name, 'getprop', prop).strip() for name, prop in (
        ('manufacturer', 'ro.product.manufacturer'), ('model', 'ro.product.model'),
        ('android_release', 'ro.build.version.release'), ('sdk', 'ro.build.version.sdk'),
        ('build', 'ro.build.display.id'))}
    package = query('package', 'dumpsys', 'package', PACKAGE)
    version = {name: match[1] for name in ('versionName', 'versionCode')
               if (match := re.search(r'\b' + name + r'=([\d.]+)(?:\s|$)', package))}
    root = Path(__file__).resolve().parents[1]
    manifest = ET.parse(root / 'AndroidManifest.xml').getroot()
    android = '{http://schemas.android.com/apk/res/android}'
    return {'report_version': 1, 'package': PACKAGE, 'device': device,
            'installed_version': version,
            'source_version': manifest.attrib[android + 'versionName'],
            'source_version_code': manifest.attrib[android + 'versionCode'],
            'operations': results, 'scope': 'not collected'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb', help='ADB executable')
    parser.add_argument('--serial', help='explicit device selector; not included in the report')
    parser.add_argument('--framework-log', type=Path, help='summarize module events from an exported framework log')
    parser.add_argument('--output', type=Path, required=True, help='new JSON file; existing files are not overwritten')
    args = parser.parse_args()
    report = collect(args.adb, args.serial)
    if args.framework_log:
        report['framework'] = log_summary(args.framework_log.read_text(encoding='utf-8', errors='replace'))
    with args.output.open('x', encoding='utf-8') as output:
        json.dump(report, output, indent=2, ensure_ascii=False)
        output.write('\n')


if __name__ == '__main__':
    main()
