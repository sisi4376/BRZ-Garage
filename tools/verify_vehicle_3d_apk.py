"""Check that an APK contains the exact offline renderer, models, and their licenses."""
from pathlib import Path
import hashlib
import json
import struct
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "preview/vehicle-3d"


def verify(apk):
    files = ["app.html", "real-models.js", "vehicle-camera.mjs", "vendor/three.module.js", "vendor/GLTFLoader.js",
             "vendor/BufferGeometryUtils.js", "vendor/THREE-LICENSE.txt"]
    for model in ("zd8", "zc6"):
        files += [f"models/{model}/{name}" for name in ("vehicle.glb", "source.json", "original/license.txt")]
    with zipfile.ZipFile(apk) as archive:
        prefix = "assets/vehicle-3d/"
        actual = {name[len(prefix):] for name in archive.namelist() if name.startswith(prefix) and not name.endswith("/")}
        assert actual == set(files), f"Unexpected or missing runtime assets: {actual ^ set(files)}"
        for name in files:
            data = archive.read(prefix + name)
            assert hashlib.sha256(data).digest() == hashlib.sha256((SOURCE / name).read_bytes()).digest(), name
            if name.endswith(".glb"):
                magic, version, length = struct.unpack_from("<4sII", data)
                assert magic == b"glTF" and version == 2 and length == len(data), name
                json_length, json_type = struct.unpack_from("<II", data, 12)
                assert json_type == 0x4E4F534A
                gltf = json.loads(data[20:20 + json_length])
                assert all("uri" not in b for b in gltf["buffers"]), "External model buffer"
                assert all("uri" not in image for image in gltf.get("images", [])), "External model image"
    print(f"PASS: {len(files)} matching assets, both self-contained GLBs, model attribution and Three.js license")


if __name__ == "__main__":
    verify(Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "android_app/app/build/outputs/apk/debug/app-debug.apk")
