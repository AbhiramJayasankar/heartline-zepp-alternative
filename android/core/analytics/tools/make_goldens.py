"""Regenerate reference outputs; never opens the Python project/database for writing.

Run with the reference project's venv Python and -B. No Kotlin implementation is involved.
"""
import argparse
import csv
import hashlib
import json
import math
import pathlib
import shutil
import sqlite3
import sys
from collections import defaultdict
from datetime import date, datetime, timedelta
from statistics import median

sys.dont_write_bytecode = True
parser = argparse.ArgumentParser()
parser.add_argument("--reference", type=pathlib.Path,
                    default=pathlib.Path(r"C:\Users\abhir\Desktop\projects\amazfit-gtr2e"))
args = parser.parse_args()
sys.path.insert(0, str(args.reference))
import numpy as np
import scipy
from scipy import signal
from server import protocol
from server.analytics import daily, ppg, session

ROOT = pathlib.Path(__file__).resolve().parents[1]
RES = ROOT / "src/test/resources"
GOLD = RES / "goldens"
REC = RES / "recordings"
GOLD.mkdir(parents=True, exist_ok=True)
REC.mkdir(parents=True, exist_ok=True)


def dump(path, obj):
    path.write_text(json.dumps(obj, ensure_ascii=False, allow_nan=False, separators=(",", ":")) + "\n", encoding="utf-8")


def synth(name, rr, resp_hz=0, seed=0, duration=None, flat=False):
    rng = np.random.default_rng(seed)
    beats = np.cumsum([0.5] + list(rr))
    n = int((beats[-1] + 1) * 25) if duration is None else int(duration * 25)
    t = np.arange(n) / 25
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
    raw = np.full(n, 40000.0) if flat else 40000 - 300 * y + rng.normal(0, 3, n)
    acc = np.tile([-0.76, -0.17, -0.63], (n, 1)) + rng.normal(0, 0.003, (n, 3))
    with (REC / name).open("w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["ts", "kind", "ppg", "ax", "ay", "az"])
        for ts, v in zip(t, raw):
            w.writerow([1790000000 + ts, "ppg", int(v), "", "", ""])
        for ts, a in zip(t, acc):
            w.writerow([1790000000 + ts, "accel", "", *a])


for name in ("20261101-023830.csv", "20261101-024059.csv"):
    shutil.copyfile(args.reference / "data/raw" / name, REC / name)
synth("regular.csv", [1 + 0.03 * math.sin(2 * math.pi * 0.25 * i) for i in range(90)])
synth("af_like.csv", np.random.default_rng(3).uniform(0.45, 1.1, 120), seed=3)
rr = [0.8] * 100
rr[47:49] = [0.5, 1.1]
synth("one_ectopic.csv", rr, seed=5)
synth("respiration.csv", [0.85 + 0.04 * math.sin(2 * math.pi * 0.25 * 0.85 * i) for i in range(160)], resp_hz=0.25, seed=7)
synth("short.csv", [0.8] * 6)
synth("too_few_intervals.csv", [0.8] * 9)
synth("ten_intervals.csv", [0.8] * 10)
synth("flat.csv", [0.8] * 50, flat=True)
synth("slow.csv", [1.9] * 50)
session_names = ["20261101-023830.csv", "20261101-024059.csv", "regular.csv", "af_like.csv",
                 "one_ectopic.csv", "respiration.csv", "short.csv", "too_few_intervals.csv", "ten_intervals.csv", "slow.csv"]
# Constant nonzero PPG exposes NumPy's unspecified equal-height argsort ordering:
# keep its reference for a documented diagnostic, not physiological parity claims.
diagnostics = ["flat.csv"]
for name in session_names + diagnostics:
    result = session.analyze(ppg.load_csv(REC / name), name)
    dump(GOLD / (pathlib.Path(name).stem + ".json"), result)
    print(name, result["beats"], result["hr_bpm"], result["rhythm"]["status"], result["rhythm"]["ectopic_beats"])


def health_by_day(values):
    groups = defaultdict(lambda: defaultdict(list))
    for k, ts, v in values:
        groups[k][datetime.fromtimestamp(ts).date()].append(v)
    return {k: {d: round(min(v) if k == "resting_hr" else sum(v) / len(v), 1) for d, v in by.items()}
            for k, by in groups.items()}


def daily_output(snapshot):
    mins = [dict(zip(snapshot["minute_columns"], r)) for r in snapshot["minutes"]]
    qualified = defaultdict(list)
    for c in snapshot["checks"]:
        if c["rmssd_ms"] is not None and c["signal_quality"] >= .7 and c["usable_s"] >= 45:
            qualified[datetime.fromtimestamp(c["start"]).date()].append(c["rmssd_ms"])
    latest = max((c for c in snapshot["checks"] if c["signal_quality"] >= .5), key=lambda c: c["start"], default=None)
    since = datetime.combine(date.fromisoformat(snapshot["now"][:10]) - timedelta(days=max(snapshot["days"], 35) + 1), datetime.min.time()).timestamp()
    return daily.compute(mins, snapshot["profile"], snapshot["days"], today=date.fromisoformat(snapshot["now"][:10]),
                         hrv_by_day={d: round(median(v), 1) for d, v in qualified.items()},
                         health=health_by_day([v for v in snapshot["health"] if v[1] >= since]),
                         rhythm_latest={"status": latest["rhythm_status"]} if latest else None)


# Windows Python uses the host local timezone. Record its UTC offset and assert the fixture date's offset.
# All committed daily fixtures use Asia/Kolkata; this fails clearly on a differently configured generator host.
assert datetime(2026, 11, 1).astimezone().utcoffset() == timedelta(hours=5, minutes=30), "Run with local timezone Asia/Kolkata"
db = sqlite3.connect((args.reference / "data/watch.db").as_uri() + "?mode=ro", uri=True)
db.execute("PRAGMA query_only=ON")
db.execute("BEGIN")  # consistent snapshot across the tables below
db.row_factory = sqlite3.Row
print("Database tables:", [r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")])
now = "2026-11-01T14:00:00+05:30[Asia/Kolkata]"
since = int(datetime(2026, 10, 12).timestamp())
activity = [dict(r) for r in db.execute("SELECT * FROM activity WHERE ts >= ? ORDER BY ts", (since,))]
prev = db.execute("SELECT kind & 15 FROM activity WHERE ts < ? AND (kind & 15) NOT IN (0,10) ORDER BY ts DESC LIMIT 1", (since,)).fetchone()
states = protocol.activity_states(activity, prev[0] if prev else None)
columns = ["ts", "kind", "intensity", "steps", "hr", "sleep", "deep", "rem", "state"]
minutes = []
for r, state in zip(activity, states):
    r["hr"] = r["hr"] if protocol.valid_hr(r["hr"]) else None
    r["state"] = state
    minutes.append([r[k] for k in columns])
health = [list(r) for r in db.execute("SELECT type,ts,value FROM health WHERE ts >= ? AND value IS NOT NULL ORDER BY type,ts", (since,))]
checks = []
if db.execute("SELECT 1 FROM sqlite_master WHERE name='insight_sessions'").fetchone():
    for r in db.execute("SELECT start,rmssd,hr,quality,usable_s,rhythm FROM insight_sessions ORDER BY start"):
        checks.append(dict(zip(["start", "rmssd_ms", "hr_bpm", "signal_quality", "usable_s", "rhythm_status"], r)))
profile_row = db.execute("SELECT value FROM meta WHERE key='profile'").fetchone()
stored_profile = json.loads(profile_row[0]) if profile_row else {}
db.rollback()
db.close()
snapshot = dict(now=now, days=14, minute_columns=columns, minutes=minutes, health=health, checks=checks,
                profile={"age": 28, "sex": "male", "weight_kg": 70}, source_profile=stored_profile)
dump(RES / "daily_snapshot.json", snapshot)
assert (RES / "daily_snapshot.json").stat().st_size < 3_000_000
dump(GOLD / "daily_real.json", daily_output(snapshot))
print("Snapshot bytes:", (RES / "daily_snapshot.json").stat().st_size, "minutes:", len(minutes), "health:", len(health))

# Compact recipe shared with Kotlin: exercise, naps, gaps, no-data states, and changing baselines.
recipe = dict(start="2026-10-01", end="2026-11-01", stop_hour=14)
synthetic = []
start = datetime.fromisoformat(recipe["start"])
end = datetime.fromisoformat(recipe["end"]) + timedelta(hours=recipe["stop_hour"])
t = start
while t < end:
    day = (t.date() - start.date()).days
    asleep = t.hour >= 23 or t.hour < (5 if t.date() == end.date() else 7)
    state = ("sleep_deep" if t.minute < 15 else "sleep_rem" if t.minute < 27 else "sleep_light") if asleep else "idle"
    hr = 51 + day % 5 if asleep else 72 + day % 4
    steps = 0 if asleep else 5
    if 16 <= t.hour < 17:
        hr, state, steps = (170 if day >= 25 else 110), "active", 80
    if t.hour == 14 and t.minute < 20:
        state, hr, steps = "sleep_light", 57, 0
    if t.hour == 2 and 20 <= t.minute < 25:
        state, steps = "idle", 0
    if t.hour == 10 and t.minute < 20:
        state, hr, steps = "not_worn", None, 0
    synthetic.append(dict(ts=int(t.timestamp()), state=state, hr=hr, steps=steps))
    t += timedelta(minutes=1)
health_syn, checks_syn = [], []
for i in range(32):
    ts = int((start + timedelta(days=i, hours=8)).timestamp())
    health_syn.extend([["resting_hr", ts, float(51 + i % 5 if i < 31 else 64)],
                       ["resting_hr", ts + 60, 80.0], ["hrv", ts, 31.0],
                       ["spo2", ts, 97.0 if i < 31 else 90.0], ["stress", ts, 20.0 + i % 7]])
    for delta, rmssd, quality, usable in [(0, 40 + i % 7 if i < 31 else 20, .9, 50), (1, 44, .8, 60),
                                           (2, 999, .69, 80), (3, 999, .9, 44.9)]:
        checks_syn.append(dict(start=ts + delta, rmssd_ms=rmssd, hr_bpm=65.0, signal_quality=quality,
                               usable_s=usable, rhythm_status="possible_af" if i == 31 and delta == 1 else "regular"))
# latest unqualified for HRV is still used by latest_rhythm, as in insights.py.
checks_syn[-1]["rhythm_status"] = "possible_af"
synthetic_snapshot = dict(now=now, days=28, minute_columns=["ts", "state", "hr", "steps"],
                          minutes=[[m[k] for k in ["ts", "state", "hr", "steps"]] for m in synthetic],
                          health=health_syn, checks=checks_syn, profile={"age": 30, "sex": "female", "weight_kg": 60})
dump(RES / "daily_recipe.json", dict(recipe=recipe, health=health_syn, checks=checks_syn, now=now, days=28,
                                    profile=synthetic_snapshot["profile"]))
dump(GOLD / "daily_synthetic.json", daily_output(synthetic_snapshot))
dump(GOLD / "daily_no_profile.json", daily_output(dict(synthetic_snapshot, profile={})))
progress_cut = int(datetime(2026, 11, 1, 2).timestamp())
dump(GOLD / "daily_in_progress.json", daily_output(dict(synthetic_snapshot,
     minutes=[r for r in synthetic_snapshot["minutes"] if r[0] < progress_cut], health=[], checks=[])))
dump(GOLD / "daily_empty.json", daily_output(dict(synthetic_snapshot, minutes=[], health=[], checks=[], profile={})))

# Isolate numerical primitives and timing independently of high-level session assertions.
x = np.sin(np.arange(400) * .13) + np.arange(400) * .001
primitives = dict(x=x.tolist(), filters=[])
for fs, lo, hi in [(100, .5, None), (100, .5, 4), (4, .1, .5), (50, .8, None), (50, .8, 5)]:
    sos = signal.butter(2, lo if hi is None else [lo, hi], btype="low" if hi is None else "band", fs=fs, output="sos")
    primitives["filters"].append(dict(fs=fs, lo=lo, hi=hi, sos=sos.tolist(), y=signal.sosfiltfilt(sos, x).tolist()))
stamps = []
true_t = np.concatenate([np.arange(0, 20, 1/50), 20 + np.arange(0, 40, 1/25)])
for i in range(0, len(true_t), 10):
    end_ts = true_t[min(i + 9, len(true_t) - 1)] + .05
    stamps.extend((end_ts - (9 - np.arange(min(10, len(true_t) - i))) / 25).tolist())
primitives.update(stamps=stamps, retimed=ppg.retime(np.array(stamps)).tolist())
flat = ppg.load_csv(REC / "flat.csv")
flat_y = ppg._bandpass(np.full(len(np.arange(0, flat.ppg_t[-1], .01)), 40000.), 100, .5, 4)
flat_d = np.diff(flat_y)
flat_skew = float(np.mean((flat_d - flat_d.mean()) ** 3) / (np.std(flat_d) ** 3 + 1e-12))
flat_y *= 1 if flat_skew >= 0 else -1
flat_prom = .25 * (np.percentile(flat_y, 95) - np.percentile(flat_y, 5))
flat_peaks = signal.find_peaks(flat_y, distance=33, prominence=flat_prom)[0]
primitives.update(constant_length=len(flat_y), constant_filtered=flat_y.tolist(), constant_skew=flat_skew,
                  constant_prominence=flat_prom, constant_peaks=flat_peaks.tolist())
dump(GOLD / "primitives.json", primitives)
dump(RES / "manifest.json", dict(sessions=session_names, diagnostics=diagnostics, python=sys.version, numpy=np.__version__, scipy=scipy.__version__,
     timezone="Asia/Kolkata", now=now, reference_sha256={str(p.relative_to(args.reference)): hashlib.sha256(p.read_bytes()).hexdigest()
     for p in [args.reference / "server/analytics" / (n + ".py") for n in ["ppg", "hrv", "rhythm", "respiration", "session", "daily"]]}))
