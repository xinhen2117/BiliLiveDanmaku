#!/usr/bin/env python3
"""Read-only correspondence check for OkHttp 4.12.0's Public Suffix List.

Usage: python verify_public_suffix_source.py SOURCE.dat ARTIFACT.jar|apk|gz
No network access, archive changes, or runtime regeneration is performed.
"""
import argparse
import gzip
import hashlib
from pathlib import Path
import struct
import zipfile

SOURCE_SHA256 = "e8b273972eb5a70e888bd3e7d7c5b9b04e600a59d69def9136a74d70ae6fcdd3"
GZIP_SHA256 = "9af43e9995ec749e7a30eec4e5a87770d7e2027847084a07cca82060eff0c791"
PAYLOAD_SHA256 = "5beda335cfa1a9a9f84c4d17f2267ad7304c3c23d14d41256032d78db274431f"
RESOURCE = "okhttp3/internal/publicsuffix/publicsuffixes.gz"
ASSET = "assets/open_source_licenses/OkHttp-4.12.0-public_suffix_list.dat"


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def check(source_path, artifact):
    source = source_path.read_bytes()
    require(hashlib.sha256(source).hexdigest() == SOURCE_SHA256, "Source snapshot hash differs")
    if zipfile.is_zipfile(artifact):
        with zipfile.ZipFile(artifact) as archive:
            require(archive.namelist().count(RESOURCE) == 1, "Missing or ambiguous runtime resource")
            compressed = archive.read(RESOURCE)
            if artifact.suffix.lower() == ".apk":
                require(archive.namelist().count(ASSET) == 1, "Missing or ambiguous packaged source")
                require(archive.read(ASSET) == source, "APK source bytes differ")
    else:
        compressed = artifact.read_bytes()
    require(hashlib.sha256(compressed).hexdigest() == GZIP_SHA256, "Runtime gzip hash differs")
    rules, exceptions = [], []
    # Okio readUtf8LineStrict removes LF and an immediately preceding CR.
    text = source.decode("utf-8")
    require(text.endswith("\n"), "Expected newline-terminated source")
    for line in text.split("\n")[:-1]:
        line = line.removesuffix("\r")
        if not line.strip() or line.startswith("//"):
            continue
        if line.startswith("!"):
            exceptions.append(line[1:].encode("utf-8"))
        else:
            rules.append(line.encode("utf-8"))
    require(len(set(rules)) == len(rules), "Duplicate rule")
    require(len(set(exceptions)) == len(exceptions), "Duplicate exception")
    ordinary = b"".join(x + b"\n" for x in sorted(rules))
    exception = b"".join(x + b"\n" for x in sorted(exceptions))
    expected = struct.pack(">I", len(ordinary)) + ordinary + struct.pack(">I", len(exception)) + exception
    actual = gzip.decompress(compressed)
    require(expected == actual, "Source does not reproduce complete runtime payload")
    require(hashlib.sha256(actual).hexdigest() == PAYLOAD_SHA256, "Unexpected decompressed payload hash")
    print(f"Verified {len(rules)} rules + {len(exceptions)} exceptions; {len(actual)} payload bytes match exactly.")
    print(f"Verified source SHA-256: {SOURCE_SHA256}")
    print(f"Verified runtime SHA-256: {GZIP_SHA256}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("artifact", type=Path)
    args = parser.parse_args()
    check(args.source, args.artifact)
