"""SQLite storage for heart-rate samples and the event log."""
import json
import pathlib
import sqlite3
import threading


class Store:
    def __init__(self, path: str | pathlib.Path):
        if str(path) != ":memory:":
            pathlib.Path(path).parent.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(str(path), check_same_thread=False)
        self._lock = threading.Lock()
        with self._lock:
            self._db.executescript(
                """
                CREATE TABLE IF NOT EXISTS hr_samples (ts REAL NOT NULL, bpm INTEGER NOT NULL);
                CREATE INDEX IF NOT EXISTS hr_ts ON hr_samples (ts);
                CREATE TABLE IF NOT EXISTS events (ts REAL NOT NULL, level TEXT NOT NULL, msg TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS activity (
                    ts INTEGER PRIMARY KEY, kind INTEGER, intensity INTEGER, steps INTEGER, hr INTEGER,
                    unknown1 INTEGER, sleep INTEGER, deep INTEGER, rem INTEGER);
                CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT);
                CREATE TABLE IF NOT EXISTS health (
                    type TEXT NOT NULL, ts INTEGER NOT NULL, value REAL, extra TEXT, PRIMARY KEY (type, ts));
                CREATE TABLE IF NOT EXISTS workouts (start INTEGER PRIMARY KEY, data TEXT NOT NULL, track TEXT);
                CREATE TABLE IF NOT EXISTS raw_fetches (
                    ts REAL NOT NULL, type TEXT NOT NULL, start REAL, reply TEXT, data BLOB);
                """
            )

    def add_hr(self, ts: float, bpm: int) -> None:
        with self._lock:
            self._db.execute("INSERT INTO hr_samples VALUES (?, ?)", (ts, bpm))
            self._db.commit()

    def hr_since(self, since: float) -> list[dict]:
        with self._lock:
            rows = self._db.execute(
                "SELECT ts, bpm FROM hr_samples WHERE ts >= ? AND bpm > 0 ORDER BY ts", (since,)
            ).fetchall()
        return [{"ts": ts, "bpm": bpm} for ts, bpm in rows]

    def add_event(self, ts: float, level: str, msg: str) -> None:
        with self._lock:
            self._db.execute("INSERT INTO events VALUES (?, ?, ?)", (ts, level, msg))
            self._db.commit()

    def events(self, limit: int) -> list[dict]:
        with self._lock:
            rows = self._db.execute(
                "SELECT ts, level, msg FROM events ORDER BY ts DESC, rowid DESC LIMIT ?", (limit,)
            ).fetchall()
        return [{"ts": ts, "level": level, "msg": msg} for ts, level, msg in rows]

    # ---- activity history ----------------------------------------------------

    ACTIVITY_COLS = ("ts", "kind", "intensity", "steps", "hr", "unknown1", "sleep", "deep", "rem")

    def upsert_activity(self, rows: list[dict]) -> int:
        """Insert or replace per-minute rows; returns how many minutes were new."""
        cols = self.ACTIVITY_COLS
        with self._lock:
            before = self._db.execute("SELECT COUNT(*) FROM activity").fetchone()[0]
            self._db.executemany(
                f"INSERT INTO activity ({','.join(cols)}) VALUES ({','.join('?' * len(cols))}) "
                f"ON CONFLICT(ts) DO UPDATE SET {','.join(f'{c}=excluded.{c}' for c in cols[1:])}",
                [tuple(r[c] for c in cols) for r in rows],
            )
            self._db.commit()
            return self._db.execute("SELECT COUNT(*) FROM activity").fetchone()[0] - before

    def activity_since(self, since: float) -> list[dict]:
        with self._lock:
            rows = self._db.execute(
                f"SELECT {','.join(self.ACTIVITY_COLS)} FROM activity WHERE ts >= ? ORDER BY ts", (since,)
            ).fetchall()
        return [dict(zip(self.ACTIVITY_COLS, r)) for r in rows]

    def activity_last_ts(self) -> int | None:
        with self._lock:
            return self._db.execute("SELECT MAX(ts) FROM activity").fetchone()[0]

    def activity_prev_kind(self, before: float) -> int | None:
        """Last masked kind before `before` that isn't a repeat marker (0 / 10)."""
        with self._lock:
            row = self._db.execute(
                "SELECT kind & 15 FROM activity WHERE ts < ? AND (kind & 15) NOT IN (0, 10) ORDER BY ts DESC LIMIT 1",
                (before,),
            ).fetchone()
        return row[0] if row else None

    # ---- stored health data (SpO2, stress, PAI, temperature, workouts...) -----------------------------------

    def add_raw_fetch(self, ts: float, kind: str, start: float | None, reply: bytes, data: bytes) -> None:
        """Keep every transferred blob: some buffers are handed over only once, so this is the only copy."""
        with self._lock:
            self._db.execute("INSERT INTO raw_fetches VALUES (?, ?, ?, ?, ?)", (ts, kind, start, reply.hex(), data))
            self._db.commit()

    def upsert_health(self, kind: str, rows: list[dict]) -> int:
        """rows: {"ts", "value", ...extras}. Returns how many (type, ts) points were new."""
        with self._lock:
            before = self._db.execute("SELECT COUNT(*) FROM health WHERE type = ?", (kind,)).fetchone()[0]
            self._db.executemany(
                "INSERT INTO health VALUES (?, ?, ?, ?) "
                "ON CONFLICT(type, ts) DO UPDATE SET value = excluded.value, extra = excluded.extra",
                [(kind, int(r["ts"]), r["value"],
                  json.dumps({k: v for k, v in r.items() if k not in ("ts", "value")})) for r in rows],
            )
            self._db.commit()
            return self._db.execute("SELECT COUNT(*) FROM health WHERE type = ?", (kind,)).fetchone()[0] - before

    def health_since(self, kind: str, since: float) -> list[dict]:
        with self._lock:
            rows = self._db.execute(
                "SELECT ts, value, extra FROM health WHERE type = ? AND ts >= ? ORDER BY ts", (kind, since)
            ).fetchall()
        return [{"ts": ts, "value": value, **(json.loads(extra) if extra else {})} for ts, value, extra in rows]

    def health_last_ts(self, kind: str) -> int | None:
        with self._lock:
            return self._db.execute("SELECT MAX(ts) FROM health WHERE type = ?", (kind,)).fetchone()[0]

    def upsert_workout(self, summary: dict, track: list[dict] | None) -> bool:
        with self._lock:
            new = self._db.execute("SELECT 1 FROM workouts WHERE start = ?", (summary["start"],)).fetchone() is None
            self._db.execute(
                "INSERT INTO workouts VALUES (?, ?, ?) ON CONFLICT(start) DO UPDATE SET data = excluded.data, "
                "track = COALESCE(excluded.track, workouts.track)",
                (summary["start"], json.dumps(summary), json.dumps(track) if track is not None else None),
            )
            self._db.commit()
            return new

    def workouts_since(self, since: float) -> list[dict]:
        with self._lock:
            rows = self._db.execute(
                "SELECT data, track FROM workouts WHERE start >= ? ORDER BY start", (since,)
            ).fetchall()
        return [{**json.loads(d), "track": json.loads(t) if t else []} for d, t in rows]

    def workout_last_start(self) -> int | None:
        with self._lock:
            return self._db.execute("SELECT MAX(start) FROM workouts").fetchone()[0]

    def set_meta(self, key: str, value: str) -> None:
        with self._lock:
            self._db.execute("INSERT OR REPLACE INTO meta VALUES (?, ?)", (key, value))
            self._db.commit()

    def get_meta(self, key: str) -> str | None:
        with self._lock:
            row = self._db.execute("SELECT value FROM meta WHERE key = ?", (key,)).fetchone()
        return row[0] if row else None


def hr_stats(samples: list[dict]) -> dict:
    bpms = [s["bpm"] for s in samples]
    if not bpms:
        return {"min": None, "max": None, "avg": None, "count": 0}
    return {"min": min(bpms), "max": max(bpms), "avg": round(sum(bpms) / len(bpms), 1), "count": len(bpms)}


SLEEP_STATES = {"sleep_light", "sleep_deep", "sleep_rem"}


def activity_summary(samples: list[dict]) -> dict:
    """Totals over per-minute samples that already carry `state` and `hr` (None when not measured)."""
    hrs = [s["hr"] for s in samples if s["hr"] is not None]
    return {
        "steps": sum(s["steps"] for s in samples),
        "hr_min": min(hrs) if hrs else None,
        "hr_avg": round(sum(hrs) / len(hrs), 1) if hrs else None,
        "hr_max": max(hrs) if hrs else None,
        "active_minutes": sum(1 for s in samples if s["steps"] > 0 or s["state"] == "active"),
        "worn_minutes": sum(1 for s in samples if s["state"] not in ("not_worn", "charging", "unknown")),
        "sleep_minutes": sum(1 for s in samples if s["state"] in SLEEP_STATES),
    }
