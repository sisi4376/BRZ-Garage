#!/usr/bin/env python3
"""Static ELM327 BLE peripheral for Windows; no vehicle or gauge firmware needed."""

import argparse
import asyncio
import contextlib
from pathlib import Path
import re
import socket
import sys
from uuid import UUID

from fake_elm327 import format_response

# Encoded Mode 01 values. Keep the fuel inputs zero as well as RPM/speed.
STATIC_PIDS = {
    0x03: [0x01, 0x00],       # engine off / open loop
    0x04: [0],                # load 0%
    0x05: [65],               # coolant 25 C (A - 40)
    0x0B: [100],              # MAP 100 kPa, equal to ambient
    0x0C: [0, 0],             # RPM 0
    0x0D: [0],                # speed 0 km/h
    0x0F: [65],               # intake 25 C
    0x10: [0, 0],             # MAF 0 g/s
    0x11: [0],                # throttle 0%
    0x1F: [0, 0],             # engine runtime 0 seconds
    0x2F: [128],              # tank approximately 50.2%
    0x33: [100],              # ambient 100 kPa
    0x42: [0x31, 0x38],       # 12.6 V
    0x44: [0x80, 0],          # lambda 1.0 (fixed reference)
    0x5C: [65],               # oil 25 C
    0x5E: [0, 0],             # fuel rate 0 L/h
}


def support_bitmap(base):
    supported = set(STATIC_PIDS)
    supported.update((0x20, 0x40))  # continuation to the last supported block
    value = sum(1 << (32 - (pid - base)) for pid in supported
                if base < pid <= base + 32)
    return list(value.to_bytes(4, "big"))


class StaticElm:
    """One command stream per connected central, independent of BLE transport."""

    def __init__(self):
        self.pending = bytearray()
        self.discard = False
        self.after_cr = False
        self.reset()

    def reset(self):
        self.echo = True
        self.spaces = True
        self.headers = False
        self.linefeed = False
        self.header = "7E0"

    def command(self, command):
        raw = "".join(command.upper().split())
        echo = self.echo
        response = "?\r"
        if raw in ("ATZ", "ATWS", "ATD"):
            self.reset()
            response = "OK\r" if raw == "ATD" else "ELM327 v1.5\r"
        elif raw == "ATI":
            response = "ELM327 v1.5\r"
        elif raw == "ATRV":
            response = "12.6V\r"
        elif raw == "ATDPN":
            response = "6\r"
        elif raw == "ATDP":
            response = "ISO 15765-4 (CAN 11/500)\r"
        elif re.fullmatch(r"AT[EHSL][01]", raw):
            attr = {"E": "echo", "H": "headers", "S": "spaces", "L": "linefeed"}[raw[2]]
            setattr(self, attr, raw[-1] == "1")
            response = "OK\r"
        elif re.fullmatch(r"ATSH[0-9A-F]{3}", raw):
            self.header = raw[4:]
            response = "OK\r"
        elif (raw in ("ATSP0", "ATSP6", "ATSPA6", "ATTP6", "ATAL", "ATPC", "ATAR", "ATCRA")
              or re.fullmatch(r"AT(?:AT[012]|ST[0-9A-F]{2}|CAF[01]|CFC[01]|CRA[0-9A-F]{3})", raw)):
            response = "OK\r"
        elif not raw.startswith("AT"):
            # ELM permits a trailing response-count nibble: 010C1 / 01 0C 1.
            if len(raw) % 2 and raw[-1:] in "123456789":
                raw = raw[:-1]
            try:
                request = bytes.fromhex(raw)
            except ValueError:
                request = b""
            payloads = []
            if len(request) >= 2 and request[0] == 1:
                for pid in request[1:]:
                    if pid % 32 == 0:
                        payloads.append([0x41, pid] + support_bitmap(pid))
                    elif pid in STATIC_PIDS:
                        payloads.append([0x41, pid] + STATIC_PIDS[pid])
            elif request in (b"\x03", b"\x07", b"\x0a"):
                payloads.append([request[0] + 0x40, 0, 0])
            response = "".join(format_response(p, self.header, self.headers, self.spaces)
                               for p in payloads) or "NO DATA\r"
        output = (command + "\r" if echo else "") + response
        if self.linefeed:
            output = output.replace("\r", "\r\n")
        return (output + ">").encode("ascii", errors="replace")

    def feed(self, data):
        """Accept fragmented or batched writes; return complete command/reply pairs."""
        replies = []
        for value in data:
            if value == 10 and self.after_cr:
                self.after_cr = False
                continue
            self.after_cr = value == 13
            if value in (10, 13):
                command = self.pending.decode("ascii", errors="replace").strip()
                self.pending.clear()
                if self.discard:
                    replies.append(("<oversize command>", b"?\r>"))
                elif command:
                    replies.append((command, self.command(command)))
                else:
                    replies.append(("", b"\r>"))
                self.discard = False
            elif not self.discard:
                if len(self.pending) >= 256:
                    self.pending.clear()
                    self.discard = True
                else:
                    self.pending.append(value)
        return replies


def load_winrt():
    if sys.platform != "win32":
        raise RuntimeError("BLE transport requires Windows 10/11.")
    cache = Path(__file__).resolve().parent / ".cache" / "obd-ble-python"
    if cache.exists():
        sys.path.insert(0, str(cache))
    try:
        import winrt.windows.devices.bluetooth as bluetooth
        import winrt.windows.devices.bluetooth.genericattributeprofile as gatt
        from winrt.windows.storage.streams import DataWriter
        from winrt.windows.devices.radios import RadioState
    except ImportError as exc:
        raise RuntimeError("Run tools/start_obd_ble_simulator.ps1 to install BLE dependencies.") from exc
    return bluetooth, gatt, DataWriter, RadioState


async def wait_for_advertising(provider, status_enum, timeout=10):
    """Windows can report ABORTED/SUCCESS before asynchronously reaching STARTED.

    Do not tear down the provider on that intermediate event. Only a started
    state or expiration of the bounded startup window decides the outcome.
    """
    loop = asyncio.get_running_loop()
    deadline = loop.time() + timeout
    while True:
        status = provider.advertisement_status
        if status in (status_enum.STARTED, status_enum.STARTED_WITHOUT_ALL_ADVERTISEMENT_DATA):
            return status
        if loop.time() >= deadline:
            raise RuntimeError(f"BLE advertising did not start within {timeout:g}s (last status: {status.name}).")
        await asyncio.sleep(0.1)


async def serve(args):
    bluetooth, gatt, DataWriter, RadioState = load_winrt()
    adapter = await asyncio.wait_for(bluetooth.BluetoothAdapter.get_default_async(), 15)
    if adapter is None:
        raise RuntimeError("No Bluetooth adapter found.")
    if not adapter.is_peripheral_role_supported:
        raise RuntimeError("This Bluetooth adapter/driver does not support the BLE peripheral role.")
    radio = await asyncio.wait_for(adapter.get_radio_async(), 10)
    print(f"PC name: {socket.gethostname()}; radio: {radio.name}; peripheral role: supported", flush=True)
    print(f"Adapter address: {adapter.bluetooth_address:012X} (BLE advertising address may differ)")
    if radio.state != RadioState.ON:
        raise RuntimeError("Turn on Bluetooth in Windows Settings, then retry.")
    if args.check:
        return

    def uuid(short):
        return UUID(f"0000{short:04x}-0000-1000-8000-00805f9b34fb")

    result = await asyncio.wait_for(gatt.GattServiceProvider.create_async(uuid(0xFFF0)), 15)
    if result.error != bluetooth.BluetoothError.SUCCESS:
        raise RuntimeError(f"Cannot create FFF0 service: {result.error.name}")
    provider = result.service_provider

    async def characteristic(short, properties):
        params = gatt.GattLocalCharacteristicParameters()
        params.characteristic_properties = properties
        params.write_protection_level = gatt.GattProtectionLevel.PLAIN
        params.read_protection_level = gatt.GattProtectionLevel.PLAIN
        result = await provider.service.create_characteristic_async(uuid(short), params)
        if result.error != bluetooth.BluetoothError.SUCCESS:
            raise RuntimeError(f"Cannot create {short:04X}: {result.error.name}")
        return result.characteristic

    properties = gatt.GattCharacteristicProperties
    rx = await characteristic(0xFFF1, properties.WRITE | properties.WRITE_WITHOUT_RESPONSE)
    tx = await characteristic(0xFFF2, properties.NOTIFY)
    loop = asyncio.get_running_loop()
    queue = asyncio.Queue(maxsize=128)
    sessions = {}
    count = 0
    stopping = False
    def on_advertisement(sender, event):
        detail = "" if event.error == bluetooth.BluetoothError.SUCCESS else f" ({event.error.name})"
        print(f"Advertising state: {event.status.name}{detail}", flush=True)

    def on_write(sender, event):
        deferral = event.get_deferral()

        def enqueue():
            if stopping or queue.full():
                deferral.complete()
                return
            queue.put_nowait((event, deferral))
        try:
            loop.call_soon_threadsafe(enqueue)
        except RuntimeError:
            deferral.complete()

    def on_clients(sender, event):
        def update():
            clients = {client.session.device_id.id for client in tx.subscribed_clients}
            for key in list(sessions):
                if key not in clients:
                    del sessions[key]
            print(f"Subscribed gauges: {len(clients)}", flush=True)
        if not stopping:
            with contextlib.suppress(RuntimeError):
                loop.call_soon_threadsafe(update)

    async def worker():
        nonlocal count
        while True:
            event, deferral = await queue.get()
            try:
                request = await asyncio.wait_for(event.get_request_async(), 5)
                if request is None:
                    continue
                if request.offset:
                    request.respond_with_protocol_error(0x07)  # invalid offset
                    continue
                data = bytes(memoryview(request.value))
                if request.option == gatt.GattWriteOption.WRITE_WITH_RESPONSE:
                    request.respond()
                key = event.session.device_id.id
                client = next((c for c in tx.subscribed_clients if c.session.device_id.id == key), None)
                if client is None:
                    continue
                elm = sessions.setdefault(key, StaticElm())
                for command, reply in elm.feed(data):
                    count += 1
                    if args.verbose or count <= 12:
                        print(f"{command!r} -> {reply.decode('ascii')!r}", flush=True)
                    # 20-byte fragments also work with the minimum ATT MTU (23).
                    size = max(1, min(20, client.max_notification_size))
                    for offset in range(0, len(reply), size):
                        writer = DataWriter()
                        writer.write_bytes(reply[offset:offset + size])
                        buffer = writer.detach_buffer()
                        writer.close()
                        result = await asyncio.wait_for(
                            tx.notify_value_for_subscribed_client_async(buffer, client), 5)
                        if result.status != gatt.GattCommunicationStatus.SUCCESS:
                            raise RuntimeError(f"Notify failed: {result.status.name}")
            except Exception as exc:
                print(f"BLE request error: {exc}", flush=True)
            finally:
                deferral.complete()
                queue.task_done()

    write_token = rx.add_write_requested(on_write)
    clients_token = tx.add_subscribed_clients_changed(on_clients)
    advertisement_token = provider.add_advertisement_status_changed(on_advertisement)
    task = asyncio.create_task(worker())
    try:
        advertising = gatt.GattServiceProviderAdvertisingParameters()
        advertising.is_connectable = True
        advertising.is_discoverable = True
        provider.start_advertising_with_parameters(advertising)
        status_enum = gatt.GattServiceProviderAdvertisementStatus
        status = await wait_for_advertising(provider, status_enum)
        if status == status_enum.STARTED_WITHOUT_ALL_ADVERTISEMENT_DATA:
            print("Windows started advertising with reduced data; if the PC name is absent, close other BLE servers and retry.")
        print("READY: FFF0 / FFF1 write / FFF2 notify", flush=True)
        print("Select this PC on the gauge BLE SCAN page; use the ZD8 or ZD8 OBD profile.")
        print("RPM/speed/fuel/MAF/load/throttle = 0; temperatures = 25 C; voltage = 12.6 V.")
        print("Ctrl+C to stop. Waiting for gauge requests...", flush=True)
        start = loop.time()
        while not args.duration or loop.time() - start < args.duration:
            await asyncio.sleep(0.25)
            if provider.advertisement_status not in (
                    status_enum.STARTED, status_enum.STARTED_WITHOUT_ALL_ADVERTISEMENT_DATA):
                print("Waiting for Windows to resume Bluetooth advertising...", flush=True)
                await wait_for_advertising(provider, status_enum)
    finally:
        stopping = True
        provider.stop_advertising()
        provider.remove_advertisement_status_changed(advertisement_token)
        rx.remove_write_requested(write_token)
        tx.remove_subscribed_clients_changed(clients_token)
        task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await task
        while not queue.empty():
            _, deferral = queue.get_nowait()
            deferral.complete()
            queue.task_done()
        print(f"Stopped. Commands answered: {count}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Check Bluetooth capability without advertising")
    parser.add_argument("--duration", type=float, default=0, help="Stop after N seconds; 0 = until Ctrl+C")
    parser.add_argument("-v", "--verbose", action="store_true", help="Print every command/reply")
    args = parser.parse_args()
    if args.duration < 0:
        parser.error("--duration must be nonnegative")
    try:
        asyncio.run(serve(args))
    except KeyboardInterrupt:
        pass
    except (RuntimeError, OSError, asyncio.TimeoutError) as exc:
        print(f"ERROR: {exc or 'Windows Bluetooth operation timed out'}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
