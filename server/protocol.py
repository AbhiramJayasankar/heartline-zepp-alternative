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
