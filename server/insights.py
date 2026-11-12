"""/api/profile and /api/insights/*: analyses of raw recordings and daily scores.

Include it in app.py with one line inside create_app():
    app.include_router(make_router(store, watch, hub.publish, ROOT / "data" / "raw"))
"""
import asyncio
import json
import pathlib
import time
from datetime import date, datetime, timedelta
from statistics import median

from fastapi import APIRouter, Body, HTTPException
from fastapi.responses import JSONResponse

from . import protocol
from .analytics import daily, ppg, session

PROFILE_KEYS = {"age": int, "sex": str, "weight_kg": float, "height_cm": float, "max_hr": int, "resting_hr": int}
MEASURE_SECONDS = (60, 120, 300)
_sleep = asyncio.sleep          # replaced in tests


class InsightsDB:
    """Tables owned by the insights module, created on the shared Store connection."""

    def __init__(self, store):
        self.store = store
        with store._lock:
            store._db.executescript(
                """
                CREATE TABLE IF NOT EXISTS insight_sessions (
                    file TEXT PRIMARY KEY, mtime REAL, start REAL, duration_s REAL, quality REAL,
                    usable_s REAL, hr REAL, rmssd REAL, sdnn REAL, rhythm TEXT, resp REAL, result TEXT);
                """
            )
            store._db.commit()

    def cached(self, name: str, mtime: float) -> dict | None:
        with self.store._lock:
            row = self.store._db.execute(
                "SELECT result FROM insight_sessions WHERE file = ? AND mtime = ?", (name, mtime)).fetchone()
        return json.loads(row[0]) if row else None

    def save(self, name: str, mtime: float, r: dict) -> None:
        with self.store._lock:
            self.store._db.execute(
                "INSERT OR REPLACE INTO insight_sessions VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                (name, mtime, r["start"], r["duration_s"], r["signal_quality"], r["usable_s"], r["hr_bpm"],
                 r["hrv"]["rmssd_ms"], r["hrv"]["sdnn_ms"], r["rhythm"]["status"], r["respiratory_rate"],
                 json.dumps(r)))
            self.store._db.commit()

    def hrv_by_day(self) -> dict:
        """Median RMSSD per day over measurements good enough to trust (SQI >= 0.7, >= 45 s usable)."""
        with self.store._lock:
            rows = self.store._db.execute(
                "SELECT start, rmssd FROM insight_sessions WHERE rmssd IS NOT NULL AND quality >= 0.7 "
                "AND usable_s >= 45").fetchall()
        by = {}
        for start, rmssd in rows:
            by.setdefault(datetime.fromtimestamp(start).date(), []).append(rmssd)
        return {d: round(median(v), 1) for d, v in by.items()}

    def latest_rhythm(self) -> dict | None:
        with self.store._lock:
            row = self.store._db.execute(
                "SELECT rhythm, start FROM insight_sessions WHERE quality >= 0.5 ORDER BY start DESC LIMIT 1").fetchone()
        return {"status": row[0], "start": row[1]} if row else None

    def health_by_day(self, since: float) -> dict:
        """Per day: the watch's own health series (lowest resting HR, last PAI, mean of the rest), if the health tables exist.
        Feature-detected: a table `health` with columns (type|kind|series, ts, value)."""
        with self.store._lock:
            tables = {r[0] for r in self.store._db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            if "health" not in tables:
                return {}
            cols = [r[1] for r in self.store._db.execute("PRAGMA table_info(health)")]
            kind = next((c for c in ("type", "kind", "series", "name") if c in cols), None)
            if not kind or "ts" not in cols or "value" not in cols:
                return {}
            rows = self.store._db.execute(
                f"SELECT {kind}, ts, value FROM health WHERE ts >= ? AND value IS NOT NULL", (since,)).fetchall()
        acc: dict = {}
        for k, ts, v in rows:
            acc.setdefault(k, {}).setdefault(datetime.fromtimestamp(ts).date(), []).append((ts, float(v)))
        out = {}
        for k, by in acc.items():
            # PAI is a running count through the day: its latest record is the day's PAI.
            if k == "resting_hr": agg = lambda xs: min(v for _, v in xs)
            elif k == "pai": agg = lambda xs: max(xs)[1]
            else: agg = lambda xs: sum(v for _, v in xs) / len(xs)
            out[k] = {d: round(agg(v), 1) for d, v in by.items()}
        return out


def load_profile(store) -> dict:
    raw = store.get_meta("profile")
    prof = json.loads(raw) if raw else {}
    return {k: prof.get(k) for k in PROFILE_KEYS}


def make_router(store, watch, publish, raw_dir: pathlib.Path) -> APIRouter:
    router = APIRouter()
    db = InsightsDB(store)
    raw_dir = pathlib.Path(raw_dir)

    def event(level: str, msg: str) -> None:
        ts = time.time()
        store.add_event(ts, level, msg)
        if publish:
            publish({"type": "event", "ts": ts, "level": level, "msg": msg})

    def recordings() -> list[pathlib.Path]:
        return sorted(raw_dir.glob("*.csv"), key=lambda p: p.name, reverse=True) if raw_dir.exists() else []

    def analyze_file(path: pathlib.Path) -> dict:
        mtime = path.stat().st_mtime
        hit = db.cached(path.name, mtime)
        if hit:
            return hit
        result = session.analyze(ppg.load_csv(path), path.name)
        db.save(path.name, mtime, result)
        return result

    @router.get("/api/profile")
    async def get_profile():
        return load_profile(store)

    @router.post("/api/profile")
    async def set_profile(body: dict = Body(...)):
        prof = load_profile(store)
        for k, v in body.items():
            if k not in PROFILE_KEYS:
                continue
            if v in (None, ""):
                prof[k] = None
                continue
            if k == "sex" and v not in ("male", "female"):
                return JSONResponse({"error": "sex must be 'male' or 'female'"}, status_code=422)
            try:
                prof[k] = PROFILE_KEYS[k](v)
            except (TypeError, ValueError):
                return JSONResponse({"error": f"invalid value for {k}"}, status_code=422)
        store.set_meta("profile", json.dumps(prof))
        return prof

    @router.get("/api/insights/recordings")
    async def list_recordings():
        out = []
        for p in recordings():
            n, first, last = 0, None, None
            with p.open() as fh:
                next(fh, None)
                for line in fh:
                    parts = line.split(",", 3)
                    if len(parts) < 3:
                        continue
                    ts = float(parts[0])
                    first = ts if first is None else first
                    last = ts
                    n += parts[1] == "ppg"
            out.append({"file": p.name, "start": first, "duration_s": round(last - first, 1) if first else 0.0,
                        "ppg_samples": n})
        return {"recordings": out}

    @router.get("/api/insights/session")
    async def get_session(file: str = "latest"):
        files = recordings()
        if not files:
            return JSONResponse({"error": "No recordings yet. Start raw sensors or run a measurement."},
                                status_code=404)
        if file == "latest":
            path = files[0]
        else:
            path = raw_dir / pathlib.Path(file).name
            if not path.exists():
                return JSONResponse({"error": f"No recording named {file}"}, status_code=404)
        return await asyncio.to_thread(analyze_file, path)

    @router.post("/api/insights/measure")
    async def measure(body: dict = Body(default={})):
        seconds = int(body.get("seconds", 60))
        if seconds not in MEASURE_SECONDS:
            return JSONResponse({"error": f"seconds must be one of {MEASURE_SECONDS}"}, status_code=422)
        if watch.status().get("connection") != "connected":
            return JSONResponse({"error": "The watch is not connected"}, status_code=409)
        if watch.status().get("raw", {}).get("streaming"):
            return JSONResponse({"error": "Raw sensors are already streaming; stop them first"}, status_code=409)
        try:
            await watch.raw_start()
            name = pathlib.Path(watch.status()["raw"]["file"]).name
            event("info", f"Measuring for {seconds} s. Sit still with your arm resting.")
            left = seconds
            while left > 0:
                step = min(10, left)
                await _sleep(step)
                left -= step
                if left:
                    event("info", f"Measuring: {left} s left")
        except Exception as e:
            try:
                await watch.raw_stop()
            except Exception:
                pass
            return JSONResponse({"error": str(e) or type(e).__name__}, status_code=500)
        await watch.raw_stop()
        result = await asyncio.to_thread(analyze_file, raw_dir / name)
        hr = f"{result['hr_bpm']:.0f} bpm" if result["hr_bpm"] else "no heart rate"
        rm = result["hrv"]["rmssd_ms"]
        event("info", f"Measurement done: {hr}, HRV {rm:.0f} ms, rhythm {result['rhythm']['status']}"
              if rm is not None else f"Measurement done: {hr}, rhythm {result['rhythm']['status']}")
        return result

    @router.get("/api/insights/daily")
    async def get_daily(days: int = 14):
        days = max(1, min(days, 90))
        return await asyncio.to_thread(compute_daily, days)

    def compute_daily(days: int) -> dict:
        since = (datetime.combine(date.today() - timedelta(days=max(days, 35) + 1), datetime.min.time())).timestamp()
        rows = store.activity_since(since)
        states = protocol.activity_states(rows, store.activity_prev_kind(since))
        minutes = [{"ts": r["ts"], "state": st, "hr": r["hr"] if protocol.valid_hr(r["hr"]) else None,
                    "steps": r["steps"]} for r, st in zip(rows, states)]
        return daily.compute(minutes, load_profile(store), days, hrv_by_day=db.hrv_by_day(),
                             health=db.health_by_day(since), rhythm_latest=db.latest_rhythm())

    return router
