"""Byte-level helpers for the Huami BLE protocol (no Bluetooth needed)."""
from datetime import datetime, timedelta, timezone

from Crypto.Cipher import AES

AUTH = "00000009-0000-3512-2118-0009af100700"
BATTERY = "00000006-0000-3512-2118-0009af100700"
RAW_CTRL = "00000001-0000-3512-2118-0009af100700"
RAW_DATA = "00000002-0000-3512-2118-0009af100700"
HR_MEASURE = "00002a37-0000-1000-8000-00805f9b34fb"
HR_CONTROL = "00002a39-0000-1000-8000-00805f9b34fb"
DEVICE_INFO = {
    "serial": "00002a25-0000-1000-8000-00805f9b34fb",
    "hardware": "00002a27-0000-1000-8000-00805f9b34fb",
    "software": "00002a28-0000-1000-8000-00805f9b34fb",
    "system_id": "00002a23-0000-1000-8000-00805f9b34fb",
    "pnp_id": "00002a50-0000-1000-8000-00805f9b34fb",
}

AUTH_REQUEST_RANDOM = bytes([0x82, 0x00, 0x02, 0x01, 0x00])
AUTH_RANDOM_OK = b"\x10\x82\x01"
AUTH_OK = b"\x10\x83\x01"

HR_STOP_MANUAL = bytes([0x15, 0x02, 0x00])
HR_START_CONTINUOUS = bytes([0x15, 0x01, 0x01])
HR_STOP_CONTINUOUS = bytes([0x15, 0x01, 0x00])
HR_PING = bytes([0x16])


def auth_response(key: bytes, random_reply: bytes) -> bytes:
    """Build the `83 00 + AES-ECB(random)` answer to the watch's random-number reply."""
    if random_reply[:3] != AUTH_RANDOM_OK or len(random_reply) < 19:
        raise ValueError(f"unexpected random reply {random_reply.hex()}")
    return bytes([0x83, 0x00]) + AES.new(key, AES.MODE_ECB).encrypt(random_reply[3:19])


def parse_hr(data: bytes) -> int:
    """Standard Heart Rate Measurement: flags bit 0 selects u8 or u16 bpm."""
    return int.from_bytes(data[1:3], "little") if data[0] & 1 else data[1]


def _parse_time(b: bytes) -> str | None:
    """year u16 LE, month, day, hour, minute, second, tz in quarter hours."""
    if len(b) < 7:
        return None
    year = int.from_bytes(b[0:2], "little")
    if year == 0:
        return None
    tz = timezone(timedelta(minutes=15 * int.from_bytes(b[7:8] or b"\x00", "little", signed=True)))
    try:
        return datetime(year, b[2], b[3], b[4], b[5], b[6], tzinfo=tz).isoformat()
    except ValueError:
        return None


def parse_battery(data: bytes, read_at: float) -> dict:
    return {
        "level": data[1],
        "charging": len(data) > 2 and data[2] == 1,
        "last_charge_a": _parse_time(data[3:11]),
        "last_charge_b": _parse_time(data[11:19]),
        "raw": data.hex(),
        "read_at": read_at,
    }


def decode_info(data: bytes) -> str:
    text = data.rstrip(b"\x00")
    if text and all(32 <= c < 127 for c in text):
        return text.decode()
    return data.hex()


# ---- raw sensor stream (Gadgetbridge Huami2021Service.CMD_RAW_SENSOR_*) ----

# The second byte of the start command is a sensor bitmask (found by probing; Gadgetbridge only uses 0x03).
# Each bit enables one packet type on RAW_DATA: 0x01 accel (0x00), 0x02 PPG (0x01), 0x08 GPS NMEA text (0x03),
# 0x10 gyroscope (0x04), 0x20 microphone as Opus frames (0x05, see audio.py), 0x40 magnetometer (0x06).
RAW_SENSORS = {"accel": 0x01, "ppg": 0x02, "gps": 0x08, "gyro": 0x10, "mic": 0x20, "mag": 0x40}
RAW_DEFAULT = ("accel", "ppg")


def raw_mask(sensors) -> int:
    mask = 0
    for name in sensors:
        if name not in RAW_SENSORS:
            raise ValueError(f"unknown sensor {name!r}; choose from {sorted(RAW_SENSORS)}")
        mask |= RAW_SENSORS[name]
    return mask or 0x03


def raw_start_cmds(mask: int = 0x03) -> tuple[bytes, bytes, bytes]:
    return bytes([0x01, mask, 0x19]), bytes([0x01, mask, 0x00, 0x00, 0x00, 0x19]), bytes([0x02])


RAW_START = raw_start_cmds(0x03)
RAW_STOP = bytes([0x03])
ACCEL_ONE_G = 4100  # raw accelerometer units per g
RAW_RATE_HZ = 25.0


def parse_raw(data: bytes) -> tuple[str, list]:
    """Decode one raw-sensor packet: accel (g), ppg (u16), gyro/mag (raw int16 xyz), gps ([nmea text]), mic ([opus bytes]) or other."""
    if len(data) < 2:
        return "other", []
    body = data[2:]
    if data[0] == 0x00:
        n = len(body) // 6
        return "accel", [
            tuple(round(int.from_bytes(body[i + k:i + k + 2], "little", signed=True) / ACCEL_ONE_G, 3) for k in (0, 2, 4))
            for i in range(0, n * 6, 6)
        ]
    if data[0] == 0x01:
        return "ppg", [int.from_bytes(body[i:i + 2], "little") for i in range(0, len(body) - 1, 2)]
    if data[0] in (0x04, 0x06):
        # Gyroscope and magnetometer: int16 x, y, z triplets in raw sensor units (scale not documented).
        n = len(body) // 6
        return ("gyro" if data[0] == 0x04 else "mag"), [
            tuple(int.from_bytes(body[i + k:i + k + 2], "little", signed=True) for k in (0, 2, 4))
            for i in range(0, n * 6, 6)
        ]
    if data[0] == 0x03:
        return "gps", [body.decode("ascii", "replace")]
    if data[0] == 0x05:
        return "mic", [bytes(data)]  # whole packet: audio.MicStream uses the counter
    return "other", []


# ---- activity history fetch (Gadgetbridge AbstractFetchOperation / FetchActivityOperation) ----

ACT_CTRL = "00000004-0000-3512-2118-0009af100700"
ACT_DATA = "00000005-0000-3512-2118-0009af100700"
FETCH_ACTIVITY = 0x01
FETCH_GO = bytes([0x02])
# 0x03 is the ack that deletes fetched data from the watch: deliberately never sent.
ACTIVITY_SAMPLE_SIZE = 8  # AmazfitGTR2Support.getActivitySampleSize()


def activity_time_bytes(t: datetime) -> bytes:
    """year u16 LE, month, day, hour, minute, 0x00, tz in quarter hours (t must be timezone-aware)."""
    tz_q = int(t.utcoffset().total_seconds() // 900)
    return t.year.to_bytes(2, "little") + bytes([t.month, t.day, t.hour, t.minute, 0x00, tz_q & 0xFF])


def fetch_command(kind: int, since: datetime) -> bytes:
    return bytes([0x01, kind]) + activity_time_bytes(since)


def parse_fetch_start(reply: bytes) -> tuple[int, float | None]:
    """`10 01 01` + u32 sample count + 8-byte start time -> (count, start as unix seconds)."""
    if reply[:3] != b"\x10\x01\x01":
        raise RuntimeError(f"Watch refused the history fetch ({reply.hex()})")
    count = int.from_bytes(reply[3:7], "little")
    start = _parse_time(reply[7:15])
    return count, (datetime.fromisoformat(start).timestamp() if start and count else None)


def parse_activity(data: bytes, start_ts: float) -> list[dict]:
    """8 bytes per minute: kind, intensity, steps, hr, unknown1, sleep, deep, rem."""
    base = int(start_ts) // 60 * 60
    rows = []
    for i in range(0, len(data) - ACTIVITY_SAMPLE_SIZE + 1, ACTIVITY_SAMPLE_SIZE):
        s = data[i:i + ACTIVITY_SAMPLE_SIZE]
        rows.append({
            "ts": base + 60 * (i // ACTIVITY_SAMPLE_SIZE), "kind": s[0], "intensity": s[1], "steps": s[2],
            "hr": s[3], "unknown1": s[4], "sleep": s[5], "deep": s[6], "rem": s[7],
        })
    return rows


# Raw kinds from Gadgetbridge devices/huami/HuamiConst.java (TYPE_*), applied after `kind & 0x0f` as in
# devices/miband/MiBand2SampleProvider.postprocess(), which is the provider HuamiCoordinator uses for the GTR 2e.
# 0 (NO_CHANGE) and 10 (IGNORE) repeat the previous minute's kind.
KIND_STATE = {1: "active", 2: "active", 3: "not_worn", 4: "active", 6: "charging", 9: "sleep_light",
              11: "sleep_deep", 12: "active"}
KIND_REPEAT = {0, 10}
REM_THRESHOLD = 55  # HuamiExtendedSampleProvider fallback: rem & 127 > 55 counts as REM


def activity_states(rows: list[dict], prev_kind: int | None = None) -> list[str]:
    """Map each minute to a state. prev_kind is the last valid (masked) kind before rows[0]."""
    states, last = [], prev_kind
    for r in rows:
        k = r["kind"] & 0x0F
        if k in KIND_REPEAT:
            k = last
        else:
            last = k
        state = KIND_STATE.get(k) if k is not None else None
        if state == "sleep_light" and (r["rem"] & 127) > REM_THRESHOLD:
            state = "sleep_rem"
        if state is None:
            state = "idle" if valid_hr(r["hr"]) else "unknown"
        states.append(state)
    return states


def valid_hr(hr: int) -> bool:
    return 0 < hr < 255


# ---- find device (Gadgetbridge HuamiSupport.sendFindDeviceCommand) ----

ALERT_LEVEL = "00002a06-0000-1000-8000-00805f9b34fb"
ALERT_FIND = bytes([0x03])
ALERT_OFF = bytes([0x00])
