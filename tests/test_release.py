import hashlib
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('verify_release', ROOT / 'tools/verify_release.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseTest(unittest.TestCase):
    def test_checksum_pin_identity_and_version(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            apk = directory / 'MotoAppClone.apk'
            apk.write_bytes(b'fixture')
            checksum = hashlib.sha256(apk.read_bytes()).hexdigest()
            sidecar = directory / 'MotoAppClone.apk.sha256'
            valid = f'{checksum}  MotoAppClone.apk\n'
            metadata = "package: name='com.lixingchi.motoappclone' versionCode='5' versionName='0.5'\n"
            with mock.patch.object(release.build, 'verify_certificate') as signer, \
                    mock.patch.object(release.subprocess, 'run') as run:
                run.return_value = subprocess.CompletedProcess([], 0, stdout=metadata)
                sidecar.write_text(valid)
                release.verify(directory, '5-0.5', 'ab' * 32, Path('tools'))
                signer.assert_called_once()
                for tag, pin in (('../other', 'ab' * 32), ('5-0.5', '')):
                    with self.assertRaises(ValueError):
                        release.verify(directory, tag, pin, Path('tools'))
                for value in (valid.replace('MotoAppClone.apk', '../private'), valid + valid, '00' * 32 + '  MotoAppClone.apk\n'):
                    sidecar.write_text(value)
                    with self.assertRaisesRegex(ValueError, 'checksum'):
                        release.verify(directory, '5-0.5', 'ab' * 32, Path('tools'))
                sidecar.write_text(valid)
                for bad in (metadata.replace('motoappclone', 'different'), metadata.replace("'5'", "'6'"),
                            metadata.replace('0.5', '0.6')):
                    run.return_value = subprocess.CompletedProcess([], 0, stdout=bad)
                    with self.assertRaisesRegex(ValueError, 'package/version'):
                        release.verify(directory, '5-0.5', 'ab' * 32, Path('tools'))
                signer.side_effect = SystemExit('Certificate mismatch')
                with self.assertRaises(SystemExit):
                    release.verify(directory, '5-0.5', 'ab' * 32, Path('tools'))


if __name__ == '__main__':
    unittest.main()
