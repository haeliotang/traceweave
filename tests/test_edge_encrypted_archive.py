import io
import sys
import tarfile
import unittest
from pathlib import Path
from cryptography.exceptions import InvalidTag

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from edge_encrypted_archive import CHUNK, VerifiedPlaintext, encrypt_stream, verify_archive, inspect_recovery_archive


class EdgeEncryptedArchiveTests(unittest.TestCase):
    def test_stream_roundtrip_across_chunks(self):
        data = b'synthetic-only' * (CHUNK // 10)
        encrypted = io.BytesIO()
        expected = encrypt_stream(io.BytesIO(data), encrypted, b'a' * 32)
        self.assertNotIn(b'synthetic-only', encrypted.getvalue())
        self.assertEqual(verify_archive(encrypted, b'a' * 32), expected)
        reader = VerifiedPlaintext(encrypted, b'a' * 32)
        self.assertEqual(reader.read(), data)
        self.assertTrue(reader.finished)

    def test_tampering_wrong_key_and_truncation_are_rejected(self):
        archive = io.BytesIO()
        encrypt_stream(io.BytesIO(b'synthetic'), archive, b'a' * 32)
        data = archive.getvalue()
        altered = bytearray(data); altered[25] ^= 1
        for invalid, key in [(bytes(altered), b'a' * 32), (data, b'b' * 32), (data[:-5], b'a' * 32)]:
            with self.assertRaises((InvalidTag, ValueError)):
                verify_archive(io.BytesIO(invalid), key)
        with self.assertRaises(ValueError):
            verify_archive(io.BytesIO(data[:5]), b'a' * 32)
        with self.assertRaises(InvalidTag):
            VerifiedPlaintext(io.BytesIO(data), b'b' * 32)

    def test_change_after_initial_authentication_is_detected_on_completion(self):
        archive = io.BytesIO()
        encrypt_stream(io.BytesIO(b'synthetic'), archive, b'a' * 32)
        reader = VerifiedPlaintext(archive, b'a' * 32)
        archive.seek(21)
        archive.write(b'X')
        archive.seek(len(b'TFEDGE01') + 12)
        with self.assertRaises(InvalidTag):
            reader.read()

    def test_streaming_archive_reader_authenticates_after_tar_iteration(self):
        raw = io.BytesIO()
        with tarfile.open(fileobj=raw, mode='w') as tar:
            fixture = b'synthetic database bytes'
            info = tarfile.TarInfo('databases/edge.sqlite')
            info.size = len(fixture)
            tar.addfile(info, io.BytesIO(fixture))
        encrypted = io.BytesIO()
        encrypt_stream(io.BytesIO(raw.getvalue()), encrypted, b'a' * 32)
        plaintext = io.BufferedReader(VerifiedPlaintext(encrypted, b'a' * 32))
        with tarfile.open(fileobj=plaintext, mode='r|') as tar:
            member = next(iter(tar))
            self.assertEqual(tar.extractfile(member).read(), fixture)
        while plaintext.read(CHUNK):
            pass
        self.assertTrue(plaintext.raw.finished)

    def test_recovery_manifest_rejects_unsafe_or_ambiguous_entries(self):
        def archive(entries):
            raw = io.BytesIO()
            with tarfile.open(fileobj=raw, mode='w') as tar:
                for name, kind in entries:
                    info = tarfile.TarInfo(name)
                    if kind == 'link':
                        info.type = tarfile.SYMTYPE; info.linkname = '../outside'
                        tar.addfile(info)
                    else:
                        info.size = 1; tar.addfile(info, io.BytesIO(b'x'))
            encrypted = io.BytesIO()
            encrypt_stream(io.BytesIO(raw.getvalue()), encrypted, b'a' * 32)
            return encrypted
        valid = [('databases/edge.sqlite', 'file'), ('files/model/data', 'file')]
        manifest = inspect_recovery_archive(archive(valid), b'a' * 32)
        self.assertEqual(set(manifest), {item[0] for item in valid})
        for invalid in [valid + [('files/../outside', 'file')],
                        valid + [('files/model/data', 'file')],
                        valid + [('files/alias', 'link')],
                        [('databases/edge.sqlite', 'file'), ('files/model', 'file'), ('files/model/data', 'file')],
                        [('other/secret', 'file')], [('.', 'file')], [('files/data', 'file')]]:
            with self.assertRaises(ValueError):
                inspect_recovery_archive(archive(invalid), b'a' * 32)


if __name__ == '__main__':
    unittest.main()
