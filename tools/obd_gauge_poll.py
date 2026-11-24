"""BRZ A/B grouped PID schedules from elm327_ble_client.c.

This mirrors the request policy, not ESP32 task/radio timing or recovery logic.
"""


from dataclasses import dataclass


@dataclass(frozen=True)
class GaugeProfile:
    label: str
    oil_command: str
    automatic: bool


GAUGE_PROFILES = {
    'zd8-mt': GaugeProfile('ZD8 6MT', '015C', False),
    'zd8-at': GaugeProfile('ZD8 6AT', '015C', True),
    'zc6-mt': GaugeProfile('ZC6 6MT', '2101', False),
    'zc6-at': GaugeProfile('ZC6 6AT', '2101', True),
}
GAUGE_PROFILES['zd8'] = GAUGE_PROFILES['zd8-mt']  # v1.3 CLI compatibility


# Positive Mode 01 payload widths for the four supported profiles.
GROUP_WIDTH = {pid: 1 for pid in (4, 5, 13, 14, 15, 17, 47, 92)}
GROUP_WIDTH.update({pid: 2 for pid in (3, 12, 16, 66, 68, 94)})


def split_group(command, response):
    """Return equivalent single-PID bodies only after validating the entire reply."""
    if not command.startswith('01') or not 6 <= len(command) <= 14 or len(command) % 2:
        return {}
    try:
        expected = bytes.fromhex(command[2:])
        lines = [line.strip() for line in response.strip().removesuffix('>').splitlines() if line.strip()]
        if len(lines) == 1:
            payload = bytes.fromhex(lines[0])
        else:
            if not lines or len(lines[0]) != 3:
                return {}
            remaining = int(lines[0], 16)
            if not 3 <= remaining <= 24:
                return {}
            payload = bytearray()
            for index, line in enumerate(lines[1:]):
                if remaining <= 0 or not line.upper().startswith(f'{index:X}:'):
                    return {}
                chunk = bytes.fromhex(line[2:])
                capacity = 7 if index else 6
                used = min(remaining, capacity)
                if not used <= len(chunk) <= capacity:
                    return {}
                payload.extend(chunk[:used])
                remaining -= used
            if remaining:
                return {}
        if not payload or payload[0] != 0x41:
            return {}
        result = {}
        offset = 1
        while offset < len(payload):
            pid = payload[offset]
            width = GROUP_WIDTH.get(pid, 0)
            end = offset + 1 + width
            key = f'01{pid:02X}'
            if not width or pid not in expected or key in result or end > len(payload):
                return {}
            result[key] = '41 ' + payload[offset:end].hex(' ').upper() + '\r'
            offset = end
        return result
    except ValueError:
        return {}


class GaugePoll:
    COMMANDS = ('010C', '010E', '010F', '010D', '0105', '0104', '0111', '015C',
                '0142', '0103', '0144', '0110', '012F', '015E', '0140')
    def __init__(self, profile='zd8-mt'):
        self.profile = GAUGE_PROFILES[profile]
        self.COMMANDS = tuple(self.profile.oil_command if cmd == '015C' else cmd for cmd in self.COMMANDS)
        self.slot = 0
        self.status_supported = None
        self.slow_index = 0
        self.failures = [0, 0]

    def commands(self, now):
        group = self.slot
        self.slot ^= 1
        pids = ['010C', '010D', '0110']
        extra = []
        if group == 0:
            pids.extend(('0111', '0104', '0144' if self.status_supported is False else '0103'))
        else:
            pids.extend(('0144', '010E'))
            slow = ('010F', '0105', self.profile.oil_command, '0142', '012F', '015E', '0140')[self.slow_index]
            self.slow_index = (self.slow_index + 1) % 7
            (extra if slow in ('2101', '0140') else pids).append(slow)
        requests = pids if self.failures[group] >= 2 else ['01' + ''.join(c[2:] for c in pids)]
        return requests + extra

    def observe(self, command, response):
        if len(command) > 4:
            group = int(command[8:10] == '44')
            parts = split_group(command, response.decode('ascii', errors='replace'))
            ok = len(parts) > 3 and all(c in parts for c in ('010C', '010D', '0110'))
            self.failures[group] = 0 if ok else self.failures[group] + 1
            if '0103' in parts:
                self.status_supported = True
            return
        for line in response.decode('ascii', errors='replace').replace('>', '').splitlines():
            try:
                data = bytes.fromhex(line)
            except ValueError:
                continue
            if command == '0100' and len(data) >= 6 and data[:2] == b'\x41\x00':
                self.status_supported = bool(data[2] & 0x20)
            elif command == '0103' and len(data) >= 4 and data[:2] == b'\x41\x03':
                self.status_supported = True


class Zd8Poll(GaugePoll):
    """Backward-compatible default for v1.3 users/tests."""


async def capture_gauge(elm, args, recording):
    import asyncio
    import time
    poll = GaugePoll(args.profile)
    for _ in range(3):
        poll.observe('0100', await elm.query('0100', 'discovery'))
        await asyncio.sleep(.15)
    recording.emit('poll_plan', commands=list(poll.COMMANDS), schedule='brz-ab-v1',
                   vehicle=poll.profile.label, automatic=poll.profile.automatic,
                   oil_command=poll.profile.oil_command)
    start = time.monotonic()
    while not args.duration or time.monotonic() - start < args.duration:
        if not elm.client.is_connected:
            raise RuntimeError('ELM disconnected. Partial recording is saved.')
        slot = poll.slot
        commands = poll.commands(time.monotonic())
        recording.emit('poll_slot', slot=slot, commands=commands)
        for command in commands:
            if args.duration and time.monotonic() - start >= args.duration:
                return
            response = await elm.query(command, 'discovery' if command == '0140' else 'poll')
            poll.observe(command, response)
        await asyncio.sleep(.001)


capture_zd8 = capture_gauge
