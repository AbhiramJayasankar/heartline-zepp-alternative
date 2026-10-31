from Crypto.Cipher import AES

from server import protocol as p
from server.store import Store, hr_stats

BATTERY_RAW = bytes.fromhex("0f4400ea070a180a322316ea070a1f040a3a164f")


def test_battery_parse():
    b = p.parse_battery(BATTERY_RAW, 123.0)
    assert b["level"] == 68
    assert b["charging"] is False
    assert b["last_charge_a"].startswith("2026-10-24T10:50:35")
    assert b["last_charge_b"].startswith("2026-10-31T04:10:58")
    assert b["last_charge_a"].endswith("+05:30")  # tz byte 0x16 = 22 quarter hours
    assert b["raw"] == BATTERY_RAW.hex() and b["read_at"] == 123.0


def test_battery_charging_and_short_packet():
    b = p.parse_battery(bytes([0x0F, 0x20, 0x01]), 0)
    assert b["level"] == 32 and b["charging"] is True
    assert b["last_charge_a"] is None and b["last_charge_b"] is None


def test_hr_parse_u8_and_u16():
    assert p.parse_hr(bytes([0x00, 83])) == 83
    assert p.parse_hr(bytes([0x01, 0x2C, 0x01])) == 300


def test_auth_response():
    key = bytes(range(16))
    rnd = bytes(range(100, 116))
    out = p.auth_response(key, b"\x10\x82\x01" + rnd)
    assert out[:2] == b"\x83\x00"
    assert out[2:] == AES.new(key, AES.MODE_ECB).encrypt(rnd)


def test_auth_response_rejects_bad_reply():
    import pytest

    with pytest.raises(ValueError):
        p.auth_response(bytes(16), b"\x10\x82\x04")


def test_decode_info():
    assert p.decode_info(b"V1.7.3.1\x00") == "V1.7.3.1"
    assert p.decode_info(b"\x01\x02") == "0102"


def test_store_round_trip():
    s = Store(":memory:")
    s.add_hr(100.0, 0)
    s.add_hr(101.0, 70)
    s.add_hr(102.0, 90)
    samples = s.hr_since(100.5)
    assert samples == [{"ts": 101.0, "bpm": 70}, {"ts": 102.0, "bpm": 90}]
    assert hr_stats(samples) == {"min": 70, "max": 90, "avg": 80.0, "count": 2}
    assert hr_stats([]) == {"min": None, "max": None, "avg": None, "count": 0}
    s.add_event(1.0, "info", "a")
    s.add_event(2.0, "warn", "b")
    assert [e["msg"] for e in s.events(10)] == ["b", "a"]
    assert len(s.events(1)) == 1
