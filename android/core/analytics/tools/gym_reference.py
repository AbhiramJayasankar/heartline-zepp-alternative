"""Rebuild the accelerometer-only fixture and run the supplied gym prototype.

Python-only verification tool; requires numpy/scipy, never used by the Android app.
Usage: python gym_reference.py GYM_DIRECTORY WORKOUT_LOG [--gait GAIT_JSON]
The gait JSON contains [start,end] offsets emitted by the unchanged Kotlin gait classifier;
without --gait, reuse the mask from the checked-in oracle.
"""
import argparse
import csv
import datetime as dt
import gzip
import importlib.util
import json
from pathlib import Path

import numpy as np


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('gym', type=Path)
    parser.add_argument('log', type=Path)
    parser.add_argument('--gait', type=Path)
    args = parser.parse_args()
    resources = Path(__file__).resolve().parents[1] / 'src/test/resources/recordings'
    spec = importlib.util.spec_from_file_location('prototype', args.gym / 'prototype_detector.py')
    prototype = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(prototype)
    # Preserve the reference itself so the oracle remains reproducible without the scratch directory.
    (Path(__file__).parent / 'gym_prototype.py').write_text(
        (args.gym / 'prototype_detector.py').read_text(), encoding='utf-8')
    cutoff = dt.datetime.fromisoformat('2026-11-02T23:51:00+05:30').timestamp()
    rows = []
    for name in ('20261102-221348.csv', '20261102-231527.csv'):
        with (args.gym / name).open() as source:
            rows.extend(r for r in csv.DictReader(source) if r['kind'] == 'accel' and float(r['ts']) < cutoff)
    # Original timestamps/axis values, no decimation or quantization. Fixed mtime makes regeneration stable.
    data = 'ts,kind,ppg,ax,ay,az\n' + ''.join(
        ','.join(r[c] for c in ('ts', 'kind', 'ppg', 'ax', 'ay', 'az')) + '\n' for r in rows)
    path = resources / 'gym_20261102.csv.gz'
    path.write_bytes(gzip.compress(data.encode(), compresslevel=9, mtime=0))
    accel = np.array([[float(r[c]) for c in ('ts', 'ax', 'ay', 'az')] for r in rows])
    t0 = accel[0, 0]
    logged = []
    with args.log.open(encoding='utf-8-sig') as source:
        for row in csv.DictReader(source):
            if row['date'] != '2026-11-02':
                continue
            start = row['start_time'] or None
            # Owner-authorized interval for the second pull-up (only its end was logged).
            if row['row_id'] == '40':
                start = '2026-11-02T22:15:00+05:30'
            logged.append(dict(row=int(row['row_id']), exercise=row['exercise'], type=row['exercise_type'],
                set=int(row['set_number']), start=start, end=row['end_time'],
                reps=float(row['reps']) if row['reps'] else None, load_kg=float(row['load_kg']) if row['load_kg'] else None))
    (resources / 'gym_20261102_log.json').write_text(json.dumps(logged, indent=2) + '\n')
    gait = json.loads(args.gait.read_text()) if args.gait else json.loads(
        (resources / 'gym_20261102_python.json').read_text())['gait']
    # Apply Heartline's existing coverage rule around reconnect/stall gaps. The supplied prototype
    # is unchanged; each contiguous span is analyzed independently, then mapped to capture time.
    sets = []
    for span in np.split(accel, np.where(np.diff(accel[:, 0]) > .5)[0] + 1):
        origin, scores = prototype.periodicity(span)
        off = origin - t0
        excluded = np.array([any(a <= off + s < b for a, b in gait) for s in range(len(scores))])
        for a, b in prototype.detect_sets(scores, excluded):
            reps, period = prototype.count_reps(span, origin, a, b)
            sets.append(dict(start=off+a, end=off+b, reps=reps, tempo=period))
    result = dict(start=t0, rows=len(rows), numpy=np.__version__, gait=gait, sets=sets)
    (resources / 'gym_20261102_python.json').write_text(json.dumps(result, indent=2) + '\n')
    print(f'{len(rows)} accel rows; {path.stat().st_size} gzip bytes; {len(logged)} logged workout rows')
    for s in sets:
        clock = lambda sec: dt.datetime.fromtimestamp(t0 + sec, dt.timezone(dt.timedelta(hours=5.5))).strftime('%H:%M:%S')
        print(f"{clock(s['start'])}-{clock(s['end'])}: {s['reps']} reps, {s['tempo']:.2f} s")


if __name__ == '__main__':
    main()
