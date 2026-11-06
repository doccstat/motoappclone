import importlib.util
import json
from pathlib import Path
import subprocess
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('diagnostics', ROOT / 'tools/diagnostics.py')
diagnostics = importlib.util.module_from_spec(spec)
spec.loader.exec_module(diagnostics)


class DiagnosticsTest(unittest.TestCase):
    def test_logs_export_only_known_events_and_error_types(self):
        report = diagnostics.log_summary(
            'account=private@example.invalid secret=password\n'
            'MotoAppClone: retained stopped clone 12345\n'
            'MotoAppClone: REFUSED activation: java.lang.NoSuchMethodException: /private/name\n'
            'MotoAppClone: unknown message with secret=password\n')
        self.assertEqual(report, {'events': {'boot_removal_prevented': 1, 'activation_refused': 1},
                                  'error_types': {'NoSuchMethodException': 1}})
        for value in ('12345', '/private', 'password', 'private@example'):
            self.assertNotIn(value, json.dumps(report))

    def test_device_selection_and_package_dump_redaction(self):
        commands = []
        def run(command, **kwargs):
            commands.append(command)
            text = ('versionCode=5 minSdk=36\nversionName=0.5\nUser 900: name=private'
                    if 'dumpsys' in command else '制造商\n')
            return subprocess.CompletedProcess(command, 0, stdout=text, stderr='private stderr')
        with mock.patch.object(diagnostics.subprocess, 'run', side_effect=run):
            report = diagnostics.collect('adb', 'private-device')
        self.assertTrue(all(command[:4] == ['adb', '-s', 'private-device', 'shell'] for command in commands))
        self.assertTrue(all('root' not in command and 'su' not in command for command in commands))
        self.assertEqual(report['installed_version'], {'versionName': '0.5', 'versionCode': '5'})
        self.assertEqual(report['device']['manufacturer'], '制造商')
        self.assertNotIn('private', json.dumps(report))

    def test_probe_failures_remain_visible_without_command_details(self):
        with mock.patch.object(diagnostics.subprocess, 'run', side_effect=FileNotFoundError('private-path')):
            report = diagnostics.collect('missing-adb')
        self.assertEqual(report['operations']['package'], {'error_type': 'FileNotFoundError'})
        self.assertEqual(report['installed_version'], {})
        self.assertNotIn('private-path', json.dumps(report))


if __name__ == '__main__':
    unittest.main()
