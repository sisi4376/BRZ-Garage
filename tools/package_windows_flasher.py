"""Build an offline Windows flasher ZIP from a verified ESP-IDF build directory."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ESPTOOL_VERSION = '4.12.0'
FLASHER_REVISION = 3
ESPTOOL_ZIP_SHA256 = '42fddc5e6a05716868ad77fb43acbf53be041f97abed87ff850df1dc88140889'
FILES = {
    'bootloader.bin': ('bootloader/bootloader.bin', 0x0, 0x8000),
    'partition-table.bin': ('partition_table/partition-table.bin', 0x8000, 0x1000),
    'ota_data_initial.bin': ('ota_data_initial.bin', 0xF000, 0x2000),
    'obd_brz_gauge.bin': ('obd_brz_gauge.bin', 0x20000, 0x300000),
    'bootmedia.bin': ('bootmedia.bin', 0x620000, 0x9E0000),
}
PARTITIONS = [
    (1, 2, 0x9000, 0x6000, 'nvs'),
    (1, 0, 0xF000, 0x2000, 'otadata'),
    (1, 1, 0x11000, 0x1000, 'phy_init'),
    (0, 16, 0x20000, 0x300000, 'ota_0'),
    (0, 17, 0x320000, 0x300000, 'ota_1'),
    (1, 0x82, 0x620000, 0x9E0000, 'bootmedia'),
]

def sha256(data):
    return hashlib.sha256(data).hexdigest()

def validate_partition_table(data):
    found = []
    checked = False
    for offset in range(0, len(data), 32):
        entry = data[offset:offset + 32]
        if len(entry) != 32:
            raise ValueError('Truncated partition table')
        if entry[:2] == b'\xff\xff':
            break
        if entry[:2] == b'\xeb\xeb':
            if entry[16:] != hashlib.md5(data[:offset]).digest():
                raise ValueError('Partition table checksum mismatch')
            checked = True
            break
        magic, kind, subtype, start, size, label, flags = struct.unpack('<HBBII16sI', entry)
        if magic != 0x50AA or flags != 0:
            raise ValueError('Unsupported partition entry')
        found.append((kind, subtype, start, size, label.split(b'\0')[0].decode()))
    if not checked or found != PARTITIONS:
        raise ValueError('Partition layout is not the supported 16 MB dual-OTA layout')

def validate_build(build, expected_version):
    config = json.loads((build / 'config/sdkconfig.json').read_text())
    if config.get('IDF_TARGET') != 'esp32s3' or not config.get('OBD_BOARD_AMOLED_175') or not config.get('ESPTOOLPY_FLASHSIZE_16MB'):
        raise ValueError('Build must target the 16 MB ESP32-S3 AMOLED 1.75 gauge')
    flash = json.loads((build / 'flasher_args.json').read_text())
    expected_map = {offset: source for source, offset, _ in FILES.values()}
    actual_map = {int(offset, 0): source for offset, source in flash['flash_files'].items()}
    if actual_map != expected_map or flash['flash_settings'] != {'flash_mode': 'dio', 'flash_size': '16MB', 'flash_freq': '80m'}:
        raise ValueError('Build flash offsets or settings do not match supported package')
    images = {}
    for name, (source, offset, limit) in FILES.items():
        data = (build / source).read_bytes()
        if not 0 < len(data) <= limit:
            raise ValueError(f'Invalid image size: {name}')
        images[name] = data
    app = images['obd_brz_gauge.bin']
    for name in ('obd_brz_gauge.bin', 'bootloader.bin'):
        image = images[name]
        if len(image) < 176 or image[0] != 0xE9 or struct.unpack_from('<H', image, 12)[0] != 9:
            raise ValueError(f'Not an ESP32-S3 image: {name}')
    if app[32:36] != bytes.fromhex('3254cdab'):
        raise ValueError('Missing application descriptor')
    version = app[48:80].split(b'\0')[0].decode('ascii')
    project = app[80:112].split(b'\0')[0].decode('ascii')
    if version != expected_version or project != 'obd_brz_gauge':
        raise ValueError(f'Stale or wrong firmware: {project} {version}')
    validate_partition_table(images['partition-table.bin'])
    if images['ota_data_initial.bin'] != b'\xff' * 0x2000:
        raise ValueError('OTA initializer must select the newly written ota_0 image')
    return images

def package(build, esptool_zip, output, version):
    if not version or any(c not in '0123456789.-abcdefghijklmnopqrstuvwxyz' for c in version):
        raise ValueError('Invalid version string')
    images = validate_build(build, version)
    if sha256(esptool_zip.read_bytes()) != ESPTOOL_ZIP_SHA256:
        raise ValueError('Official esptool archive SHA-256 mismatch')
    name = f'BRZ-Garage-Flasher-v{version}-r{FLASHER_REVISION}-Windows-x64'
    destination = output / name
    archive_path = output / f'{name}.zip'
    if destination.exists() or archive_path.exists():
        raise FileExistsError('Output exists; use a new output directory to preserve earlier packages')
    destination.mkdir(parents=True)
    (destination / 'firmware').mkdir()
    (destination / 'tools').mkdir()
    (destination / 'licenses').mkdir()
    with zipfile.ZipFile(esptool_zip) as archive:
        for source, target in [('esptool.exe', 'tools/esptool.exe'), ('LICENSE', 'licenses/esptool-LICENSE.txt'), ('README.md', 'licenses/esptool-README.md')]:
            (destination / target).write_bytes(archive.read(f'esptool-windows-amd64/{source}'))
    entries = []
    for file, data in images.items():
        (destination / 'firmware' / file).write_bytes(data)
        entries.append({'path': file, 'offset': FILES[file][1], 'size': len(data), 'sha256': sha256(data)})
    manifest = {'format': 1, 'version': version, 'flasher_revision': FLASHER_REVISION, 'chip': 'esp32s3',
                'variant': 'obd_brz_gauge_amoled175', 'flash_bytes': 16777216,
                'esptool_version': ESPTOOL_VERSION,
                'esptool_sha256': sha256((destination / 'tools/esptool.exe').read_bytes()),
                'esptool_archive_sha256': ESPTOOL_ZIP_SHA256, 'files': entries}
    (destination / 'package.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    for source in (ROOT / 'tools/flasher').iterdir():
        if source.is_file():
            data = source.read_bytes()
            if source.suffix == '.cmd':
                data = data.replace(b'\r\n', b'\n').replace(b'\n', b'\r\n')
            (destination / source.name).write_bytes(data)
    shutil.copy2(ROOT / 'LICENSE', destination / 'licenses/BRZ-Garage-LICENSE.txt')
    shutil.copy2(ROOT / 'NOTICE.md', destination / 'licenses/BRZ-Garage-NOTICE.md')
    with zipfile.ZipFile(archive_path, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for file in sorted(destination.rglob('*')):
            if file.is_file():
                archive.write(file, file.relative_to(output))
    digest = sha256(archive_path.read_bytes())
    (output / f'{name}.zip.sha256').write_text(f'{digest}  {archive_path.name}\n', encoding='ascii')
    print(f'Created: {archive_path}\nSHA256: {digest}\nNo device was accessed.')
    return destination, archive_path

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-dir', required=True, type=Path)
    parser.add_argument('--esptool-zip', required=True, type=Path)
    parser.add_argument('--output-dir', default=ROOT / '.publish/flasher', type=Path)
    parser.add_argument('--version', required=True)
    args = parser.parse_args()
    package(args.build_dir.resolve(), args.esptool_zip.resolve(), args.output_dir.resolve(), args.version)

if __name__ == '__main__':
    main()
