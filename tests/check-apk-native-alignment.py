"""Read-only validation of every shipped native library for the supported 64-bit ABIs."""
import hashlib
import json
import struct
import sys
import zipfile
from pathlib import Path


def inspect(apk_path):
    expected = {"arm64-v8a": 183, "x86_64": 62}
    results = []
    with apk_path.open("rb") as raw, zipfile.ZipFile(apk_path) as archive:
        libraries = [item for item in archive.infolist() if item.filename.startswith("lib/") and item.filename.endswith(".so")]
        if not libraries:
            raise ValueError("APK has no native libraries")
        observed = set()
        for item in libraries:
            parts = item.filename.split("/")
            if len(parts) != 3 or parts[1] not in expected:
                raise ValueError("Unexpected shipped ABI/library path: " + item.filename)
            abi = parts[1]
            observed.add(abi)
            if item.compress_type != zipfile.ZIP_STORED:
                raise ValueError("Native library must be uncompressed: " + item.filename)
            raw.seek(item.header_offset)
            header = raw.read(30)
            if header[:4] != b"PK\x03\x04":
                raise ValueError("Malformed ZIP local header")
            name_length, extra_length = struct.unpack_from("<HH", header, 26)
            data_offset = item.header_offset + 30 + name_length + extra_length
            if data_offset % 16384:
                raise ValueError("Native ZIP data is not 16 KB aligned: " + item.filename)
            elf = archive.read(item)
            if elf[:6] != b"\x7fELF\x02\x01" or len(elf) < 64:
                raise ValueError("Expected little-endian ELF64: " + item.filename)
            if struct.unpack_from("<H", elf, 18)[0] != expected[abi]:
                raise ValueError("ELF machine does not match ABI: " + item.filename)
            ph_offset = struct.unpack_from("<Q", elf, 32)[0]
            ph_size, ph_count = struct.unpack_from("<HH", elf, 54)
            if ph_size < 56 or ph_count < 1 or ph_offset + ph_size * ph_count > len(elf):
                raise ValueError("Malformed ELF program-header table")
            loads = []
            for index in range(ph_count):
                start = ph_offset + index * ph_size
                if struct.unpack_from("<I", elf, start)[0] != 1:
                    continue
                offset, address = struct.unpack_from("<QQ", elf, start + 8)
                alignment = struct.unpack_from("<Q", elf, start + 48)[0]
                if alignment < 16384 or alignment & (alignment - 1) or offset % 16384 != address % 16384:
                    raise ValueError("ELF LOAD segment is not compatible with 16 KB pages: " + item.filename)
                loads.append(alignment)
            if not loads:
                raise ValueError("ELF has no LOAD segments")
            results.append({"path": item.filename, "abi": abi, "sha256": hashlib.sha256(elf).hexdigest(),
                            "zip_data_offset": data_offset, "zip_alignment": 16384, "load_alignments": loads})
        if observed != set(expected):
            raise ValueError("Missing required ABI")
    return {"apk": str(apk_path), "native_library_count": len(results), "all_native_16kb_aligned": True, "libraries": results}


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python tests/check-apk-native-alignment.py app.apk")
    print(json.dumps(inspect(Path(sys.argv[1]).resolve()), indent=2))
