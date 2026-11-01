"""Analytics on synthetic and real PPG, daily scores, and the insights API (no Bluetooth)."""
import csv
import json
import math
import pathlib
from datetime import date, datetime, timedelta

import numpy as np
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from server import insights, protocol
from server.analytics import daily, hrv, ppg, rhythm, session
from server.store import Store

ROOT = pathlib.Path(__file__).resolve().parent.parent
REAL = sorted((ROOT / "data" / "raw").glob("*.csv"))
FS = 25.0


def synth_ppg(rr: list[float], fs=FS, resp_hz=0.0, noise=3.0, seed=0, start=1_790_000_000.0):
    """Pulse train with beats at cumulative RR, optional respiratory baseline/amplitude modulation.
    Raw counts fall with each systole (as the real sensor does)."""
    rng = np.random.default_rng(seed)
    beats = np.cumsum([0.5] + list(rr))
    n = int((beats[-1] + 1.0) * fs)
    t = np.arange(n) / fs
    y = np.zeros(n)
    for b in beats:
        ph = t - b
        rise = (ph >= 0) & (ph < 0.12)
        y[rise] += ph[rise] / 0.12
        fall = ph >= 0.12
        y[fall] += np.exp(-(ph[fall] - 0.12) * 6) * (ph[fall] < 1.5)
    if resp_hz:
        y *= 1 + 0.25 * np.sin(2 * np.pi * resp_hz * t)
        y += 0.4 * np.sin(2 * np.pi * resp_hz * t)
    raw = 40000 - 300 * y + rng.normal(0, noise, n)
    acc = np.tile([-0.76, -0.17, -0.63], (n, 1)) + rng.normal(0, 0.003, (n, 3))
    return ppg.make_recording(start + t, raw, start + t, acc), beats


def write_csv(path: pathlib.Path, rec: ppg.Recording):
    with path.open("w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["ts", "kind", "ppg", "ax", "ay", "az"])
        for t, v in zip(rec.ppg_t, rec.ppg):
            w.writerow([rec.start + t, "ppg", int(v), "", "", ""])
        for t, a in zip(rec.acc_t, rec.acc):
            w.writerow([rec.start + t, "accel", "", *a])


# ---- PPG / HRV / rhythm on synthetic signals -----------------------------------

def test_regular_rhythm_hr_and_hrv():
    rr = [1.0 + 0.03 * math.sin(2 * math.pi * 0.25 * i) for i in range(90)]
    rec, _ = synth_ppg(rr)
    a = session.analyze(rec, "syn")
    assert a["hr_bpm"] == pytest.approx(60, abs=1.5)
    assert a["signal_quality"] > 0.9
    assert a["rhythm"]["status"] == "regular"
    assert 5 < a["hrv"]["rmssd_ms"] < 60
    assert a["hrv"]["hf"] is not None and a["hrv"]["lf"] is None    # 90 s: HF yes, LF needs 120 s
    assert any("not a medical diagnosis" in n for n in a["rhythm"]["notes"])


def test_af_like_random_rr_flagged():
    rng = np.random.default_rng(3)
    rr = list(rng.uniform(0.45, 1.1, 120))
    rec, _ = synth_ppg(rr, seed=3)
    a = session.analyze(rec, "af")
    assert a["rhythm"]["status"] == "possible_af", a["rhythm"]["features"]


def test_ectopic_beats_counted():
    rr = []
    for i in range(100):
        if i % 15 == 7:
            rr += [0.5, 1.1]
        else:
            rr.append(0.8)
    rec, _ = synth_ppg(rr, seed=5)
    a = session.analyze(rec, "ect")
    assert a["rhythm"]["ectopic_beats"] >= 4
    assert a["rhythm"]["status"] in ("irregular", "regular") and a["rhythm"]["status"] != "possible_af"
    assert any(r["ectopic"] for r in a["rr"])


def test_respiratory_rate_from_modulation():
    rr = [0.85 + 0.04 * math.sin(2 * math.pi * 0.25 * 0.85 * i) for i in range(140)]
    rec, _ = synth_ppg(rr, resp_hz=0.25, seed=7)
    a = session.analyze(rec, "resp")
    assert a["respiratory_rate"] == pytest.approx(15, abs=2)


def test_motion_rejects_beats():
    rr = [0.9] * 80
    rec, _ = synth_ppg(rr, seed=9)
    shake = (rec.acc_t > 20) & (rec.acc_t < 35)
    rec.acc[shake] += np.random.default_rng(1).normal(0, 0.3, (int(shake.sum()), 3))
    beats = ppg.detect_beats(rec)
    moving = (beats.times > 21) & (beats.times < 34)
    assert not beats.good[moving].any()
    assert beats.good[~moving].mean() > 0.8


def test_polarity_independent():
    rr = [0.75] * 80
    rec, _ = synth_ppg(rr, seed=11)
    flipped = ppg.Recording(rec.start, rec.fs, 80000 - rec.ppg, rec.ppg_t, rec.acc_t, rec.acc)
    assert session.analyze(flipped, "f")["hr_bpm"] == pytest.approx(80, abs=1.5)


def test_retime_handles_bursts_and_rate_change():
    # 50 Hz for 20 s then 25 Hz, delivered in 10-sample packets whose stamps overlap.
    true_t = np.concatenate([np.arange(0, 20, 1 / 50), 20 + np.arange(0, 40, 1 / 25)])
    stamps = np.empty_like(true_t)
    for i in range(0, len(true_t), 10):
        end = true_t[min(i + 9, len(true_t) - 1)] + 0.05
        stamps[i:i + 10] = end - (9 - np.arange(min(10, len(true_t) - i))) / 25
    t = ppg.retime(stamps)
    early, late = np.median(np.diff(t[:800])), np.median(np.diff(t[-800:]))
    assert 1 / early == pytest.approx(50, rel=0.1)
    assert 1 / late == pytest.approx(25, rel=0.1)


def test_baevsky_and_tpr():
    nn = np.array([0.80] * 40 + [0.85] * 10)
    si = hrv.stress_index(nn)
    # AMo = 80 %, Mo = 0.825 s (bin centre), MxDMn = 0.05 s -> 80 / (2 * 0.825 * 0.05)
    assert si == pytest.approx(80 / (2 * 0.825 * 0.05), rel=0.05)
    tpr, random_like = rhythm.turning_point_ratio(np.arange(60, dtype=float))
    assert tpr == 0 and not random_like
    tpr, random_like = rhythm.turning_point_ratio(np.random.default_rng(0).uniform(0.5, 1.0, 200))
    assert random_like


def test_short_recording_is_insufficient():
    rec, _ = synth_ppg([0.8] * 8)
    a = session.analyze(rec, "short")
    assert a["rhythm"]["status"] == "insufficient_data"


@pytest.mark.parametrize("path", REAL, ids=[p.name for p in REAL])
def test_real_recordings_sane(path):
    a = session.analyze(ppg.load_csv(path), path.name)
    assert 0 <= a["signal_quality"] <= 1
    assert a["hr_bpm"] is None or 40 <= a["hr_bpm"] <= 180
    # PPG streams at ~25 Hz, or ~50 Hz when more raw sensors are enabled alongside it.
    assert 20 <= ppg.load_csv(path).fs <= 60


# ---- daily scores --------------------------------------------------------------

def minutes_for(nights: int, end: date, bed=23, wake=7, day_hr=70, night_hr=52):
    out = []
    start = datetime.combine(end - timedelta(days=nights), datetime.min.time())
    t = start
    while t.date() <= end and t < datetime.combine(end, datetime.min.time()) + timedelta(hours=14):
        h = t.hour
        asleep = h >= bed or h < wake
        state = ("sleep_deep" if t.minute < 15 else "sleep_rem" if t.minute < 27 else "sleep_light") if asleep else "idle"
        out.append({"ts": int(t.timestamp()), "state": state, "hr": night_hr if asleep else day_hr,
                    "steps": 0 if asleep else 5})
        t += timedelta(minutes=1)
    return out


def test_daily_scores_synthetic():
    end = date(2026, 3, 10)
    out = daily.compute(minutes_for(10, end), {"age": 30, "sex": "female", "weight_kg": 60}, 7, today=end)
    d = out["days"][-1]
    assert d["sleep_minutes"] == 8 * 60
    assert d["resting_hr"] == 52
    assert 80 <= d["sleep_score"] <= 100
    assert out["trends"]["sleep_regularity"] == pytest.approx(100, abs=1)
    assert out["trends"]["vo2max_estimate"] == pytest.approx(14.5 * (208 - 21) / 52, abs=0.2)
    assert 0 <= out["today"]["readiness"] <= 100
    assert out["today"]["body_battery"] is not None


def test_daily_night_in_progress_not_scored():
    end = date(2026, 3, 10)
    mins = [m for m in minutes_for(5, end) if m["ts"] < datetime.combine(end, datetime.min.time()).timestamp() + 2 * 3600]
    out = daily.compute(mins, {}, 7, today=end)
    assert out["days"][-1]["sleep_score"] is None
    assert out["today"]["note"]
    assert out["today"]["last_readiness"] is not None


def test_daily_without_profile_never_invents():
    end = date(2026, 3, 10)
    out = daily.compute(minutes_for(5, end), {}, 7, today=end)
    assert out["trends"]["vo2max_estimate"] is None
    assert all(d["strain"] is None and d["hr_zones"] is None for d in out["days"])
    assert any(a["title"] == "Complete your profile" for a in out["alerts"])


@pytest.mark.skipif(not (ROOT / "data" / "watch.db").exists(), reason="no real history")
def test_daily_on_real_history():
    store = Store(ROOT / "data" / "watch.db")
    rows = store.activity_since(0)
    states = protocol.activity_states(rows, None)
    mins = [{"ts": r["ts"], "state": s, "hr": r["hr"] if protocol.valid_hr(r["hr"]) else None, "steps": r["steps"]}
            for r, s in zip(rows, states)]
    last = datetime.fromtimestamp(rows[-1]["ts"]).date()
    out = daily.compute(mins, {"age": 28, "sex": "male", "weight_kg": 70}, 7, today=last)
    keys = {"date", "resting_hr", "hrv_rmssd", "sleep_minutes", "sleep_efficiency", "deep_min", "light_min", "rem_min",
            "awake_min", "sleep_score", "steps", "active_minutes", "strain", "trimp", "hr_zones", "spo2_avg",
            "stress_avg", "readiness", "calories_active"}
    assert all(keys <= set(d) for d in out["days"])
    assert any(d["sleep_score"] for d in out["days"])
    for d in out["days"]:
        assert d["readiness"] is None or 0 <= d["readiness"] <= 100
        assert d["strain"] is None or 0 <= d["strain"] <= 21
    assert 30 <= out["trends"]["resting_hr_7d"] <= 100


# ---- API -----------------------------------------------------------------------

class FakeWatch:
    def __init__(self, raw_dir: pathlib.Path):
        self.raw_dir, self.connected, self.streaming, self.file = raw_dir, True, False, None

    def status(self):
        return {"connection": "connected" if self.connected else "disconnected",
                "raw": {"streaming": self.streaming, "file": f"data/raw/{self.file}" if self.file else None}}

    async def raw_start(self):
        self.streaming, self.file = True, "20990101-000000.csv"
        rec, _ = synth_ppg([0.8] * 80, seed=2)
        write_csv(self.raw_dir / self.file, rec)
        return True

    async def raw_stop(self):
        self.streaming = False
        return False


@pytest.fixture
def api(tmp_path, monkeypatch):
    async def no_sleep(_):
        return None
    monkeypatch.setattr(insights, "_sleep", no_sleep)
    raw = tmp_path / "raw"
    raw.mkdir()
    store = Store(":memory:")
    events = []
    watch = FakeWatch(raw)
    app = FastAPI()
    app.include_router(insights.make_router(store, watch, events.append, raw))
    return TestClient(app), watch, raw, events, store


def test_profile_roundtrip_and_validation(api):
    client, *_ = api
    assert client.get("/api/profile").json()["age"] is None
    r = client.post("/api/profile", json={"age": "31", "sex": "male", "weight_kg": 72.5, "bogus": 1})
    assert r.json()["age"] == 31 and r.json()["weight_kg"] == 72.5
    assert client.post("/api/profile", json={"sex": "x"}).status_code == 422
    assert client.get("/api/profile").json()["sex"] == "male"


def test_recordings_and_session(api):
    client, _, raw, _, _ = api
    assert client.get("/api/insights/session").status_code == 404
    rec, _ = synth_ppg([0.75] * 70)
    write_csv(raw / "20260101-120000.csv", rec)
    lst = client.get("/api/insights/recordings").json()["recordings"]
    assert lst[0]["file"] == "20260101-120000.csv" and lst[0]["ppg_samples"] == len(rec.ppg)
    s = client.get("/api/insights/session?file=latest").json()
    assert s["hr_bpm"] == pytest.approx(80, abs=1.5)
    for k in ("signal_quality", "usable_s", "beats", "hrv", "rhythm", "respiratory_rate", "rr", "ppg_preview",
              "beat_times", "motion"):
        assert k in s
    assert len(s["ppg_preview"]) <= 2000
    assert client.get("/api/insights/session?file=nope.csv").status_code == 404


def test_measure(api):
    client, watch, raw, events, _ = api
    assert client.post("/api/insights/measure", json={"seconds": 7}).status_code == 422
    watch.connected = False
    assert client.post("/api/insights/measure", json={"seconds": 60}).status_code == 409
    watch.connected = True
    r = client.post("/api/insights/measure", json={"seconds": 60})
    assert r.status_code == 200, r.text
    assert r.json()["hr_bpm"] == pytest.approx(75, abs=1.5)
    assert not watch.streaming
    msgs = [e["msg"] for e in events]
    assert any("s left" in m for m in msgs) and any(m.startswith("Measurement done") for m in msgs)


def test_daily_endpoint_empty_and_with_health(api):
    client, _, _, _, store = api
    body = client.get("/api/insights/daily?days=7").json()
    assert set(body) == {"days", "today", "trends", "alerts"}
    # A health table from the watch-data module is picked up when present.
    with store._lock:
        store._db.execute("CREATE TABLE IF NOT EXISTS health (type TEXT, ts REAL, value REAL)")
        store._db.execute("INSERT INTO health (type, ts, value) VALUES ('spo2', ?, 96)", (int(datetime.now().timestamp()),))
        store._db.commit()
    body = client.get("/api/insights/daily?days=7").json()
    assert body["days"][-1]["spo2_avg"] == 96
