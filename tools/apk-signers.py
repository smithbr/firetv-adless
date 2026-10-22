#!/usr/bin/env python3
"""Prints the SHA-256 of each signer's certificate in an APK's v2, v3 and
v3.1 signature blocks, one line per signer per block, as apksigner verify
--print-certs reports them. tools/firetv checks a downloaded launcher.apk
with it before installing, since a computer without the Android SDK has no
apksigner; the Release workflow checks that the two agree.

It reads the certificates and checks no signature: Android checks those as
it installs, against these same certificates, so an APK whose blocks name
the release key but were signed with another does not install. The blocks
are found as Android finds them: the end of central directory record
nearest the end of the file, the signing block just before the central
directory. Exits 1, saying why, when the APK has none of these blocks or is
malformed."""

import hashlib
import struct
import sys

EOCD = b"PK\x05\x06"
ZIP64_LOCATOR = b"PK\x06\x07"
MAGIC = b"APK Sig Block 42"
# Block IDs: APK Signature Scheme v2, v3 and v3.1.
SCHEMES = (0x7109871A, 0xF05368C0, 0x1B93AD61)


class Malformed(Exception):
    pass


class Reader:
    def __init__(self, data):
        self.data = data
        self.at = 0

    def uint32(self):
        if self.at + 4 > len(self.data):
            raise Malformed("truncated length")
        (value,) = struct.unpack_from("<I", self.data, self.at)
        self.at += 4
        return value

    # A field prefixed with its uint32 length.
    def field(self):
        size = self.uint32()
        if self.at + size > len(self.data):
            raise Malformed("field runs past its block")
        value = self.data[self.at:self.at + size]
        self.at += size
        return Reader(value)

    def done(self):
        return self.at == len(self.data)


def end_of_central_directory(apk):
    # As Android's ZipUtils: the first record, searching back from the end,
    # whose comment length reaches exactly to the end of the file.
    for comment in range(min(len(apk) - 22, 0xFFFF) + 1):
        at = len(apk) - 22 - comment
        if apk[at:at + 4] == EOCD and struct.unpack_from("<H", apk, at + 20)[0] == comment:
            return at
    raise Malformed("not a ZIP file")


def signing_blocks(apk):
    eocd = end_of_central_directory(apk)
    if eocd >= 20 and apk[eocd - 20:eocd - 16] == ZIP64_LOCATOR:
        raise Malformed("ZIP64, which APK signing does not support")
    cd_size, cd_offset = struct.unpack_from("<II", apk, eocd + 12)
    if cd_offset + cd_size != eocd:
        raise Malformed("central directory is not followed by its end record")
    if cd_offset < 32 or apk[cd_offset - 16:cd_offset] != MAGIC:
        raise Malformed("no APK signing block")
    (size,) = struct.unpack_from("<Q", apk, cd_offset - 24)
    start = cd_offset - size - 8
    if start < 0 or struct.unpack_from("<Q", apk, start)[0] != size:
        raise Malformed("APK signing block sizes disagree")
    at, end = start + 8, cd_offset - 24
    while at < end:
        if at + 12 > end:
            raise Malformed("truncated signing block entry")
        length, block_id = struct.unpack_from("<QI", apk, at)
        if length < 4 or at + 8 + length > end:
            raise Malformed("signing block entry runs past the block")
        yield block_id, apk[at + 12:at + 8 + length]
        at += 8 + length


def signer_digests(apk):
    digests = []
    for block_id, value in signing_blocks(apk):
        if block_id not in SCHEMES:
            continue
        signers = Reader(value).field()
        while not signers.done():
            # signer: signed data, ...; signed data: digests, certificates, ...
            signed = signers.field().field()
            signed.field()
            certificates = signed.field()
            if certificates.done():
                raise Malformed("a signer has no certificate")
            # The first certificate is the signer's; any others are its chain.
            digests.append(hashlib.sha256(certificates.field().data).hexdigest())
    if not digests:
        raise Malformed("no v2 or v3 signature")
    return digests


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: apk-signers.py APK")
    try:
        with open(sys.argv[1], "rb") as f:
            digests = signer_digests(f.read())
    except (OSError, Malformed, struct.error) as e:
        sys.exit(f"{sys.argv[1]}: {e}")
    print("\n".join(digests))


if __name__ == "__main__":
    main()
