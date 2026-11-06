import contextlib
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('motoappclone_build', ROOT / 'build.py')
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


class BuildTest(unittest.TestCase):
    def test_certificate_digest(self):
        self.assertEqual(build.certificate_digest('AA:' * 31 + 'AA'), 'aa' * 32)
        for value in ('', '00', 'g' * 64, '0' * 63):
            with self.subTest(value=value), self.assertRaises(ValueError):
                build.certificate_digest(value)

    def assert_refused(self, env, message, args=('--release',)):
        with mock.patch.dict(os.environ, env, clear=True), \
                mock.patch('sys.argv', ['build.py', *args]), \
                mock.patch.object(build.subprocess, 'run') as run, \
                contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as error:
                build.main()
            self.assertIn(message, str(error.exception))
            run.assert_not_called()

    def test_release_requires_credentials(self):
        self.assert_refused({}, 'Release signing requires')

    def test_release_refuses_missing_key_or_pin(self):
        with tempfile.TemporaryDirectory() as temporary:
            key = Path(temporary) / 'release.keystore'
            env = dict(SIGNING_KEYSTORE=str(key), SIGNING_STORE_PASSWORD='dummy',
                       SIGNING_KEY_PASSWORD='dummy', SIGNING_KEY_ALIAS='dummy')
            self.assert_refused(env, 'refusing to generate a replacement')
            self.assertFalse(key.exists())
            key.touch()
            self.assert_refused(env, 'SIGNING_CERT_SHA256')
            env['SIGNING_CERT_SHA256'] = 'not a fingerprint'
            self.assert_refused(env, 'SIGNING_CERT_SHA256')

    def test_unknown_option_is_not_silently_unsigned(self):
        self.assert_refused({}, '2', args=('--relase',))

    def test_certificate_verification_requires_one_matching_signer(self):
        good = 'Number of signers: 1\nSigner #1 certificate SHA-256 digest: ' + 'ab' * 32 + '\n'
        for text, valid in ((good, True), (good.replace('Signer #1', 'V3.0 Signer:'), True),
                            ('', False), (good.replace('ab', 'cd'), False),
                            (good.replace('signers: 1', 'signers: 2'), False),
                            (good.replace('Number of signers: 1\n', ''), False)):
            result = subprocess.CompletedProcess([], 0, stdout=text, stderr='')
            with self.subTest(text=text), mock.patch.object(build.subprocess, 'run', return_value=result):
                if valid:
                    build.verify_certificate('apksigner', 'signed.apk', {}, 'ab' * 32)
                else:
                    with self.assertRaisesRegex(SystemExit, 'APK certificate does not match'):
                        build.verify_certificate('apksigner', 'signed.apk', {}, 'ab' * 32)

    def test_invalid_apk_signature_fails_even_without_a_pin(self):
        with mock.patch.object(build.subprocess, 'run',
                               side_effect=subprocess.CalledProcessError(1, 'apksigner')):
            with self.assertRaises(subprocess.CalledProcessError):
                build.verify_certificate('apksigner', 'signed.apk', {}, None)

    def test_manifest_identity_and_plain_version(self):
        manifest = ET.parse(ROOT / 'AndroidManifest.xml').getroot()
        android = '{http://schemas.android.com/apk/res/android}'
        self.assertEqual(manifest.attrib['package'], 'com.lixingchi.motoappclone')
        self.assertGreater(int(manifest.attrib[android + 'versionCode']), 0)
        self.assertRegex(manifest.attrib[android + 'versionName'], r'^\d+(\.\d+)+$')
        self.assertEqual(manifest.find('uses-sdk').attrib[android + 'minSdkVersion'], '36')


if __name__ == '__main__':
    unittest.main()
