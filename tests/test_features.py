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


def test_raw_mask_and_new_packet_types():
    from server import protocol as p
    assert p.raw_mask(["accel", "ppg"]) == 0x03
    assert p.raw_mask(["gps", "gyro", "mag"]) == 0x58
    assert p.raw_start_cmds(0x58)[1] == bytes([0x01, 0x58, 0, 0, 0, 0x19])
    kind, s = p.parse_raw(bytes.fromhex("0600d904980ab8fbd704990abefb"))
    assert kind == "mag" and s[0] == (1241, 2712, -1096) and len(s) == 2
    kind, s = p.parse_raw(bytes.fromhex("0401fbff02000400070000000000"))
    assert kind == "gyro" and s == [(-5, 2, 4), (7, 0, 0)]
    kind, s = p.parse_raw(b"\x03\x00$GPGGA,1")
    assert kind == "gps" and s == ["$GPGGA,1"]


def test_nmea_parser_fix_and_satellites():
    from server.gps import Nmea
    n = Nmea()
    text = ("$GPGSV,1,1,02,05,45,120,38,12,10,300,,*7F\r\n"
            "$GNRMC,215503.00,A,2830.0000,N,07700.0000,E,1.0,90.0,011126,,,A*00\r\n"
            "$GPGGA,215503.00,2830.0000,N,07700.0000,E,1,07,1.2,215.0,M,,M,,*00\r\n")
    # Checksums above are placeholders, so strip them: the parser accepts sentences without one.
    text = "\r\n".join(line.split("*")[0] for line in text.split("\r\n"))
    assert n.feed(text[:40]) is False
    assert n.feed(text[40:] + "\r\n") is True
    st = n.state
    assert st["fix"] and st["sats_used"] == 7 and st["alt_m"] == 215.0
    assert abs(st["lat"] - 28.5) < 1e-6 and abs(st["lon"] - 77.0) < 1e-6
    assert st["speed_mps"] == 0.51 and st["utc"].startswith("2026-11-01T21:55:03")
    assert {s["id"] for s in st["satellites"]} == {"05", "12"}
    assert next(s for s in st["satellites"] if s["id"] == "12")["snr"] is None


def test_nmea_rejects_bad_checksum():
    from server.gps import Nmea
    n = Nmea()
    assert n.feed("$GPGGA,215503.00,2830.0,N,07700.0,E,1,07,1.2,215.0,M,,M,,*FF\r\n") is False
    assert n.state["sats_used"] == 0


def test_mic_frames_split_across_packets():
    from server.audio import split_frames
    stream = bytes([10, 8]) + bytes(9) + bytes([9, 8]) + bytes(8) + bytes([12, 8])
    frames, tail, skipped = split_frames(stream)
    assert [len(f) for f in frames] == [10, 9] and tail == bytes([12, 8]) and skipped == 0
    more, tail, _ = split_frames(tail + bytes(11))
    assert [len(f) for f in more] == [12] and tail == b""


def test_mic_resyncs_after_lost_packet():
    from server.audio import MicStream, split_frames
    good = (bytes([10, 8]) + bytes(9)) * 4
    # Garbage where a lost packet cut a frame: the parser skips to the next [len][0x08] boundary.
    frames, tail, skipped = split_frames(bytes([0x55, 0x99, 0x01]) + good)
    assert len(frames) == 4 and skipped == 3


def test_mic_decodes_real_tone(tmp_path):
    import json, pathlib, wave
    import numpy as np
    from server import protocol as p
    from server.audio import MicStream
    fx = json.loads((pathlib.Path(__file__).parent / "fixtures" / "mic_2400hz.json").read_text())
    payload = b"".join(bytes([len(bytes.fromhex(f))]) + bytes.fromhex(f) for f in fx["frames"])
    assert p.parse_raw(b"\x05\x00" + payload[:10])[0] == "mic"
    mic = MicStream(tmp_path / "t.wav")
    levels = mic.feed(payload[:37]) + mic.feed(payload[37:])  # split mid-frame like real packets
    mic.close()
    assert mic.stats["errors"] == 0 and len(levels) == len(fx["frames"])
    with wave.open(str(tmp_path / "t.wav")) as w:
        x = np.frombuffer(w.readframes(w.getnframes()), np.int16).astype(float)
    seg = x[-int(0.5 * 48000):]
    spec = np.abs(np.fft.rfft(seg * np.hanning(len(seg))))
    freqs = np.fft.rfftfreq(len(seg), 1 / 48000)
    band = (freqs > 300) & (freqs < 3800)
    assert abs(freqs[band][np.argmax(spec[band])] - 2400) < 30
