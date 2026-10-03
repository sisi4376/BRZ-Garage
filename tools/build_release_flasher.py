"""Package freshly built release firmware with the maintained Windows flasher."""
import argparse
from pathlib import Path
import re
import tempfile
import urllib.request

from package_windows_flasher import ESPTOOL_VERSION, ESPTOOL_ZIP_SHA256, package, sha256


def source_version(source):
    match = re.search(r'set\(PROJECT_VER\s+"([^"]+)"\)',
                      (source / 'CMakeLists.txt').read_text(encoding='utf-8'))
    if not match:
        raise ValueError('Firmware source has no PROJECT_VER')
    return match.group(1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--firmware-source', required=True, type=Path)
    parser.add_argument('--build-dir', required=True, type=Path)
    parser.add_argument('--output-dir', required=True, type=Path)
    parser.add_argument('--esptool-zip', type=Path,
                        help='Optional local official archive; otherwise download the pinned release')
    args = parser.parse_args()
    version = source_version(args.firmware_source)
    with tempfile.TemporaryDirectory(prefix='brz-esptool-') as temporary:
        archive = args.esptool_zip
        if archive is None:
            url = (f'https://github.com/espressif/esptool/releases/download/v{ESPTOOL_VERSION}/'
                   f'esptool-v{ESPTOOL_VERSION}-windows-amd64.zip')
            with urllib.request.urlopen(url, timeout=120) as response:
                data = response.read()
            if sha256(data) != ESPTOOL_ZIP_SHA256:
                raise ValueError('Downloaded official esptool archive checksum mismatch')
            archive = Path(temporary) / 'esptool.zip'
            archive.write_bytes(data)
        package(args.build_dir.resolve(), archive.resolve(), args.output_dir.resolve(), version)


if __name__ == '__main__':
    main()
