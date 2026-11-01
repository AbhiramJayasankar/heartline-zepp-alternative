"""Build a <2 MB fixture + Python oracle without opening the original database for writing.

Run using the reference project's venv: python make_import_fixture.py <reference-root>
The fixture keeps all tables, 3 days of activity, all non-temperature health, the
first temperature sample + its newest 3 days, and all raw transfers/workouts/cache.
The production importer does not trim anything.
"""
import sys
sys.dont_write_bytecode = True

import hashlib
import json
import pathlib
import sqlite3
from datetime import datetime


def main():
    root = pathlib.Path(sys.argv[1]).resolve()
    module = pathlib.Path(__file__).resolve().parents[1]
    assets = module / "src/androidTest/assets"
    path = assets / "watch-trimmed.db"
    assert not path.exists(), "Remove the old fixture explicitly before regenerating"
    source = sqlite3.connect((root / "data/watch.db").as_uri() + "?mode=ro", uri=True)
    target = sqlite3.connect(path)
    for name, sql in source.execute("SELECT name,sql FROM sqlite_master WHERE type='table'"):
        target.execute(sql)
        where, args = "", ()
        if name == "activity":
            cutoff = source.execute("SELECT MAX(ts)-3*86400+60 FROM activity").fetchone()[0]
            where, args = " WHERE ts >= ?", (cutoff,)
        elif name == "health":
            cutoff = source.execute("SELECT MAX(ts)-3*86400+60 FROM health WHERE type='temperature'").fetchone()[0]
            where, args = " WHERE type != 'temperature' OR ts >= ? OR ts = (SELECT MIN(ts) FROM health WHERE type='temperature')", (cutoff,)
        rows = source.execute(f"SELECT * FROM {name}{where} ORDER BY rowid", args)
        placeholders = ",".join("?" for _ in rows.description)
        target.executemany(f"INSERT INTO {name} VALUES ({placeholders})", rows)
    target.commit()
    target.execute("VACUUM")
    target.close()
    source.close()
    assert path.stat().st_size < 2_000_000, path.stat().st_size

    sys.path.insert(0, str(root))
    from server.store import Store, hr_stats, activity_summary
    from server.protocol import activity_states, valid_hr
    from server.insights import InsightsDB
    store = Store(path)
    insights = InsightsDB(store)
    activity = store.activity_since(0)
    minutes = [{"ts": r["ts"], "state": st, "steps": r["steps"], "hr": r["hr"] if valid_hr(r["hr"]) else None}
               for r, st in zip(activity, activity_states(activity, store.activity_prev_kind(activity[0]["ts"])))]
    middle = activity[len(activity) // 2]["ts"]
    oracle = {
        "timezone": str(datetime.now().astimezone().tzinfo),
        "counts": {n: store._db.execute(f"SELECT COUNT(*) FROM {n}").fetchone()[0]
                   for n, in store._db.execute("SELECT name FROM sqlite_master WHERE type='table'")},
        "hr_stats": hr_stats(store.hr_since(0)),
        "activity_summary": activity_summary(minutes),
        "activity_last_ts": store.activity_last_ts(),
        "activity_middle": middle,
        "activity_prev_kind": store.activity_prev_kind(middle),
        "health": {kind: {"count": n, "first": first, "last": last}
                   for kind, n, first, last in store._db.execute("SELECT type,COUNT(*),MIN(ts),MAX(ts) FROM health GROUP BY type")},
        "hrv_by_day": {str(d): v for d, v in insights.hrv_by_day().items()},
        "health_by_day": {k: {str(d): v for d, v in days.items()} for k, days in insights.health_by_day(0).items()},
        "latest_rhythm": insights.latest_rhythm(),
        "raw_sha256": [hashlib.sha256(data).hexdigest() if data is not None else None
                       for data, in store._db.execute("SELECT data FROM raw_fetches ORDER BY ts,rowid")],
        "fixture_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    }
    store._db.close()
    (assets / "python-oracle.json").write_text(json.dumps(oracle, indent=2), encoding="utf-8")
    print(json.dumps({"fixture_bytes": path.stat().st_size, "counts": oracle["counts"], "timezone": oracle["timezone"]}))


if __name__ == "__main__":
    main()
