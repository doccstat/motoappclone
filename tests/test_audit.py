import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TEST_CACHE = Path.home() / '.cache/motoappclone-tests'
TEST_CACHE.mkdir(parents=True, exist_ok=True)
spec = importlib.util.spec_from_file_location('audit', ROOT / 'tools/audit_firmware.py')
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def minimal_dex():
    """Two method references, but only one declared method; no proprietary bytecode."""
    data = bytearray(112)
    data[:8] = b'dex\n035\0'
    strings = [b'Ltest/Example;', b'V', b'foo', b'referenceOnly', b'field']
    string_ids = len(data)
    data.extend(bytes(4 * len(strings)))
    offsets = []
    for value in strings:
        offsets.append(len(data))
        data.extend(bytes([len(value)]) + value + b'\0')
    for i, offset in enumerate(offsets):
        struct.pack_into('<I', data, string_ids + 4 * i, offset)
    type_ids = len(data)
    data.extend(struct.pack('<II', 0, 1))
    proto_ids = len(data)
    data.extend(struct.pack('<III', 1, 1, 0))
    field_ids = len(data)
    data.extend(struct.pack('<HHI', 0, 1, 4))
    method_ids = len(data)
    data.extend(struct.pack('<HHIHHI', 0, 0, 2, 0, 0, 3))
    class_defs = len(data)
    data.extend(bytes(32))
    class_data = len(data)
    data.extend(bytes([1, 0, 1, 0, 0, 1, 0, 1, 0]))
    struct.pack_into('<IIIIIIII', data, class_defs, 0, 1, 0xffffffff, 0, 0, 0, class_data, 0)
    for pos, size, offset in [(56, 5, string_ids), (64, 2, type_ids), (72, 1, proto_ids),
                              (80, 1, field_ids), (88, 2, method_ids), (96, 1, class_defs)]:
        struct.pack_into('<II', data, pos, size, offset)
    return bytes(data)


class AuditTest(unittest.TestCase):
    def test_uleb_boundaries(self):
        for blob, value in [(b'\0', 0), (b'\x7f', 127), (b'\x80\x01', 128),
                            (b'\xff\xff\xff\xff\x0f', 0xffffffff)]:
            self.assertEqual(audit.uleb(blob, 0), (value, len(blob)))
        with self.assertRaises(ValueError):
            audit.uleb(b'\x80' * 5, 0)
        with self.assertRaises(IndexError):
            audit.uleb(b'\x80', 0)

    def test_declarations_not_merely_references(self):
        classes, fields, methods = audit.dex_members(minimal_dex())
        self.assertEqual(classes, {'Ltest/Example;'})
        self.assertEqual(fields, {('Ltest/Example;', 'field', 'V')})
        self.assertEqual(methods, {('Ltest/Example;', 'foo', '()V')})
        with self.assertRaises(ValueError):
            audit.dex_members(b'not dex')

    def test_missing_or_invalid_artifacts_fail_closed(self):
        with tempfile.TemporaryDirectory(dir=TEST_CACHE) as temporary:
            with self.assertRaisesRegex(ValueError, 'No APK/JAR'):
                audit.audit(Path(temporary))
            path = Path(temporary) / 'framework.jar'
            path.write_bytes(b'not a jar')
            with self.assertRaises(zipfile.BadZipFile):
                audit.audit(Path(temporary))

    def test_arbitrary_artifact_names_are_checked_by_members_not_hash(self):
        with tempfile.TemporaryDirectory(dir=TEST_CACHE) as temporary:
            path = Path(temporary) / 'different-firmware.apk'
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('classes.dex', minimal_dex())
            with self.assertRaisesRegex(ValueError, 'Missing methods'):
                audit.audit(Path(temporary))


if __name__ == '__main__':
    unittest.main()
