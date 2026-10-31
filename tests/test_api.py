import time

import pytest
from fastapi.testclient import TestClient

from server.app import Hub, create_app
from server.store import Store
from server.watch import NotConnected


class FakeWatch:
    """Same public surface as server.watch.Watch, no Bluetooth."""

    def __init__(self, emit):
        self.emit = emit
        self.connected = False
        self.streaming = False
        self.raw = False
        self.vibrations = 0

    def status(self):
        return {
            "connection": "connected" if self.connected else "disconnected",
            "error": None, "model": "Amazfit GTR 2e", "mac": "AA:BB", "firmware": "V1",
            "battery": None, "hr": {"streaming": self.streaming, "last_bpm": None, "last_ts": None},
            "connected_since": None, "rssi": None,
            "raw": {"streaming": self.raw, "file": "data/raw/x.csv" if self.raw else None},
        }

    def _require(self):
        if not self.connected:
            raise NotConnected("The watch is not connected")

    async def connect(self):
        self.connected = True
        self.emit({"type": "status", **self.status()})

    async def disconnect(self):
        self.connected = self.streaming = False

    async def shutdown(self):
        pass

    async def read_battery(self):
        self._require()
        return {"level": 68, "charging": False, "last_charge_a": None, "last_charge_b": None,
                "raw": "0f44", "read_at": 1.0}

    async def device_info(self):
        self._require()
        return {"serial": "S", "hardware": "H", "software": "V1", "system_id": None, "pnp_id": None}

    async def hr_start(self):
        self._require()
        self.streaming = True
        return True

    async def hr_stop(self):
        self._require()
        self.streaming = False
        return False

    def gatt(self):
        self._require()
        return [{"uuid": "180d", "name": "Heart Rate", "characteristics": [{"uuid": "2a37", "properties": ["notify"]}]}]

    async def raw_start(self):
        self._require()
        self.raw = True
        self.emit({"type": "raw", "ts": 1.0, "ppg": [43917, 43930], "accel": [[-0.08, 0.03, -0.983]]})
        return True

    async def raw_stop(self):
        self._require()
        self.raw = False
        return False

    async def activity_sync(self):
        self._require()
        return {"minutes_added": 2, "from": "2026-10-31T02:28:00+05:30", "to": "2026-10-31T02:29:00+05:30"}

    async def vibrate(self):
        self._require()
        self.vibrations += 1
        return {"ok": True}

    async def raw_probe(self):
        self._require()
        return {"supported": False, "replies": {"start": "10010304", "go": "100204", "stop": "100301"},
                "packets": 0, "note": "rejected"}


@pytest.fixture
def env(tmp_path):
    hub, store = Hub(), Store(":memory:")
    watch = FakeWatch(hub.publish)
    with TestClient(create_app(watch=watch, store=store, hub=hub)) as client:
        yield client, watch, store, hub


def test_status_and_409s_before_connect(env):
    client, *_ = env
    assert client.get("/api/status").json()["connection"] == "disconnected"
    for method, path in [("get", "/api/battery"), ("get", "/api/device-info"), ("post", "/api/hr/start"),
                         ("post", "/api/hr/stop"), ("get", "/api/gatt"), ("post", "/api/raw/probe")]:
        r = getattr(client, method)(path)
        assert r.status_code == 409, path
        assert r.json() == {"error": "The watch is not connected"}


def test_connected_flow(env):
    client, watch, store, _ = env
    assert client.post("/api/connect").json()["connection"] == "connected"
    assert client.get("/api/battery").json()["level"] == 68
    assert client.get("/api/device-info").json()["software"] == "V1"
    assert client.post("/api/hr/start").json() == {"streaming": True}
    assert client.get("/api/status").json()["hr"]["streaming"] is True
    assert client.post("/api/hr/stop").json() == {"streaming": False}
    assert client.get("/api/gatt").json()["services"][0]["name"] == "Heart Rate"
    assert client.post("/api/raw/probe").json()["supported"] is False
    assert client.post("/api/disconnect").json()["connection"] == "disconnected"


def test_hr_history_and_events(env):
    client, _, store, _ = env
    now = time.time()
    store.add_hr(now - 7200, 50)
    store.add_hr(now - 30, 0)
    store.add_hr(now - 20, 80)
    store.add_hr(now - 10, 100)
    body = client.get("/api/hr/history?minutes=5").json()
    assert [s["bpm"] for s in body["samples"]] == [80, 100]
    assert body["stats"] == {"min": 80, "max": 100, "avg": 90.0, "count": 2}
    store.add_event(now, "info", "hello")
    assert client.get("/api/events?limit=5").json()["events"][0]["msg"] == "hello"


def test_ws_status_then_pushes(env):
    client, _, _, hub = env
    with client.websocket_connect("/ws") as ws:
        first = ws.receive_json()
        assert first["type"] == "status" and first["connection"] == "disconnected"
        hub.publish({"type": "hr", "ts": 1.0, "bpm": 77})
        assert ws.receive_json() == {"type": "hr", "ts": 1.0, "bpm": 77}


def test_ble_failure_is_500(env):
    client, watch, *_ = env

    async def boom():
        raise RuntimeError("GATT write failed")

    watch.connected = True
    watch.read_battery = boom
    r = client.get("/api/battery")
    assert r.status_code == 500 and r.json() == {"error": "GATT write failed"}


def test_static_index_served(env):
    client, *_ = env
    assert client.get("/").status_code == 200


def test_raw_stream_endpoints(env):
    client, watch, _, _ = env
    assert client.post("/api/raw/start").status_code == 409
    client.post("/api/connect")
    with client.websocket_connect("/ws") as ws:
        assert ws.receive_json()["type"] == "status"
        assert client.post("/api/raw/start").json() == {"streaming": True}
        msg = ws.receive_json()
        assert msg["type"] == "raw" and msg["ppg"] == [43917, 43930] and len(msg["accel"][0]) == 3
    status = client.get("/api/status").json()
    assert status["raw"] == {"streaming": True, "file": "data/raw/x.csv"}
    assert client.post("/api/raw/stop").json() == {"streaming": False}


def test_activity_endpoints(env):
    client, watch, store, _ = env
    assert client.post("/api/activity/sync").status_code == 409
    client.post("/api/connect")
    assert client.post("/api/activity/sync").json()["minutes_added"] == 2
    now = int(time.time()) // 60 * 60
    store.upsert_activity([
        {"ts": now - 180, "kind": 9, "intensity": 0, "steps": 0, "hr": 55, "unknown1": 5, "sleep": 60, "deep": 10, "rem": 10},
        {"ts": now - 120, "kind": 240, "intensity": 0, "steps": 0, "hr": 255, "unknown1": 5, "sleep": 60, "deep": 10, "rem": 10},
        {"ts": now - 60, "kind": 1, "intensity": 40, "steps": 30, "hr": 90, "unknown1": 5, "sleep": 0, "deep": 0, "rem": 0},
    ])
    store.set_meta("activity_last_sync", "123.5")
    body = client.get("/api/activity?hours=1").json()
    assert [s["state"] for s in body["samples"]] == ["sleep_light", "sleep_light", "active"]
    assert body["samples"][1]["hr"] is None
    assert body["summary"] == {"steps": 30, "hr_min": 55, "hr_avg": 72.5, "hr_max": 90,
                               "active_minutes": 1, "worn_minutes": 3, "sleep_minutes": 2}
    assert body["last_sync"] == 123.5


def test_vibrate(env):
    client, watch, *_ = env
    assert client.post("/api/vibrate").status_code == 409
    client.post("/api/connect")
    assert client.post("/api/vibrate").json() == {"ok": True}
    assert watch.vibrations == 1
