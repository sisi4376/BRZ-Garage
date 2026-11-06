"""Standard PID decoding and timestamp-based trip estimates for OBD Studio."""
from obd_recorder import normalize

FORMULAS = {
    '010C': ('发动机转速', 'rpm', 2, lambda b: int.from_bytes(b, 'big') / 4),
    '010D': ('车速', 'km/h', 1, lambda b: b[0]),
    '0105': ('冷却液温度', '°C', 1, lambda b: b[0] - 40),
    '015C': ('机油温度', '°C', 1, lambda b: b[0] - 40),
    '0111': ('节气门', '%', 1, lambda b: b[0] * 100 / 255),
    '0104': ('发动机负荷', '%', 1, lambda b: b[0] * 100 / 255),
    '012F': ('燃油余量', '%', 1, lambda b: b[0] * 100 / 255),
    '0142': ('ECU 电压', 'V', 2, lambda b: int.from_bytes(b, 'big') / 1000),
    '0110': ('空气流量', 'g/s', 2, lambda b: int.from_bytes(b, 'big') / 100),
    '015E': ('燃油流量', 'L/h', 2, lambda b: int.from_bytes(b, 'big') / 20),
    '0144': ('指令空燃比 λ', '', 2, lambda b: int.from_bytes(b, 'big') / 32768),
    '01A6': ('ECU 总里程', 'km', 4, lambda b: int.from_bytes(b, 'big') / 10),
}


def decode(command, response):
    command = normalize(command)
    if command not in FORMULAS:
        return None
    name, unit, size, formula = FORMULAS[command]
    for line in response.splitlines():
        try:
            data = bytes.fromhex(line.strip())
        except ValueError:
            continue
        if len(data) == size + 2 and data[:2] == bytes.fromhex('41' + command[2:]):
            if command == '01A6' and data[2:] == b'\xff' * 4:
                return None
            return name, formula(data[2:]), unit
    return None


class TripMetrics:
    """Zero-order integration over known intervals, never across expired samples.

    Fuel fallback uses the firmware's gasoline density/AFR assumptions, without
    claiming parity with its vehicle-specific overrun-cut heuristics.
    """
    FRESH_SECONDS = 5.0

    def __init__(self):
        self.samples = {}
        self.t = None
        self.distance_km = self.fuel_l = 0.0
        self.paired_distance_km = self.paired_fuel_l = 0.0
        self.distance_seconds = self.fuel_seconds = self.paired_seconds = 0.0
        self.has_speed = self.has_fuel = self.estimated = False

    def get(self, pid, t):
        sample = self.samples.get(pid)
        if sample and 0 <= t - sample[1] < self.FRESH_SECONDS:
            return sample[0]
        return None

    def rate(self, t):
        direct = self.get('015E', t)
        if direct is not None:
            return direct, 'ECU'
        rpm = self.get('010C', t)
        if rpm == 0:
            return 0.0, '发动机停止'
        maf = self.get('0110', t)
        if rpm is None or maf is None or maf <= 0:
            return None, '等待燃油数据'
        lam = self.get('0144', t)
        afr = lam * 14.7 if lam is not None else 14.7
        if not 8 <= afr <= 22:
            afr = 14.7
        return maf / afr / 745 * 3600, 'MAF 汽油估算'

    def advance(self, t):
        if self.t is None:
            self.t = t
            return
        if t < self.t:
            raise ValueError('Trip clock moved backwards; reset at replay loop boundaries.')
        boundaries = sorted({self.t, t} | {stamp + self.FRESH_SECONDS
            for _, stamp in self.samples.values() if self.t < stamp + self.FRESH_SECONDS < t})
        for start, end in zip(boundaries, boundaries[1:]):
            middle = (start + end) / 2
            speed = self.get('010D', middle)
            rate, source = self.rate(middle)
            dt = end - start
            if speed is not None:
                self.distance_km += speed * dt / 3600
                self.distance_seconds += dt
            if rate is not None:
                self.fuel_l += rate * dt / 3600
                self.fuel_seconds += dt
                self.estimated |= source == 'MAF 汽油估算'
            if speed is not None and rate is not None:
                self.paired_distance_km += speed * dt / 3600
                self.paired_fuel_l += rate * dt / 3600
                self.paired_seconds += dt
        self.t = t

    def update(self, command, value, t, sampled_at=None):
        self.advance(t)
        command = normalize(command)
        if value is None:
            self.samples.pop(command, None)
        else:
            self.samples[command] = (value, t if sampled_at is None else sampled_at)
        self.has_speed |= self.get('010D', t) is not None
        rate, source = self.rate(t)
        self.has_fuel |= rate is not None
        self.estimated |= source == 'MAF 汽油估算'

    def snapshot(self):
        t = self.t if self.t is not None else 0
        speed = self.get('010D', t)
        rate, source = self.rate(t)
        instant = None if speed is None or rate is None else (rate if speed < 3 else rate * 100 / speed)
        return dict(distance_km=self.distance_km if self.has_speed else None,
                    fuel_l=self.fuel_l if self.has_fuel else None,
                    fuel_rate_lph=rate, fuel_source=source,
                    instant=instant, instant_unit='L/h' if speed is not None and speed < 3 else 'L/100km',
                    average_l100km=self.paired_fuel_l * 100 / self.paired_distance_km
                        if self.paired_distance_km >= .01 else None,
                    distance_seconds=self.distance_seconds, fuel_seconds=self.fuel_seconds,
                    paired_seconds=self.paired_seconds, includes_maf_estimate=self.estimated)
