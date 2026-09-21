"""Reset out of the USB bootloader and capture a bounded firmware boot log."""
import argparse
import time

import serial
from esptool.reset import HardReset

parser = argparse.ArgumentParser()
parser.add_argument('--port', required=True)
parser.add_argument('--seconds', type=int, default=18)
args = parser.parse_args()
with serial.Serial() as port:
    port.port = args.port
    port.baudrate = 115200
    port.timeout = 0.3
    port.dtr = False
    port.rts = False
    port.open()
    HardReset(port, uses_usb=True).reset()
    deadline = time.monotonic() + min(max(args.seconds, 1), 55)
    print('Monitoring firmware boot:', flush=True)
    while time.monotonic() < deadline:
        data = port.read(8192)
        if data:
            print(data.decode('utf-8', errors='replace'), end='', flush=True)
