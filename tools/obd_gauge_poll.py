"""ZD8 6MT production 14-slot schedule from elm327_ble_client.c.

This mirrors the request policy, not ESP32 task/radio timing or recovery logic.
"""


class Zd8Poll:
    COMMANDS = ('010C', '010F', '010D', '0105', '0104', '0111', '015C',
                '0142', '0103', '0144', '0110', '012F', '0140', '015E')
    def __init__(self):
        self.slot = 0
        self.last = {}
        self.status_supported = self.rate_supported = None
        self.status_probes = self.rate_probes = self.level_probes = 0
        self.level_supported = False

    def due(self, key, now, interval):
        previous = self.last.get(key)
        if previous is not None and now - previous < interval:
            return False
        self.last[key] = now
        return True

    def commands(self, now):
        slot, commands = self.slot, []
        if slot == 0:
            commands.append('010C')
        elif slot in (1, 3, 6, 7):
            command = {1: '010F', 3: '0105', 6: '015C', 7: '0142'}[slot]
            if self.due(command, now, 5):
                commands.append(command)
        elif slot == 2:
            commands.append('010D')
        elif slot in (4, 5, 9, 10):
            command = {4: '0104', 5: '0111', 9: '0144', 10: '0110'}[slot]
            if self.due(command, now, .5):
                commands.append(command)
        elif slot == 8 and self.due('0103', now, .5):
            if self.status_supported is not False:
                if self.status_supported is None and self.status_probes >= 3:
                    self.status_supported = False
                else:
                    self.status_probes += self.status_supported is None
                    commands.append('0103')
        # Slot 11 is empty in the production firmware (A4/A6 disabled).
        elif slot == 12:
            if (self.level_supported or self.level_probes < 3) and self.due('012F', now, 30):
                self.level_probes += not self.level_supported
                commands.append('012F')
        elif slot == 13 and self.due('rate', now, .5):
            if self.rate_supported is None and self.rate_probes < 3:
                self.rate_probes += 1
                commands.append('0140')
            elif self.rate_supported:
                commands.append('015E')
        if slot != 0:
            commands.append('010D' if slot % 4 == 0 else '010C')
        self.slot = (slot + 1) % 14
        return commands

    def observe(self, command, response):
        for line in response.decode('ascii', errors='replace').replace('>', '').splitlines():
            try:
                data = bytes.fromhex(line)
            except ValueError:
                continue
            if command == '0100' and len(data) >= 6 and data[:2] == b'\x41\x00':
                self.status_supported = bool(data[2] & 0x20)
            elif command == '0140' and len(data) >= 6 and data[:2] == b'\x41\x40':
                self.rate_supported = bool(data[5] & 4)
            elif command == '0103' and len(data) >= 4 and data[:2] == b'\x41\x03':
                self.status_supported = True
            elif command == '012F' and len(data) >= 3 and data[:2] == b'\x41\x2f':
                self.level_supported = True
        if command == '0140' and self.rate_probes >= 3 and self.rate_supported is None:
            self.rate_supported = False


async def capture_zd8(elm, args, recording):
    import asyncio
    import time
    poll = Zd8Poll()
    for _ in range(3):
        poll.observe('0100', await elm.query('0100', 'discovery'))
        await asyncio.sleep(.15)
    recording.emit('poll_plan', commands=list(poll.COMMANDS), schedule='zd8-14slot-v1')
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
