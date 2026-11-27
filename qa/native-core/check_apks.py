"""Check native ABI contents and ELF PT_LOAD alignment in the five local release APKs."""
import argparse
from pathlib import Path
import struct
import zipfile

ABIS = {"arm64-v8a", "armeabi-v7a", "x86_64", "x86"}


def check(path, expected):
    abis = set()
    count = 0
    with zipfile.ZipFile(path) as apk, path.open("rb") as archive:
        for entry in apk.infolist():
            if not entry.filename.startswith("lib/") or not entry.filename.endswith(".so"):
                continue
            abi = entry.filename.split("/")[1]
            data = apk.read(entry)
            assert data[:4] == b"\x7fELF" and data[5] == 1, entry.filename
            is64 = data[4] == 2
            assert is64 or data[4] == 1
            phoff = struct.unpack_from("<Q" if is64 else "<I", data, 32 if is64 else 28)[0]
            stride, total = struct.unpack_from("<HH", data, 54 if is64 else 42)
            segments = []
            for index in range(total):
                start = phoff + index * stride
                if struct.unpack_from("<I", data, start)[0] == 1:
                    align = struct.unpack_from("<Q" if is64 else "<I", data, start + (48 if is64 else 28))[0]
                    segments.append(align)
            assert segments and all(value >= 16384 for value in segments), (entry.filename, segments)
            if entry.compress_type == zipfile.ZIP_STORED:
                archive.seek(entry.header_offset + 26)
                name, extra = struct.unpack("<HH", archive.read(4))
                assert (entry.header_offset + 30 + name + extra) % 16384 == 0, entry.filename
            abis.add(abi)
            count += 1
            if entry.filename.endswith("/librprobe_core.so"):
                print(f"  {abi} Rust core {len(data):,} bytes")
        assert abis == expected, (path, abis)
        for abi in expected:
            assert f"lib/{abi}/librprobe_core.so" in apk.namelist()
    print(f"{path.name}: {count} ELF files, 16 KiB alignment passed")


if __name__ == "__main__":
    if not __debug__:
        raise SystemExit("Python assertions must be enabled")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("version")
    args = parser.parse_args()
    for abi in ["", *sorted(ABIS)]:
        suffix = "-" + abi if abi else ""
        check(args.directory / f"RProbeDesk-{args.version}-android{suffix}.apk", {abi} if abi else ABIS)
