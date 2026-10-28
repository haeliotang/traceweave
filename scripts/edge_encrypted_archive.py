#!/usr/bin/env python3
"""Stream encryption primitives for a local Edge archive, without handling keys or ADB."""
import hashlib
import io
import os
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

MAGIC = b'TFEDGE01'
NONCE_BYTES = 12
TAG_BYTES = 16
CHUNK = 1024 * 1024


def encrypt_stream(source, destination, key):
    if len(key) != 32:
        raise ValueError('Expected a 256-bit archive key')
    nonce = os.urandom(NONCE_BYTES)
    header = MAGIC + nonce
    encryptor = Cipher(algorithms.AES(key), modes.GCM(nonce)).encryptor()
    encryptor.authenticate_additional_data(header)
    destination.write(header)
    digest = hashlib.sha256()
    length = 0
    while chunk := source.read(CHUNK):
        length += len(chunk)
        digest.update(chunk)
        destination.write(encryptor.update(chunk))
    destination.write(encryptor.finalize())
    destination.write(encryptor.tag)
    return length, digest.hexdigest()


def verify_archive(source, key):
    """Authenticate and hash plaintext in memory; never output plaintext or write it to disk."""
    if len(key) != 32:
        raise ValueError('Expected a 256-bit archive key')
    source.seek(0, os.SEEK_END)
    size = source.tell()
    if size < len(MAGIC) + NONCE_BYTES + TAG_BYTES:
        raise ValueError('Archive is incomplete')
    source.seek(0)
    header = source.read(len(MAGIC) + NONCE_BYTES)
    if header[:len(MAGIC)] != MAGIC:
        raise ValueError('Unsupported archive format')
    source.seek(-TAG_BYTES, os.SEEK_END)
    tag = source.read(TAG_BYTES)
    source.seek(len(header))
    decryptor = Cipher(algorithms.AES(key), modes.GCM(header[len(MAGIC):], tag)).decryptor()
    decryptor.authenticate_additional_data(header)
    remaining = size - len(header) - TAG_BYTES
    digest = hashlib.sha256()
    length = 0
    while remaining:
        chunk = source.read(min(CHUNK, remaining))
        if not chunk:
            raise ValueError('Archive is incomplete')
        remaining -= len(chunk)
        plaintext = decryptor.update(chunk)
        length += len(plaintext)
        digest.update(plaintext)
    plaintext = decryptor.finalize()
    length += len(plaintext)
    digest.update(plaintext)
    return length, digest.hexdigest()


class VerifiedPlaintext(io.RawIOBase):
    """Authenticate once before yielding bytes; authenticate again when fully consumed."""
    def __init__(self, source, key):
        super().__init__()
        self.expected = verify_archive(source, key)
        source.seek(0, os.SEEK_END)
        self.remaining = source.tell() - len(MAGIC) - NONCE_BYTES - TAG_BYTES
        source.seek(0)
        header = source.read(len(MAGIC) + NONCE_BYTES)
        source.seek(-TAG_BYTES, os.SEEK_END)
        tag = source.read(TAG_BYTES)
        source.seek(len(header))
        self.decryptor = Cipher(algorithms.AES(key), modes.GCM(header[len(MAGIC):], tag)).decryptor()
        self.decryptor.authenticate_additional_data(header)
        self.source = source
        self.digest = hashlib.sha256()
        self.count = 0
        self.finished = False

    def readable(self):
        return True

    def readinto(self, buffer):
        if not buffer:
            return 0
        if not self.remaining:
            if not self.finished:
                tail = self.decryptor.finalize()
                if tail or (self.count, self.digest.hexdigest()) != self.expected:
                    raise ValueError('Archive changed during recovery')
                self.finished = True
            return 0
        chunk = self.source.read(min(len(buffer), self.remaining))
        if not chunk:
            raise ValueError('Archive is incomplete')
        self.remaining -= len(chunk)
        plain = self.decryptor.update(chunk)
        buffer[:len(plain)] = plain
        self.digest.update(plain)
        self.count += len(plain)
        return len(plain)
