from datetime import datetime, timedelta, timezone

from server import protocol as p
from server.store import Store

IST = timezone(timedelta(hours=5, minutes=30))


def test_parse_accel_packet():
    pkt = bytes.fromhex("0000aefe780041f0")  # type 0, counter 0, one xyz triplet
    kind, samples = p.parse_raw(pkt)
    assert kind == "accel"
    assert samples == [(round(-338 / 4100, 3), round(120 / 4100, 3), round(-4031 / 4100, 3))]


def test_parse_ppg_packets_22_and_24_bytes():
    p22 = bytes.fromhex("01019f8c7f8c728c648c7c8c858c8f8c868c8b8c828c")
    kind, samples = p.parse_raw(p22)
    assert kind == "ppg" and len(samples) == 10 and samples[0] == 0x8C9F
    p24 = bytes.fromhex("0102deaa") + bytes(20)
    assert len(p.parse_raw(p24)[1]) == 11


def test_parse_other_packet():
    assert p.parse_raw(bytes.fromhex("070000f781c22500000000")) == ("other", [])


def test_activity_time_bytes_and_fetch_command():
    t = datetime(2026, 10, 31, 2, 28, tzinfo=IST)
    assert p.activity_time_bytes(t).hex() == "ea070a1f021c0016"
    assert p.fetch_command(0x01, t).hex() == "0101ea070a1f021c0016"


def test_parse_fetch_start():
    count, start = p.parse_fetch_start(bytes.fromhex("1001014a020000ea070a1f021c0016"))
    assert count == 586
    assert start == datetime(2026, 10, 31, 2, 28, tzinfo=IST).timestamp()
    assert p.parse_fetch_start(bytes.fromhex("10010100000000ea070a1f021c0016")) == (0, None)


def test_parse_activity_samples():
    data = bytes([6, 0, 0, 255, 5, 31, 128, 128, 240, 8, 3, 57, 5, 62, 159, 166])
    rows = p.parse_activity(data, 1000.0)
    assert [r["ts"] for r in rows] == [960, 1020]
    assert rows[1] == {"ts": 1020, "kind": 240, "intensity": 8, "steps": 3, "hr": 57,
                       "unknown1": 5, "sleep": 62, "deep": 159, "rem": 166}


def test_activity_states():
    row = lambda kind, hr=60, rem=0: {"kind": kind, "hr": hr, "rem": rem}
    rows = [row(240), row(6, 255), row(240, 255), row(3, 255), row(9), row(9, rem=128 + 60), row(11), row(1), row(7), row(7, 255)]
    assert p.activity_states(rows) == ["idle", "charging", "charging", "not_worn", "sleep_light", "sleep_rem",
                                       "sleep_deep", "active", "idle", "unknown"]
    assert p.activity_states([row(0)], prev_kind=11) == ["sleep_deep"]


def test_store_activity_upsert():
    s = Store(":memory:")
    r = {"ts": 60, "kind": 1, "intensity": 0, "steps": 5, "hr": 70, "unknown1": 0, "sleep": 0, "deep": 0, "rem": 0}
    assert s.upsert_activity([r, {**r, "ts": 120}]) == 2
    assert s.upsert_activity([{**r, "steps": 9}]) == 0
    assert s.activity_since(0)[0]["steps"] == 9
    assert s.activity_last_ts() == 120
    assert s.activity_prev_kind(1000) == 1
    s.set_meta("k", "v")
    assert s.get_meta("k") == "v" and s.get_meta("x") is None
