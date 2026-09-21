"""Capture timestamped fuel-diagnostic serial output during a real-car test."""

import argparse
import datetime as dt
import time
from pathlib import Path

import serial


parser = argparse.ArgumentParser()
parser.add_argument("--port", required=True)
parser.add_argument("--output", required=True)
parser.add_argument("--seconds", type=int, default=1800)
args = parser.parse_args()

output = Path(args.output)
output.parent.mkdir(parents=True, exist_ok=True)

with serial.Serial() as port, output.open("a", encoding="utf-8", buffering=1) as log:
    port.port = args.port
    port.baudrate = 115200
    port.timeout = 0.25
    port.dtr = False
    port.rts = False
    port.open()

    deadline = time.monotonic() + max(1, args.seconds)
    pending = b""
    marker = f"# capture-start {dt.datetime.now().astimezone().isoformat()} port={args.port}\n"
    print(marker, end="", flush=True)
    log.write(marker)

    try:
        while time.monotonic() < deadline:
            pending += port.read(8192)
            while b"\n" in pending:
                raw, pending = pending.split(b"\n", 1)
                stamp = dt.datetime.now().astimezone().isoformat(timespec="milliseconds")
                line = raw.rstrip(b"\r").decode("utf-8", errors="replace")
                rendered = f"{stamp} {line}\n"
                print(rendered, end="", flush=True)
                log.write(rendered)
    except KeyboardInterrupt:
        pass
    finally:
        if pending:
            stamp = dt.datetime.now().astimezone().isoformat(timespec="milliseconds")
            log.write(f"{stamp} {pending.decode('utf-8', errors='replace')}\n")
        marker = f"# capture-end {dt.datetime.now().astimezone().isoformat()}\n"
        print(marker, end="", flush=True)
        log.write(marker)
