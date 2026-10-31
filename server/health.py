"""Byte-level helpers for the watch's stored health data and controls (no Bluetooth needed).

Formats follow Gadgetbridge (service/devices/huami/operations/fetch/Fetch*Operation.java,
devices/huami/HuamiActivitySummaryParser.java, service/devices/huami/HuamiActivityDetailsParser.java,
HuamiSupport.java), checked against bytes captured from this GTR 2e (tests/fixtures/).
"""
import struct
from datetime import datetime

# HuamiFetchDataType codes -> contract type keys
FETCH_TYPES = {
    "manual_hr": 0x02,
    "workouts": 0x05,  # sports summaries; details (0x06) are fetched per workout
    "pai": 0x0D,
    "stress_manual": 0x12,
    "stress": 0x13,
    "spo2": 0x25,
    "spo2_sleep": 0x26,
    "statistics": 0x2C,
    "temperature": 0x2E,
    "sleep_respiratory_rate": 0x38,
    "resting_hr": 0x3A,
    "max_hr": 0x3D,
    "sleep_session": 0x48,
    "hrv": 0x49,
}
FETCH_SPORTS_DETAILS = 0x06

# On this watch these buffers are handed over once: the next fetch starts after the previous transfer even
# without the 0x03 ack (observed for temperature, stress, spo2_sleep and statistics). Store everything.
CONSUMED_ON_TRANSFER = {"temperature", "stress", "stress_manual", "spo2", "spo2_sleep", "statistics",
                        "sleep_respiratory_rate", "resting_hr", "max_hr", "sleep_session", "hrv"}


def _ts(b: bytes, offset: int = 0) -> int:
    return struct.unpack_from("<I", b, offset)[0]


def parse_manual_hr(data: bytes) -> list[dict]:
    """FetchHeartRateManualOperation: 6 bytes each = u32 unix ts, tz quarter-hours, hr."""
    out = []
    for i in range(0, len(data) - 5, 6):
        out.append({"ts": _ts(data, i), "value": data[i + 5], "tz_q": struct.unpack_from("<b", data, i + 4)[0]})
    return out


def parse_stress(data: bytes, start_ts: float) -> list[dict]:
    """FetchStressAutoOperation: one byte per minute from start, 0xff = no value. 0-39 relaxed ... 80-100 high."""
    base = int(start_ts) // 60 * 60
    return [{"ts": base + 60 * i, "value": b} for i, b in enumerate(data) if b != 0xFF]


def parse_stress_manual(data: bytes) -> list[dict]:
    """FetchStressManualOperation: 5 bytes each = u32 ts, stress."""
    return [{"ts": _ts(data, i), "value": data[i + 4]} for i in range(0, len(data) - 4, 5)]


def parse_pai(data: bytes) -> list[dict]:
    """FetchPaiOperation: 102-byte records (type 5 = current, 0 = from before a factory reset, skipped)."""
    out = []
    for i in range(0, len(data) - 101, 102):
        r = data[i:i + 102]
        if r[0] != 5:
            continue
        low, moderate, high = struct.unpack_from("<fff", r, 37)
        t_low, t_mod, t_high = struct.unpack_from("<hhh", r, 49)
        today, total = struct.unpack_from("<ff", r, 55)
        out.append({"ts": _ts(r, 1), "value": round(today, 2), "total": round(total, 2),
                    "low": round(low, 2), "moderate": round(moderate, 2), "high": round(high, 2),
                    "minutes_low": t_low, "minutes_moderate": t_mod, "minutes_high": t_high})
    return out


def parse_spo2(data: bytes) -> list[dict]:
    """SpO2 records. Version-2 formats from FetchSpo2NormalOperation (65-byte) and FetchSpo2SleepOperation
    (30-byte); this GTR 2e instead sends 6-byte records (u32 ts, spo2, extra) without a version byte."""
    out = []
    if data and data[0] == 2 and (len(data) - 1) % 65 == 0:
        for i in range(1, len(data), 65):
            raw = struct.unpack_from("<b", data, i + 4)[0]
            out.append({"ts": _ts(data, i), "value": raw + 128 if raw < 0 else raw, "auto": raw < 0})
    elif data and data[0] == 2 and (len(data) - 1) % 30 == 0:
        for i in range(1, len(data), 30):
            out.append({"ts": _ts(data, i), "value": data[i + 4], "duration": data[i + 5]})
    elif len(data) % 6 == 0:
        for i in range(0, len(data), 6):
            out.append({"ts": _ts(data, i), "value": data[i + 4], "extra": data[i + 5]})
    return [r for r in out if 50 <= r["value"] <= 100]


TEMP_INVALID = 0x5A5A


def parse_temperature(data: bytes, start_ts: float | None, end_ts: float) -> list[dict]:
    """FetchTemperatureOperation: 8 bytes per minute = i16 unk1, i16 skin temp x100, 0x5a5a, 0x5a5a.

    The watch's header start can be meaningless (it reported 2024-03-13 for a 40-day ring buffer), but the
    buffer always ends at the moment of the fetch, so samples are anchored to the end unless the header start
    is consistent with the sample count.
    """
    n = len(data) // 8
    end = int(end_ts) // 60 * 60
    if start_ts is None or abs((int(start_ts) // 60 * 60 + 60 * (n - 1)) - end) > 3600:
        base = end - 60 * (n - 1)
    else:
        base = int(start_ts) // 60 * 60
    out = []
    for i in range(n):
        unk1, temp, _, _ = struct.unpack_from("<hhHH", data, 8 * i)
        if temp in (TEMP_INVALID, 0x7FFF) or not 1000 <= temp <= 4500:
            continue
        out.append({"ts": base + 60 * i, "value": temp / 100,
                    "sensor2": unk1 / 100 if 1000 <= unk1 <= 4500 else None})
    return out


WORKOUT_TYPES = {1: "Outdoor running", 2: "Treadmill", 3: "Walking", 4: "Cycling", 5: "Exercise",
                 6: "Swimming", 7: "Open water swimming", 8: "Indoor cycling", 9: "Elliptical trainer",
                 10: "Climbing", 15: "Hiking", 0x12: "Soccer", 0x15: "Jump rope", 0x17: "Rowing machine",
                 0x34: "Strength training", 0x3C: "Yoga", 0x4E: "Cricket", 0x55: "Basketball",
                 0x59: "Table tennis", 0x5C: "Badminton"}


def parse_workout_summary(data: bytes) -> dict | None:
    """HuamiActivitySummaryParser.parseBinaryData for version >= 512 (this watch sends 515)."""
    if len(data) < 24:
        return None
    version, kind = struct.unpack_from("<HH", data, 0)
    start, end = struct.unpack_from("<II", data, 4)
    pos = 24
    if version == 516:
        pos += 4
    if version < 512 or version == 519 or len(data) < pos + 116:
        return {"version": version, "start": start, "end": end, "type": WORKOUT_TYPES.get(kind, f"Type {kind}"),
                "type_code": kind, "duration_s": end - start}
    steps, active_s = struct.unpack_from("<Ii", data, pos)
    calories, distance = struct.unpack_from("<ff", data, pos + 24)
    max_speed, min_speed, avg_speed = struct.unpack_from("<fff", data, pos + 52)
    max_cad, min_cad, avg_cad = struct.unpack_from("<fff", data, pos + 76)
    avg_hr, _pace, _stride, max_hr = struct.unpack_from("<hhhh", data, pos + 108)
    return {
        "version": version, "start": start, "end": end, "type": WORKOUT_TYPES.get(kind, f"Type {kind}"),
        "type_code": kind, "duration_s": end - start, "active_s": active_s, "steps": steps,
        "calories": round(calories), "distance_m": round(distance), "avg_hr": avg_hr or None,
        "max_hr": max_hr or None, "avg_speed_ms": round(avg_speed, 2) if avg_speed > 0 else None,
        "avg_cadence": round(avg_cad * 60) if avg_cad > 0 else None,
    }


def parse_workout_details(data: bytes) -> list[dict]:
    """HuamiActivityDetailsParser: 8-byte records = type, 1-byte wrapping time offset, 6 payload bytes.
    Returns the heart-rate track as [{"t": seconds from start, "hr": bpm}]."""
    track, total, last = [], 0, 0
    for i in range(0, len(data) - 7, 8):
        kind, off = data[i], data[i + 1]
        if last <= off:
            total += off - last
            last = off
        else:
            total += off
            last = off
        if kind == 1:  # TYPE_HR
            v = data[i + 2:i + 8]
            if not any(v[1:]):
                if v[0]:
                    track.append({"t": total, "hr": v[0]})
            else:
                for t, hr in ((v[0], v[1]), (v[2], v[3]), (v[4], v[5])):
                    if hr:
                        track.append({"t": t, "hr": hr})
    return track


# ---- live data ------------------------------------------------------------------------------------------------

def parse_realtime_steps(data: bytes) -> dict | None:
    """Char 0x0007, 13 bytes: 0x0c, u32 steps, u32 distance (m), u32 calories (kcal)."""
    if len(data) < 13:
        return None
    steps, distance, calories = struct.unpack_from("<III", data, 1)
    return {"steps": steps, "distance_m": distance, "calories": calories}


DEVICE_EVENTS = {  # HuamiDeviceEvent.java
    0x01: "fell_asleep", 0x02: "woke_up", 0x03: "steps_goal_reached", 0x04: "button_pressed",
    0x06: "start_nonwear", 0x07: "call_reject", 0x08: "find_phone_start", 0x09: "call_ignore",
    0x0A: "alarm_toggled", 0x0B: "button_pressed_long", 0x0E: "tick_30min", 0x0F: "find_phone_stop",
    0x10: "silent_mode", 0x14: "workout_starting", 0x16: "mtu_request", 0x1A: "alarm_changed",
    0xFE: "music_control",
}
MUSIC_COMMANDS = {0xE0: "music_open", 0xE1: "music_close", 0x00: "play", 0x01: "pause", 0x03: "next",
                  0x04: "previous", 0x05: "volume_up", 0x06: "volume_down"}


def parse_device_event(data: bytes) -> str:
    if not data:
        return "empty"
    name = DEVICE_EVENTS.get(data[0], f"unknown_{data[0]:02x}")
    if data[0] == 0xFE and len(data) > 1:
        name += ":" + MUSIC_COMMANDS.get(data[1], f"{data[1]:02x}")
    return name


# ---- controls -------------------------------------------------------------------------------------------------

CURRENT_TIME = "00002a2b-0000-1000-8000-00805f9b34fb"
REALTIME_STEPS = "00000007-0000-3512-2118-0009af100700"
DEVICE_EVENT = "00000010-0000-3512-2118-0009af100700"
CONFIG = "00000003-0000-3512-2118-0009af100700"
CHUNKED = "00000020-0000-3512-2118-0009af100700"


def current_time_bytes(now: datetime) -> bytes:
    """HuamiSupport.setCurrentTime: year u16, month, day, h, m, s, ISO weekday, fractions, 0, tz quarter-hours."""
    tz_q = int(now.utcoffset().total_seconds() // 900)
    return now.year.to_bytes(2, "little") + bytes(
        [now.month, now.day, now.hour, now.minute, now.second, now.isoweekday(), 0, 0, tz_q & 0xFF])


def parse_current_time(data: bytes) -> str | None:
    if len(data) < 7:
        return None
    try:
        return datetime(int.from_bytes(data[0:2], "little"), data[2], data[3], data[4], data[5], data[6]).isoformat()
    except ValueError:
        return None


ICON_APP = 11  # HuamiIcon.APP_11
ALERT_SMS, ALERT_CUSTOM_HUAMI = 5, 0xFA  # AlertCategory


def notification_payload(title: str, body: str, kind: str = "message", app: str = "Dashboard") -> bytes:
    """HuamiSupport.onNotification (chunked branch) and onSetCallStateNew."""
    title = (title or " ")[:32]
    body = (body or " ")[:512]
    if kind == "call":
        return bytes([3, 0, 0, 0, 0, 0]) + title.encode() + bytes([0, 0, 0, 2])
    message = f"{title}\0{body}\n\n".encode()[:225]
    if kind == "generic":
        return bytes([ALERT_SMS, 1]) + message + b"\0 \0"
    return bytes([ALERT_CUSTOM_HUAMI, 1, ICON_APP]) + message + f"\0{app}\0".encode()


# Gadgetbridge keeps mMTU at 23 unless the watch requests more; one large write (Windows negotiates 247)
# made this watch drop the connection right after showing the notification.
CHUNK_MTU = 23


def chunked_writes(payload: bytes, mtu: int, kind: int = 0) -> list[bytes]:
    """HuamiSupport.writeToChunkedOld: [0, flags|type, counter] + up to (mtu - 6) bytes per write."""
    size = max(17, mtu - 6)
    chunks = [payload[i:i + size] for i in range(0, len(payload), size)] or [b""]
    out = []
    for n, part in enumerate(chunks):
        last = n == len(chunks) - 1
        flags = (0x80 | (0x40 if n == 0 else 0)) if last else (0x40 if n else 0)
        out.append(bytes([0, flags | kind, n & 0xFF]) + part)
    return out


# Watch settings. Huami watches don't report these back, so the value shown is the last one written here.
# target: "config" = char 0x0003 (acked with 0x10 + echo), "hr" = heart-rate control point 0x2A39.
SETTINGS = [
    {"key": "hr_interval", "label": "All-day heart rate", "type": "enum", "target": "hr",
     "options": [{"value": 0, "label": "Off"}, {"value": 1, "label": "Every minute"},
                 {"value": 5, "label": "Every 5 minutes"}, {"value": 10, "label": "Every 10 minutes"},
                 {"value": 30, "label": "Every 30 minutes"}]},
    {"key": "hr_sleep_detection", "label": "Heart rate during sleep", "type": "bool", "target": "hr"},
    {"key": "hr_activity_monitoring", "label": "Heart rate during activity", "type": "bool", "target": "config"},
    {"key": "stress_monitoring", "label": "All-day stress", "type": "bool", "target": "config"},
    {"key": "hr_alert", "label": "High heart rate alert", "type": "bool", "target": "config"},
    {"key": "hr_alert_threshold", "label": "High heart rate threshold", "type": "int", "target": "config",
     "min": 100, "max": 180},
    {"key": "expose_hr", "label": "Share heart rate with other apps (BLE heart rate broadcast)", "type": "bool",
     "target": "config"},
    {"key": "goal_notification", "label": "Step goal notification", "type": "bool", "target": "config"},
    {"key": "lift_wrist", "label": "Lift wrist to wake", "type": "bool", "target": "config"},
    {"key": "inactivity_warnings", "label": "Move reminders (9:00-21:00, after 60 min)", "type": "bool",
     "target": "config"},
    {"key": "dnd", "label": "Do not disturb", "type": "enum", "target": "config",
     "options": [{"value": "off", "label": "Off"}, {"value": "auto", "label": "Automatic (while asleep)"}]},
    {"key": "time_24h", "label": "24-hour clock", "type": "bool", "target": "config"},
    {"key": "metric_units", "label": "Metric units", "type": "bool", "target": "config"},
]


def setting_command(key: str, value, current: dict | None = None) -> tuple[str, bytes]:
    """Bytes for one setting, from HuamiService / HuamiSupport.set*. Returns (target, bytes)."""
    current = current or {}
    b = lambda v: 1 if v else 0  # noqa: E731
    if key == "hr_interval":
        return "hr", bytes([0x14, int(value)])
    if key == "hr_sleep_detection":
        return "hr", bytes([0x15, 0x00, b(value)])
    if key == "hr_activity_monitoring":
        return "config", bytes([0x06, 0x22, 0x00, b(value)])
    if key == "stress_monitoring":
        return "config", bytes([0xFE, 0x06, 0x00, b(value)])
    if key in ("hr_alert", "hr_alert_threshold"):
        on = value if key == "hr_alert" else current.get("hr_alert", True)
        thr = value if key == "hr_alert_threshold" else current.get("hr_alert_threshold", 150)
        return "config", bytes([0x06, 0x1A, 0x00, b(on), int(thr)])
    if key == "expose_hr":
        return "config", bytes([0x06, 0x1F, 0x00, b(value)])
    if key == "goal_notification":
        return "config", bytes([0x06, 0x06, 0x00, b(value)])
    if key == "lift_wrist":
        return "config", (bytes([0x06, 0x05, 0x00, 0x01, 0, 0, 0, 0]) if value else bytes([0x06, 0x05, 0x00, 0x00]))
    if key == "inactivity_warnings":
        if value:  # COMMAND_ENABLE_INACTIVITY_WARNINGS with threshold 60 min, 09:00-21:00
            return "config", bytes([0x08, 0x01, 0x3C, 0x00, 0x09, 0x00, 0x15, 0x00, 0x00, 0x00, 0x00, 0x00])
        return "config", bytes([0x08, 0x00, 0x3C, 0x00, 0x04, 0x00, 0x15, 0x00, 0x00, 0x00, 0x00, 0x00])
    if key == "dnd":
        return "config", bytes([0x09, 0x83 if value == "auto" else 0x82])
    if key == "time_24h":
        return "config", bytes([0x06, 0x02, 0x00, b(value)])
    if key == "metric_units":
        return "config", bytes([0x06, 0x03, 0x00, 0x00 if value else 0x01])
    raise KeyError(key)


def validate_setting(key: str, value):
    spec = next((s for s in SETTINGS if s["key"] == key), None)
    if spec is None:
        raise KeyError(f"Unknown setting {key}")
    if spec["type"] == "bool":
        return bool(value)
    if spec["type"] == "int":
        v = int(value)
        if not spec["min"] <= v <= spec["max"]:
            raise ValueError(f"{key} must be between {spec['min']} and {spec['max']}")
        return v
    allowed = [o["value"] for o in spec["options"]]
    if value not in allowed:
        raise ValueError(f"{key} must be one of {allowed}")
    return value
