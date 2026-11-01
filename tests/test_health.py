import json
import pathlib
import struct
from datetime import datetime, timedelta, timezone

import pytest
from fastapi.testclient import TestClient
from test_api import FakeWatch

from server import health as h
from server.app import Hub, create_app
from server.protocol import parse_fetch_start
from server.store import Store

FX = json.loads((pathlib.Path(__file__).parent / "fixtures" / "health_fetch.json").read_text())


def blob(key):
    return bytes.fromhex(FX[key]["data"])


def start(key):
    return parse_fetch_start(bytes.fromhex(FX[key]["reply"]))[1]


# ---- parsers on bytes captured from the GTR 2e -------------------------------------------------------------

def test_manual_hr():
    rows = h.parse_manual_hr(blob("manual_hr"))
    assert [r["value"] for r in rows] == [113, 116, 105, 95]
    assert rows[0]["ts"] == 1793471635 and rows[0]["tz_q"] == 22


def test_stress_skips_empty_minutes():
    rows = h.parse_stress(blob("stress"), start("stress"))
    assert [r["value"] for r in rows] == [54, 31, 38]
    assert all(r["ts"] % 60 == 0 for r in rows)
    assert rows[1]["ts"] - rows[0]["ts"] == 5 * 60


def test_pai():
    (row,) = h.parse_pai(blob("pai"))
    assert row["value"] == 0.17 and row["total"] == 114.54
    assert (row["minutes_low"], row["minutes_moderate"], row["minutes_high"]) == (13, 1, 0)


def test_spo2_six_byte_records():
    (row,) = h.parse_spo2(blob("spo2_sleep"))
    assert row["value"] == 99 and row["ts"] == 0x6AE65476


def test_spo2_version2_normal_format():
    rec = struct.pack("<Ib", 1_700_000_000, -128 + 97) + bytes(60)
    (row,) = h.parse_spo2(bytes([2]) + rec)
    assert row == {"ts": 1_700_000_000, "value": 97, "auto": True}


def test_workout_summary_and_track():
    w = h.parse_workout_summary(blob("workout_summary"))
    assert w["version"] == 515 and w["type"] == "Strength training"
    assert (w["duration_s"], w["steps"], w["calories"], w["avg_hr"], w["max_hr"]) == (1477, 498, 158, 109, 162)
    track = h.parse_workout_details(blob("workout_details"))
    assert len(track) == 505 and track[0] == {"t": 17, "hr": 72}
    assert max(p["hr"] for p in track) == 162
    assert all(b["t"] >= a["t"] for a, b in zip(track, track[1:]))


def test_temperature_anchored_to_fetch_end():
    rows = h.parse_temperature(blob("temperature"), start("temperature"), FX["temperature"]["end_ts"])
    assert len(rows) == 100
    assert rows[-1]["ts"] == FX["temperature"]["end_ts"] // 60 * 60
    assert all(15 < r["value"] < 40 for r in rows) and rows[-1]["value"] > 30


def test_temperature_skips_invalid_and_uses_consistent_start():
    data = struct.pack("<hhHH", 3100, 3250, 0x5A5A, 0x5A5A) + struct.pack("<hhHH", 0x5A5A, 0x5A5A, 0x5A5A, 0x5A5A)
    rows = h.parse_temperature(data, 6000, 6060)
    assert rows == [{"ts": 6000, "value": 32.5, "sensor2": 31.0}]


def test_realtime_steps_device_events_and_time():
    assert h.parse_realtime_steps(bytes.fromhex(FX["realtime_steps"])) == {
        "steps": 536, "distance_m": 389, "calories": 37}
    assert h.parse_device_event(bytes([0x04])) == "button_pressed"
    assert h.parse_device_event(bytes([0xFE, 0x03])) == "music_control:next"
    assert h.parse_current_time(bytes.fromhex(FX["current_time"])) == "2026-11-01T02:56:21"
    ist = timezone(timedelta(hours=5, minutes=30))
    assert h.current_time_bytes(datetime(2026, 11, 1, 2, 56, 23, tzinfo=ist)).hex() == "ea070b0102381707000016"


def test_notification_payload_and_chunks():
    p = h.notification_payload("Hi", "there")
    # GTS family (incl. GTR 2e): category, 4-byte extra header, 1, icon. Missing header rebooted the watch.
    assert p == bytes([0xFA, 0, 0, 0, 0, 1, 11]) + b"Hi\0there\n\n\0Dashboard\0"
    assert h.notification_payload("Hi", "there", "generic") == bytes([5, 0, 0, 0, 0, 1]) + b"Hi\0there\n\n\0 \0"
    long = h.notification_payload("T", "é" * 400)
    assert len(long) <= h.NOTIFICATION_MAX + len(b"\0Dashboard\0")
    long[7:-len(b"\0Dashboard\0")].decode()  # never cuts a UTF-8 character in half
    chunks = h.chunked_writes(bytes(50), mtu=23)
    assert [c[:3] for c in chunks] == [bytes([0, 0, 0]), bytes([0, 0x40, 1]), bytes([0, 0x80, 2])]
    assert sum(len(c) - 3 for c in chunks) == 50
    assert h.chunked_writes(b"x", mtu=247) == [bytes([0, 0xC0, 0]) + b"x"]
    assert h.notification_payload("Mum", "", "call")[:6] == bytes([3, 0, 0, 0, 0, 0])


def test_setting_commands():
    assert h.setting_command("hr_interval", 1) == ("hr", bytes([0x14, 1]))
    assert h.setting_command("stress_monitoring", True) == ("config", bytes([0xFE, 0x06, 0x00, 0x01]))
    assert h.setting_command("hr_alert_threshold", 140, {"hr_alert": True}) == (
        "config", bytes([0x06, 0x1A, 0x00, 0x01, 140]))
    assert h.validate_setting("hr_interval", 5) == 5
    with pytest.raises(ValueError):
        h.validate_setting("hr_interval", 7)
    with pytest.raises(KeyError):
        h.validate_setting("factory_reset", True)


# ---- store + API --------------------------------------------------------------------------------------------

def test_store_health_and_workouts():
    s = Store(":memory:")
    assert s.upsert_health("stress", [{"ts": 60, "value": 40}, {"ts": 120, "value": 50}]) == 2
    assert s.upsert_health("stress", [{"ts": 120, "value": 55, "source": "x"}]) == 0
    assert s.health_since("stress", 0) == [{"ts": 60, "value": 40}, {"ts": 120, "value": 55, "source": "x"}]
    assert s.health_last_ts("stress") == 120
    w = h.parse_workout_summary(blob("workout_summary"))
    assert s.upsert_workout(w, [{"t": 1, "hr": 80}]) is True
    assert s.upsert_workout(w, None) is False
    (got,) = s.workouts_since(0)
    assert got["type"] == "Strength training" and got["track"] == [{"t": 1, "hr": 80}]


class HealthFakeWatch(FakeWatch):
    def __init__(self, emit):
        super().__init__(emit)
        self.sent, self.set = [], {}

    async def health_sync(self):
        self._require()
        return {"types": {"spo2": {"added": 1, "supported": True, "note": ""}}}

    def settings(self):
        return [{"key": "hr_interval", "label": "All-day heart rate", "type": "enum",
                 "value": self.set.get("hr_interval"), "options": [], "min": None, "max": None}]

    async def set_setting(self, key, value):
        self._require()
        h.validate_setting(key, value)
        self.set[key] = value
        return {"ok": True}

    async def notify(self, title, body, kind="message"):
        self._require()
        self.sent.append((title, body, kind))
        return {"ok": True}

    async def time_sync(self):
        self._require()
        return {"ok": True, "time": "2026-11-01T03:00:00"}


@pytest.fixture
def henv():
    hub, store = Hub(), Store(":memory:")
    watch = HealthFakeWatch(hub.publish)
    with TestClient(create_app(watch=watch, store=store, hub=hub)) as client:
        yield client, watch, store


def test_health_endpoints(henv):
    client, watch, store = henv
    assert client.post("/api/health/sync").status_code == 409
    client.post("/api/connect")
    assert client.post("/api/health/sync").json()["types"]["spo2"]["added"] == 1
    import time
    now = int(time.time()) // 60 * 60
    store.upsert_health("spo2", [{"ts": now - 600, "value": 97}])
    store.upsert_health("spo2_sleep", [{"ts": now - 300, "value": 99}])
    store.upsert_health("temperature", [{"ts": now - 60, "value": 32.5, "sensor2": 31.0}])
    store.upsert_workout({**h.parse_workout_summary(blob("workout_summary")), "start": now - 3600,
                          "end": now - 2000}, [{"t": 5, "hr": 90}])
    body = client.get("/api/health?days=1").json()
    assert set(body["series"]) >= {"spo2", "stress", "pai", "resting_hr", "max_hr", "manual_hr", "temperature",
                                    "sleep_respiratory_rate", "hrv"}
    assert [(r["value"], r["source"]) for r in body["series"]["spo2"]] == [(97, "spo2"), (99, "spo2_sleep")]
    assert body["series"]["temperature"][0]["sensor2"] == 31.0
    (w,) = body["workouts"]
    assert w["type"] == "Strength training" and w["avg_hr"] == 109 and w["track"] == [{"t": 5, "hr": 90}]
    assert body["sleep_sessions"] == [] and body["last_sync"] is None


def test_settings_notify_time(henv):
    client, watch, _ = henv
    assert client.post("/api/notify", json={"title": "a", "body": "b"}).status_code == 409
    client.post("/api/connect")
    assert client.get("/api/settings").json()["settings"][0]["key"] == "hr_interval"
    assert client.post("/api/settings", json={"key": "hr_interval", "value": 1}).json() == {"ok": True}
    assert client.post("/api/settings", json={"key": "hr_interval", "value": 7}).status_code == 400
    assert client.post("/api/settings", json={"key": "nope", "value": 1}).status_code == 400
    assert client.post("/api/settings", json={"value": 1}).status_code == 400
    assert client.post("/api/notify", json={"title": "Hi", "body": "there"}).json() == {"ok": True}
    assert client.post("/api/notify", json={"title": "Hi", "kind": "sms"}).status_code == 400
    assert watch.sent == [("Hi", "there", "message")]
    assert client.post("/api/time/sync").json()["ok"] is True
