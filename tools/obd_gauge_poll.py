"""Four BRZ 6-slot schedules from elm327_ble_client.c.

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


class GaugePoll:
    COMMANDS = ('010C', '010F', '010D', '0105', '0104', '0111', '015C',
                '0142', '0103', '0144', '0110', '012F', '015E', '0140')
    def __init__(self, profile='zd8-mt'):
        self.profile = GAUGE_PROFILES[profile]
        self.COMMANDS = tuple(self.profile.oil_command if cmd == '015C' else cmd for cmd in self.COMMANDS)
        self.slot = 0
        self.status_supported = None
        self.slow_index = 0
        self.status_probes = 0

    def commands(self, now):
        slot, commands = self.slot, []
        if slot == 5:
            commands.append(('010F', '0105', self.profile.oil_command, '0142', '012F', '015E', '0140')[self.slow_index])
            self.slow_index = (self.slow_index + 1) % 7
        elif slot in (0, 1, 2, 4):
            commands.append({0: '0110', 1: '0104', 2: '0111', 4: '0144'}[slot])
        elif slot == 3:
            if self.status_supported is not False:
                if self.status_supported is None and self.status_probes >= 3:
                    self.status_supported = False
                else:
                    self.status_probes += self.status_supported is None
                    commands.append('0103')
        commands.append('010D' if slot % 2 != 0 else '010C')
        self.slot = (slot + 1) % 6
        return commands

    def observe(self, command, response):
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
    recording.emit('poll_plan', commands=list(poll.COMMANDS), schedule='brz-6slot-v6',
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
