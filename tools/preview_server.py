#!/usr/bin/env python3
"""Local no-flash preview server for the BRZ ZD8 gauge.

The server exposes the static UI under ``preview/`` and a tiny JSON API.  In
``auto`` mode it keeps trying an ELM327 TCP endpoint and falls back to a
deterministic driving scene while the emulator is unavailable.

No third-party Python modules are required.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import socket
import threading
import time
from dataclasses import asdict, dataclass
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Callable, Optional


ROOT = Path(__file__).resolve().parents[1]
PREVIEW_ROOT = ROOT / "preview"
HEX_PAIR = re.compile(r"[0-9A-Fa-f]{2}")


def clamp(value: float, low: float, high: float) -> float:
    return min(high, max(low, value))


@dataclass
class GaugeData:
    rpm: float = 0
    speed: float = 0
    coolant_temp: Optional[float] = None
    oil_temp: Optional[float] = None
    intake_temp: Optional[float] = None
    load: float = 0
    throttle: float = 0
    voltage: Optional[float] = None
    gear: int = 0


class PreviewState:
    def __init__(self, elm_host: str, elm_port: int, source: str) -> None:
        self.lock = threading.Lock()
        self.data = GaugeData()
        self.source = "demo"
        self.sequence = 0
        self.scenario = "road"
        self.elm_host = elm_host
        self.elm_port = elm_port
        self.source_mode = source
        self.last_error = ""
        self.reconnect_event = threading.Event()
        self.stop_event = threading.Event()

    def publish(self, data: GaugeData, source: str, error: str = "") -> None:
        with self.lock:
            self.data = data
            self.source = source
            self.last_error = error
            self.sequence += 1

    def snapshot(self) -> dict:
        with self.lock:
            return {
                "data": asdict(self.data),
                "source": self.source,
                "sequence": self.sequence,
                "scenario": self.scenario,
                "elm": f"{self.elm_host}:{self.elm_port}",
                "error": self.last_error,
            }


class Elm327Tcp:
    """Small ELM327 TCP client sufficient for standard ZD8 Mode 01 PIDs."""

    def __init__(self, host: str, port: int) -> None:
        self.host = host
        self.port = port
        self.sock: Optional[socket.socket] = None
        self.buffer = bytearray()

    def connect(self) -> None:
        self.close()
        self.sock = socket.create_connection((self.host, self.port), timeout=1.2)
        self.sock.settimeout(1.2)
        self.buffer.clear()
        # Some emulators greet with a prompt. Drain it without requiring one.
        try:
            self.sock.recv(1024)
        except socket.timeout:
            pass
        for command in ("ATZ", "ATE0", "ATL0", "ATS1", "ATH0", "ATSP6", "0100"):
            self.command(command)

    def close(self) -> None:
        if self.sock is not None:
            try:
                self.sock.close()
            except OSError:
                pass
        self.sock = None

    def command(self, command: str) -> str:
        if self.sock is None:
            raise ConnectionError("ELM327 socket is not connected")
        self.sock.sendall((command.strip() + "\r").encode("ascii"))
        deadline = time.monotonic() + 1.4
        while time.monotonic() < deadline:
            if b">" in self.buffer:
                raw, _, rest = self.buffer.partition(b">")
                self.buffer = bytearray(rest)
                return raw.decode("ascii", errors="ignore")
            chunk = self.sock.recv(2048)
            if not chunk:
                raise ConnectionError("ELM327 closed the TCP connection")
            self.buffer.extend(chunk)
        raise TimeoutError(f"ELM327 timeout waiting for {command}")

    @staticmethod
    def payload(response: str, mode: int, pid: int) -> list[int]:
        compact = "".join(HEX_PAIR.findall(response)).upper()
        marker = f"{mode + 0x40:02X}{pid:02X}"
        start = compact.find(marker)
        if start < 0:
            raise ValueError(f"response does not contain {marker}: {response!r}")
        tail = compact[start + len(marker):]
        return [int(tail[index:index + 2], 16) for index in range(0, len(tail) - 1, 2)]

    def mode01(self, pid: int) -> list[int]:
        return self.payload(self.command(f"01{pid:02X}"), 0x01, pid)


def infer_gear(rpm: float, speed: float) -> int:
    """Estimate ZD8 6MT gear from RPM and road speed."""
    if speed < 3 or rpm < 650:
        return 0
    ratios = (3.626, 2.189, 1.541, 1.213, 1.000, 0.767)
    final_drive = 4.1
    radius_m = 0.318
    wheel_rpm = speed * 1000 / 60 / (2 * math.pi * radius_m)
    observed = rpm / max(wheel_rpm * final_drive, 1)
    best = min(range(len(ratios)), key=lambda index: abs(ratios[index] - observed))
    return best + 1 if abs(ratios[best] - observed) / ratios[best] < 0.24 else 0


def query_elm(elm: Elm327Tcp, previous: GaugeData) -> GaugeData:
    """Query one compact ZD8 PID round; retain optional values on NO DATA."""

    def read(pid: int, decoder: Callable[[list[int]], float], fallback):
        try:
            payload = elm.mode01(pid)
            return decoder(payload)
        except (ValueError, IndexError, TimeoutError):
            return fallback

    rpm = read(0x0C, lambda b: ((b[0] << 8) | b[1]) / 4, previous.rpm)
    speed = read(0x0D, lambda b: b[0], previous.speed)
    data = GaugeData(
        rpm=rpm,
        speed=speed,
        coolant_temp=read(0x05, lambda b: b[0] - 40, previous.coolant_temp),
        oil_temp=read(0x5C, lambda b: b[0] - 40, previous.oil_temp),
        intake_temp=read(0x0F, lambda b: b[0] - 40, previous.intake_temp),
        load=read(0x04, lambda b: b[0] * 100 / 255, previous.load),
        throttle=read(0x11, lambda b: b[0] * 100 / 255, previous.throttle),
        voltage=read(0x42, lambda b: ((b[0] << 8) | b[1]) / 1000, previous.voltage),
    )
    data.gear = infer_gear(data.rpm, data.speed)
    return data


def demo_data(scenario: str, elapsed: float) -> GaugeData:
    if scenario == "idle":
        rpm = 780 + math.sin(elapsed * 2.1) * 24
        speed = 0
        throttle = 4 + math.sin(elapsed * 1.3)
        load = 17 + math.sin(elapsed * .7) * 3
        oil = min(91, 48 + elapsed * .28)
        coolant = min(90, 55 + elapsed * .34)
        gear = 0
    elif scenario == "track":
        phase = (elapsed % 14) / 14
        saw = phase if phase < .72 else 1 - (phase - .72) / .28
        rpm = 2900 + saw * 4750 + math.sin(elapsed * 3) * 120
        speed = 54 + saw * 108
        throttle = 32 + saw * 66
        load = 42 + saw * 55
        oil = 106 + math.sin(elapsed / 7) * 5
        coolant = 94 + math.sin(elapsed / 9) * 2
        gear = clamp(round(2 + phase * 4), 2, 6)
    else:
        phase = (elapsed % 22) / 22
        wave = .5 - .5 * math.cos(phase * math.tau)
        rpm = 1050 + wave * 4650 + math.sin(elapsed * 1.8) * 90
        speed = 8 + wave * 102
        throttle = 9 + wave * 72
        load = 24 + wave * 61
        oil = 88 + math.sin(elapsed / 10) * 4
        coolant = 91 + math.sin(elapsed / 12) * 2
        gear = clamp(round(1 + wave * 5), 1, 6)
    return GaugeData(
        rpm=max(0, rpm), speed=max(0, speed), coolant_temp=coolant, oil_temp=oil,
        intake_temp=31 + math.sin(elapsed / 6) * 4, load=clamp(load, 0, 100),
        throttle=clamp(throttle, 0, 100), voltage=13.8 + math.sin(elapsed / 5) * .12,
        gear=int(gear),
    )


def data_worker(state: PreviewState) -> None:
    elm = Elm327Tcp(state.elm_host, state.elm_port)
    started = time.monotonic()
    next_connect = 0.0
    connected = False
    while not state.stop_event.is_set():
        now = time.monotonic()
        force_reconnect = state.reconnect_event.is_set()
        if force_reconnect:
            state.reconnect_event.clear()
            elm.close()
            connected = False
            next_connect = 0

        wants_elm = state.source_mode in ("auto", "elm327")
        if wants_elm and not connected and now >= next_connect:
            try:
                elm.connect()
                connected = True
            except (OSError, TimeoutError, ValueError) as exc:
                elm.close()
                next_connect = now + 2.0
                if state.source_mode == "elm327":
                    state.publish(demo_data(state.scenario, now - started), "demo", str(exc))

        if connected:
            try:
                current = state.snapshot()["data"]
                previous = GaugeData(**current)
                state.publish(query_elm(elm, previous), "elm327")
                state.stop_event.wait(.04)
                continue
            except (OSError, TimeoutError, ConnectionError, ValueError) as exc:
                elm.close()
                connected = False
                next_connect = time.monotonic() + 1.5
                state.last_error = str(exc)

        state.publish(demo_data(state.scenario, now - started), "demo", state.last_error)
        state.stop_event.wait(.08)
    elm.close()


class PreviewHandler(SimpleHTTPRequestHandler):
    server_version = "BRZGaugePreview/1.0"

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=os.fspath(PREVIEW_ROOT), **kwargs)

    @property
    def state(self) -> PreviewState:
        return self.server.state  # type: ignore[attr-defined]

    def log_message(self, fmt: str, *args) -> None:
        if self.path.startswith(("/api/data", "/api/native/status", "/lvgl_frame.bmp")):
            return
        super().log_message(fmt, *args)

    def send_json(self, payload: dict, status: HTTPStatus = HTTPStatus.OK) -> None:
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        if self.path.split("?", 1)[0] == "/lvgl_frame.bmp":
            frame_path = PREVIEW_ROOT / "lvgl_frame.bmp"
            body = None
            for _ in range(3):
                try:
                    body = frame_path.read_bytes()
                    break
                except OSError:
                    time.sleep(.01)
            if body is None:
                self.send_error(HTTPStatus.SERVICE_UNAVAILABLE, "LVGL frame is not ready")
                return
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "image/bmp")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if self.path.split("?", 1)[0] == "/api/data":
            self.send_json(self.state.snapshot())
            return
        if self.path.split("?", 1)[0] == "/api/native/status":
            status_path = PREVIEW_ROOT / "lvgl_status.json"
            try:
                self.send_json(json.loads(status_path.read_text(encoding="utf-8")))
            except (OSError, json.JSONDecodeError):
                self.send_json({"online": False, "source": "offline", "page": 3})
            return
        super().do_GET()

    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", "0") or 0)
        try:
            body = json.loads(self.rfile.read(length) or b"{}")
        except json.JSONDecodeError:
            self.send_json({"ok": False, "error": "invalid JSON"}, HTTPStatus.BAD_REQUEST)
            return
        if self.path == "/api/scenario":
            scenario = body.get("scenario")
            if scenario not in ("road", "track", "idle"):
                self.send_json({"ok": False, "error": "invalid scenario"}, HTTPStatus.BAD_REQUEST)
                return
            with self.state.lock:
                self.state.scenario = scenario
            self.send_json({"ok": True, "scenario": scenario})
            return
        if self.path == "/api/reconnect":
            self.state.reconnect_event.set()
            self.send_json({"ok": True})
            return
        if self.path == "/api/native/page":
            try:
                page = int(body.get("page"))
            except (TypeError, ValueError):
                page = -1
            if not 0 <= page <= 20:
                self.send_json({"ok": False, "error": "invalid page"}, HTTPStatus.BAD_REQUEST)
                return
            (PREVIEW_ROOT / "lvgl_command.txt").write_text(f"page {page}", encoding="ascii")
            self.send_json({"ok": True, "page": page})
            return
        if self.path == "/api/native/gesture":
            direction = body.get("direction")
            if direction not in ("left", "right", "up", "down"):
                self.send_json({"ok": False, "error": "invalid gesture"}, HTTPStatus.BAD_REQUEST)
                return
            (PREVIEW_ROOT / "lvgl_command.txt").write_text(f"gesture {direction}", encoding="ascii")
            self.send_json({"ok": True, "direction": direction})
            return
        if self.path == "/api/native/rpm-warn":
            try:
                value = int(body.get("value"))
            except (TypeError, ValueError):
                value = 0
            if not 1000 <= value <= 6500:
                self.send_json({"ok": False, "error": "invalid RPM threshold"}, HTTPStatus.BAD_REQUEST)
                return
            value = round(value / 500) * 500
            (PREVIEW_ROOT / "lvgl_command.txt").write_text(f"rpmwarn {value}", encoding="ascii")
            self.send_json({"ok": True, "value": value})
            return
        if self.path == "/api/native/data":
            try:
                rpm = int(body.get("rpm"))
                speed = int(body.get("speed"))
                gear = int(body.get("gear"))
            except (TypeError, ValueError):
                self.send_json({"ok": False, "error": "invalid gauge data"}, HTTPStatus.BAD_REQUEST)
                return
            if not 0 <= rpm <= 9000 or not 0 <= speed <= 240 or not 0 <= gear <= 6:
                self.send_json({"ok": False, "error": "gauge data out of range"}, HTTPStatus.BAD_REQUEST)
                return
            (PREVIEW_ROOT / "lvgl_command.txt").write_text(
                f"data {rpm} {speed} {gear}", encoding="ascii")
            self.send_json({"ok": True, "rpm": rpm, "speed": speed, "gear": gear})
            return
        if self.path == "/api/native/data-auto":
            (PREVIEW_ROOT / "lvgl_command.txt").write_text("data-auto", encoding="ascii")
            self.send_json({"ok": True})
            return
        self.send_json({"ok": False, "error": "not found"}, HTTPStatus.NOT_FOUND)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="BRZ ZD8 gauge no-flash preview")
    parser.add_argument("--host", default="127.0.0.1", help="preview HTTP listen address")
    parser.add_argument("--port", type=int, default=8080, help="preview HTTP port")
    parser.add_argument("--elm-host", default="127.0.0.1", help="ELM327-emulator TCP host")
    parser.add_argument("--elm-port", type=int, default=35000, help="ELM327-emulator TCP port")
    parser.add_argument("--source", choices=("auto", "elm327", "demo"), default="auto")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not PREVIEW_ROOT.is_dir():
        raise SystemExit(f"preview directory not found: {PREVIEW_ROOT}")
    state = PreviewState(args.elm_host, args.elm_port, args.source)
    worker = threading.Thread(target=data_worker, args=(state,), name="gauge-data", daemon=True)
    worker.start()
    server = ThreadingHTTPServer((args.host, args.port), PreviewHandler)
    server.state = state  # type: ignore[attr-defined]
    print(f"BRZ ZD8 preview: http://{args.host}:{args.port}")
    print(f"ELM327 TCP: {args.elm_host}:{args.elm_port} ({args.source})")
    try:
        server.serve_forever(poll_interval=.2)
    except KeyboardInterrupt:
        print("\nStopping preview...")
    finally:
        state.stop_event.set()
        server.server_close()
        worker.join(timeout=2)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
