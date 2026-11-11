"""Record ELM327 BLE traffic and replay it through the existing gauge simulator."""
import argparse
import asyncio
from bisect import bisect_right
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import sys
import time

from obd_ble_simulator import StaticElm

ROOT = Path(__file__).resolve().parents[1]
CORE_PIDS = (3, 4, 5, 11, 12, 13, 15, 16, 17, 31, 47, 51, 66, 68, 92, 94, 164, 166)


def normalize(command):
    command = ''.join(command.upper().split())
    if not command.startswith('AT') and len(command) % 2 and command[-1:] in '123456789':
        command = command[:-1]
    return command


def body(response, command):
    lines = response.decode('ascii', errors='replace').replace('>', '').splitlines()
    return '\r'.join(line.strip() for line in lines
                     if line.strip() and normalize(line) != normalize(command)) + '\r'


def bitmap_pids(response, base):
    """Union of headerless Mode 01 support responses from responding ECUs."""
    found = set()
    for line in response.decode('ascii', errors='replace').splitlines():
        compact = ''.join(line.split()).replace('>', '')
        if re.fullmatch(f'41{base:02X}[0-9A-Fa-f]{{8}}', compact, re.I):
            mask = int(compact[4:], 16)
            found.update(base + i for i in range(1, 33) if mask & (1 << (32 - i)))
    return found


class Recording:
    def __init__(self, path, on_event=None, **metadata):
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        self.file = path.open('x', encoding='utf-8', buffering=1)
        self.start = time.monotonic()
        self.on_event = on_event
        self.emit('session', schema=1, utc=datetime.now(timezone.utc).isoformat(), **metadata)

    def emit(self, kind, **fields):
        event = dict(kind=kind, t=time.monotonic() - self.start, **fields)
        self.file.write(json.dumps(event, ensure_ascii=False) + '\n')
        if self.on_event:
            self.on_event(event)

    def close(self):
        self.emit('end')
        self.file.close()


def uuid16(short):
    return f'0000{short:04x}-0000-1000-8000-00805f9b34fb'


def select_chars(services):
    # Same service preference and property fallback as elm327_ble_client.c.
    for service_id, write_id, notify_id in ((0xFFF0, 0xFFF1, 0xFFF2),
                                            (0x18F0, 0x2AF1, 0x2AF0),
                                            (0xFF12, 0xFF15, 0xFF14)):
        service = services.get_service(uuid16(service_id))
        if service is None:
            continue
        chars = list(service.characteristics)
        writes = [c for c in chars if {'write', 'write-without-response'} & set(c.properties)]
        notifications = [c for c in chars if 'notify' in c.properties]
        if writes and notifications:
            return (next((c for c in writes if c.uuid == uuid16(write_id)), writes[0]),
                    next((c for c in notifications if c.uuid == uuid16(notify_id)), notifications[0]))
    raise RuntimeError('No compatible ELM BLE service (FFF0 / 18F0 / FF12) found.')


class ElmConnection:
    def __init__(self, client, write_char, recording, timeout=8):
        self.client, self.write_char, self.recording = client, write_char, recording
        self.timeout = timeout
        self.queue = asyncio.Queue()
        self.header = '7E0'

    def notify(self, sender, data):
        data = bytes(data)
        self.recording.emit('rx', hex=data.hex())
        self.queue.put_nowait(data)

    async def query(self, command, phase='poll'):
        command = normalize(command)
        if not self.queue.empty():
            raise RuntimeError('Unexpected data between commands; stopping to avoid mismatched responses.')
        packet = (command + '\r').encode('ascii')
        started = time.monotonic() - self.recording.start
        self.recording.emit('tx', command=command, hex=packet.hex(), header=self.header)
        received = bytearray()
        try:
            async with asyncio.timeout(self.timeout):
                await self.client.write_gatt_char(self.write_char, packet,
                    response='write-without-response' not in self.write_char.properties)
                while b'>' not in received:
                    received.extend(await self.queue.get())
                    if len(received) > 1024 * 1024:
                        raise RuntimeError('ELM reply exceeds 1 MiB without a prompt.')
        except BaseException:
            self.recording.emit('reply', command=command, header=self.header, phase=phase,
                                started=started, hex=received.hex(), complete=False)
            # Never send another PID after timeout: a late reply would corrupt pairing.
            raise
        self.recording.emit('reply', command=command, header=self.header, phase=phase,
                            started=started, hex=received.hex(), complete=True)
        if command.startswith('ATSH') and b'OK' in received:
            self.header = command[4:]
        return bytes(received)


async def scan():
    from bleak import BleakScanner
    devices = await BleakScanner.discover(timeout=8, return_adv=True)
    result = []
    for device, adv in devices.values():
        name = adv.local_name or device.name or '(unnamed)'
        print(f'{len(result) + 1:2}. {device.address}  {name}  RSSI={adv.rssi}')
        result.append(device)
    return result


async def capture(args, on_event=None):
    from bleak import BleakClient
    if args.profile == 'zd8' and args.extra:
        raise ValueError('ZD8 gauge mode uses a fixed schedule; --extra is not supported.')
    address = args.address
    if not address:
        devices = await scan()
        if not devices:
            raise RuntimeError('No BLE devices found. Check Bluetooth and adapter power.')
        choice = int(await asyncio.to_thread(input, 'ELM device number: '))
        if not 1 <= choice <= len(devices):
            raise ValueError('Invalid device number')
        address = devices[choice - 1]
    path = args.output or ROOT / 'logs' / 'obd-recordings' / (datetime.now().strftime('%Y%m%d-%H%M%S-%f') + '.jsonl')
    recording = Recording(path, on_event=on_event, address=str(getattr(address, 'address', address)), protocol=6,
                          header='7E0', headers=False, profile=args.profile)
    print(f'Recording: {path}', flush=True)
    try:
        async with BleakClient(address, timeout=20) as client:
            write_char, notify_char = select_chars(client.services)
            recording.emit('transport', write=write_char.uuid, notify=notify_char.uuid)
            elm = ElmConnection(client, write_char, recording)
            await client.start_notify(notify_char, elm.notify)
            for command in ('ATZ', 'ATE0', 'ATL0', 'ATS1', 'ATH0', 'ATAT1',
                            'ATST0A' if args.profile == 'zd8' else 'ATST19', 'ATSP6', 'ATSH7E0'):
                response = await elm.query(command, 'init')
                if command != 'ATZ' and 'OK' not in body(response, command):
                    raise RuntimeError(f'ELM initialization rejected {command}: {response!r}')
                await asyncio.sleep(.03)
            if args.profile == 'zd8':
                from obd_gauge_poll import capture_zd8
                await capture_zd8(elm, args, recording)
                return
            supported = set()
            for base in range(0, 0xE1, 32):
                pids = set()
                for attempt in range(3 if base == 0 else 1):
                    pids = bitmap_pids(await elm.query(f'01{base:02X}', 'discovery'), base)
                    if pids:
                        break
                    await asyncio.sleep(.15)
                supported.update(p for p in pids if p <= 255 and p % 32)
                if base + 32 not in pids:
                    break
            if not supported:
                print('No support bitmap; trying the gauge standard PID set.', flush=True)
                supported.update(CORE_PIDS)
            pids = sorted(supported if args.profile == 'all' else supported.intersection(CORE_PIDS))
            commands = [f'01{pid:02X}' for pid in pids]
            if args.profile == 'zc6':
                commands.append('2101')
            commands += args.extra
            commands = list(dict.fromkeys(commands))
            if not commands:
                raise RuntimeError('No PIDs available for this profile.')
            recording.emit('poll_plan', commands=commands)
            print(f'Polling {len(commands)} requests. Ctrl+C saves and stops.', flush=True)
            start, count = time.monotonic(), 0
            while not args.duration or time.monotonic() - start < args.duration:
                for command in commands:
                    if args.duration and time.monotonic() - start >= args.duration:
                        break
                    if not client.is_connected:
                        raise RuntimeError('ELM disconnected. Partial recording is saved.')
                    response = await elm.query(command)
                    count += 1
                    if args.verbose or count % 50 == 0:
                        print(f'{count:7} {command}: {body(response, command).strip()}', flush=True)
                    await asyncio.sleep(args.interval)
    except BaseException as exc:
        recording.emit('stop', reason=type(exc).__name__, detail=str(exc))
        raise
    finally:
        recording.close()
        print(f'Saved: {path}', flush=True)


class ReplayData:
    def __init__(self, path):
        self.samples, self.discovery = {}, {}
        self.end = 0
        session = None
        with Path(path).open(encoding='utf-8') as file:
            for number, line in enumerate(file, 1):
                if not line.endswith('\n'):
                    print(f'Ignoring incomplete final line {number}.', file=sys.stderr)
                    break
                row = json.loads(line)
                self.end = max(self.end, row['t'])
                if row['kind'] == 'session':
                    session = row
                if row['kind'] != 'reply' or not row['complete']:
                    continue
                command = normalize(row['command'])
                if command.startswith('AT'):
                    continue
                key = (row['header'], command)
                response = body(bytes.fromhex(row['hex']), command)
                if row['phase'] == 'discovery':
                    self.discovery[key] = response
                elif row['phase'] == 'poll':
                    self.samples.setdefault(key, []).append((row['t'], response))
        if not session or session.get('schema') != 1 or session.get('headers') is not False:
            raise ValueError('Unsupported recording format; expected schema 1 with ATH0.')
        if not self.samples:
            raise ValueError('Recording has no complete polling responses.')
        for samples in self.samples.values():
            samples.sort(key=lambda item: item[0])
        self.times = {key: [item[0] for item in rows] for key, rows in self.samples.items()}
        self.origin = min(times[0] for times in self.times.values())
        self.duration = max(.001, self.end - self.origin)
        self.profile = session.get('profile', 'gauge')


class ReplayElm(StaticElm):
    def __init__(self, data, speed=1, loop=False, clock=time.monotonic):
        self.data, self.speed, self.loop, self.clock = data, speed, loop, clock
        self.started = None
        self.telemetry = None
        super().__init__()

    def feed(self, data):
        # Preserve per-command source timestamps when a BLE write batches requests.
        replies = []
        self.reply_telemetry = []
        for value in data:
            completed = super().feed(bytes([value]))
            for pair in completed:
                replies.append(pair)
                self.reply_telemetry.append(self.telemetry)
        return replies

    def command(self, command):
        self.telemetry = None
        raw = normalize(command)
        if raw.startswith('AT'):
            # Voltage must not silently come from the static simulator.
            if raw == 'ATRV':
                return self.render(command, 'NO DATA\r')
            return super().command(command)
        key = (self.header, raw)
        response = self.data.discovery.get(key)
        if response is None:
            if self.started is None:
                self.started = self.clock()
            elapsed = (self.clock() - self.started) * self.speed
            cycle = int(elapsed / self.data.duration) if self.loop else 0
            if self.loop:
                elapsed %= self.data.duration
            self.telemetry = dict(t=self.data.origin + min(elapsed, self.data.duration),
                                  cycle=cycle, sampled_at=None)
            response = 'NO DATA\r'
            if elapsed <= self.data.duration and key in self.data.samples:
                index = bisect_right(self.data.times[key], self.data.origin + elapsed) - 1
                if index >= 0:
                    response = self.data.samples[key][index][1]
                    self.telemetry['sampled_at'] = self.data.samples[key][index][0]
        # Capture intentionally uses the gauge's ATH0 mode. Never invent ECU IDs.
        if self.headers:
            response = 'NO DATA\r'
        return self.render(command, response)

    def render(self, command, response):
        if not self.spaces:
            response = '\r'.join(line.replace(' ', '') if re.fullmatch(r'[0-9A-Fa-f :]+', line)
                                 else line for line in response.split('\r'))
        output = (command + '\r' if self.echo else '') + response
        if self.linefeed:
            output = output.replace('\r', '\r\n')
        return (output + '>').encode('ascii', errors='replace')


def read_request(value):
    value = normalize(value)
    if not re.fullmatch(r'(01[0-9A-F]{2}|21[0-9A-F]{2}|22[0-9A-F]{4})', value):
        raise argparse.ArgumentTypeError('Only read requests 01xx, 21xx or 22xxxx are accepted.')
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action', required=True)
    sub.add_parser('scan', help='List Bluetooth LE devices')
    record = sub.add_parser('record', help='Capture from a real ELM327')
    record.add_argument('--address', help='Exact BLE address; otherwise choose from a scan')
    record.add_argument('--output', type=Path)
    record.add_argument('--profile', choices=('all', 'gauge', 'zc6', 'zd8'), default='all')
    record.add_argument('--extra', action='append', type=read_request, default=[])
    record.add_argument('--duration', type=float, default=0)
    record.add_argument('--interval', type=float, default=.03, help='Pause after each reply, seconds')
    record.add_argument('-v', '--verbose', action='store_true')
    replay = sub.add_parser('replay', help='Serve a recording to the gauge over BLE')
    replay.add_argument('file', type=Path)
    replay.add_argument('--speed', type=float, default=1)
    replay.add_argument('--loop', action='store_true')
    replay.add_argument('-v', '--verbose', action='store_true')
    args = parser.parse_args()
    import math
    for name in ('duration', 'interval', 'speed'):
        value = getattr(args, name, 0)
        if not math.isfinite(value) or value < 0 or (name == 'speed' and value == 0):
            parser.error(f'Invalid --{name}')
    try:
        if args.action == 'scan':
            asyncio.run(scan())
        elif args.action == 'record':
            asyncio.run(capture(args))
        else:
            from obd_ble_simulator import serve
            data = ReplayData(args.file)
            args.check, args.duration = False, 0
            print(f'Replay: {len(data.samples)} requests, {data.duration:.1f}s, speed={args.speed}x')
            asyncio.run(serve(args, elm_factory=lambda: ReplayElm(data, args.speed, args.loop)))
    except KeyboardInterrupt:
        return 0
    except Exception as exc:
        print(f'ERROR: {exc or type(exc).__name__}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
