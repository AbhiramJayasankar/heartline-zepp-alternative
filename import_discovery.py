"""One-off: load the buffers captured during discovery into a database.

Temperature, stress, SpO2 (sleep) and statistics are handed over by the watch only once, so the discovery runs
of 2026-11-01 hold the only copy of what was on the watch before the dashboard started syncing them.
Usage: python import_discovery.py [path/to/watch.db]
"""
import json
import pathlib
import sys

from server import health as h
from server.protocol import parse_fetch_start
from server.store import Store

FIRST_TRANSFER_END = 1793481707  # 02:51:47 IST: where the next temperature fetch said the buffer resumed

db = sys.argv[1] if len(sys.argv) > 1 else "data/watch.db"
store = Store(db)
disc = pathlib.Path("discovery")
added = {}


def load(kind, entry, end_ts):
    reply, data = bytes.fromhex(entry["reply"]), bytes.fromhex(entry.get("data") or "")
    if not data:
        return
    start = parse_fetch_start(reply)[1]
    store.add_raw_fetch(end_ts, kind, start, reply, data)
    rows = {"temperature": lambda: h.parse_temperature(data, start, end_ts),
            "stress": lambda: h.parse_stress(data, start),
            "spo2_sleep": lambda: h.parse_spo2(data)}.get(kind, lambda: [])()
    added[kind] = added.get(kind, 0) + (store.upsert_health(kind, rows) if rows else 0)


first = json.loads((disc / "first_fetch_7d.json").read_text())
load("temperature", first["temperature"], FIRST_TRANSFER_END)
load("stress", first["stress_auto"], FIRST_TRANSFER_END)
load("spo2_sleep", first["spo2_sleep"], FIRST_TRANSFER_END)
load("statistics", first["statistics"], FIRST_TRANSFER_END)

second = json.loads((disc / "second_fetch_7d.json").read_text())
load("stress", second["stress_auto"], FIRST_TRANSFER_END + 600)

twice = disc / "temp_twice.json"
if twice.exists():
    t = json.loads(twice.read_text())
    if t["B"].get("data"):
        load("temperature", t["B"], t["tb"])

print(db, added)
