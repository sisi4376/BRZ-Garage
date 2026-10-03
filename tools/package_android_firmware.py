#!/usr/bin/env python3
"""Package only the safe OTA application image into the Android app assets."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "build" / "obd_brz_gauge.bin"
DESTINATION = ROOT / "android_app" / "app" / "src" / "main" / "assets" / "firmware"


def firmware_version() -> str:
    match = re.search(r'set\(PROJECT_VER\s+"([^"]+)"\)',
                      (ROOT / "CMakeLists.txt").read_text(encoding="utf-8"))
    if not match:
        raise SystemExit("PROJECT_VER not found in CMakeLists.txt")
    return match.group(1)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-dir", type=Path, default=SOURCE.parent,
                        help="ESP-IDF build directory containing the finished application")
    args = parser.parse_args()
    source = args.build_dir.resolve() / SOURCE.name
    image = source.read_bytes()
    if not image or image[0] != 0xE9:
        raise SystemExit("build/obd_brz_gauge.bin is not an ESP32 application image")
    if len(image) > 0x300000:
        raise SystemExit("application image is larger than the 3 MB OTA slot")
    version = firmware_version()
    # ESP-IDF app descriptor follows the 24-byte image and 8-byte segment headers.
    if image[32:36] != bytes.fromhex("3254cdab"):
        raise SystemExit("ESP-IDF application descriptor is missing")
    image_version = image[48:80].split(b"\0", 1)[0].decode("ascii")
    if image_version != version:
        raise SystemExit(f"Stale firmware: image {image_version}, source {version}; rebuild first")
    digest = hashlib.sha256(image).hexdigest()
    manifest = {
        "format": 1,
        "device": {
            "board": "Waveshare ESP32-S3-Touch-AMOLED-1.75-B",
            "variant": "obd_brz_gauge_amoled175",
            "lcd": "CO5300",
            "screen": {"w": 466, "h": 466, "bpp": 16},
            "flash_mb": 16,
            "psram_mb": 8,
            "ota_slots": 2,
            "bootmedia_slots": 1,
            "bootmedia_format": 1,
        },
        "firmware": {
            "project": "obd_brz_gauge",
            "version": version,
            "build_tag": f"embedded-{version}",
            "idf": "v5.5.3",
        },
        "files": {
            "firmware": {
                "path": SOURCE.name,
                "size": len(image),
                "sha256": digest,
                "ota_only": True,
            }
        },
    }
    DESTINATION.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, DESTINATION / SOURCE.name)
    (DESTINATION / "latest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"embedded firmware {version}: {len(image)} bytes sha256={digest}")


if __name__ == "__main__":
    main()
