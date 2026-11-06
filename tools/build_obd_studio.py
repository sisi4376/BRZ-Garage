"""Build the standalone Windows executable using the recorder virtual environment."""
import hashlib
import argparse
import os
from pathlib import Path
import struct
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--name', default='BRZ-OBD-Studio')
    args = parser.parse_args()
    if not args.name or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._' for c in args.name):
        parser.error('Use a simple executable base name.')
    from PySide6.QtWidgets import QApplication
    from PySide6.QtGui import QImage, QPainter, QColor, QPen
    from PySide6.QtCore import Qt, QBuffer, QIODevice, QPointF
    app = QApplication.instance() or QApplication([])
    image = QImage(256, 256, QImage.Format_ARGB32)
    image.fill(QColor('#0c1422'))
    painter = QPainter(image)
    painter.setRenderHint(QPainter.Antialiasing)
    painter.setPen(QPen(QColor('#50dec2'), 18, Qt.SolidLine, Qt.RoundCap))
    painter.drawArc(44, 44, 168, 168, -35 * 16, 250 * 16)
    painter.setPen(QPen(QColor('#e7eef8'), 12, Qt.SolidLine, Qt.RoundCap))
    painter.drawLine(QPointF(128, 132), QPointF(177, 91))
    painter.setBrush(QColor('#50dec2'))
    painter.drawEllipse(QPointF(128, 132), 11, 11)
    painter.end()
    buffer = QBuffer()
    buffer.open(QIODevice.WriteOnly)
    image.save(buffer, 'PNG')
    png = bytes(buffer.data())
    work = ROOT / '.tmp' / 'obd-studio-build'
    work.mkdir(parents=True, exist_ok=True)
    icon = work / 'studio.ico'
    icon.write_bytes(struct.pack('<HHH', 0, 1, 1) + struct.pack('<BBBBHHII', 0, 0, 0, 0, 1, 32, len(png), 22) + png)
    # Build tools may inherit Poppler/libheif DLL directories from the host PATH.
    # Those legacy UCRT/API-set shims break Qt when bundled into a frozen app.
    env = os.environ.copy()
    windows = Path(env.get('SystemRoot', r'C:\Windows'))
    env['PATH'] = os.pathsep.join([str(Path(sys.executable).parent), str(windows / 'System32'), str(windows)])
    subprocess.run([sys.executable, '-m', 'PyInstaller', '--noconfirm', '--clean', '--onefile',
        '--windowed', '--name', args.name, '--icon', str(icon),
        '--distpath', str(ROOT), '--workpath', str(work / 'work'), '--specpath', str(work),
        '--collect-all', 'winrt', '--collect-submodules', 'bleak.backends.winrt',
        '--add-data', f'{icon};.',
        str(ROOT / 'tools' / 'obd_recorder_gui.py')], cwd=ROOT, env=env, check=True)
    output = ROOT / (args.name + '.exe')
    output.with_suffix('.exe.sha256').write_text(hashlib.sha256(output.read_bytes()).hexdigest() + '  ' + output.name + '\n')
    print(output)


if __name__ == '__main__':
    main()
