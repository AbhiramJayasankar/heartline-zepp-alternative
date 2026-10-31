"""SQLite storage for heart-rate samples and the event log."""
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


def hr_stats(samples: list[dict]) -> dict:
    bpms = [s["bpm"] for s in samples]
    if not bpms:
        return {"min": None, "max": None, "avg": None, "count": 0}
    return {"min": min(bpms), "max": max(bpms), "avg": round(sum(bpms) / len(bpms), 1), "count": len(bpms)}
