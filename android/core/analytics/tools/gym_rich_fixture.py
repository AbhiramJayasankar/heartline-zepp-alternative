"""Compact raw gyro, PPG around still stretches, accel tail, live HR and watch workouts.
Read-only input; only writes test fixtures in this checkout. No manual log is used.
Usage: python core/analytics/tools/gym_rich_fixture.py GYM_DIRECTORY
"""
import csv
import gzip
import json
import sqlite3
import sys
from pathlib import Path
import numpy as np

root = Path(sys.argv[1])
dest = Path(__file__).resolve().parents[1] / 'src/test/resources/recordings'
data = {k: [] for k in ('accel', 'gyro', 'ppg')}
for name in ('20261102-221348.csv', '20261102-231527.csv'):
    with (root / name).open() as source:
        for row in csv.DictReader(source):
            if row['kind'] in data:
                data[row['kind']].append([float(row['ts'])] +
                    ([float(row['ppg'])] if row['kind'] == 'ppg' else [float(row[c]) for c in ('ax', 'ay', 'az')]))
data = {k: np.array(v) for k, v in data.items()}
start = json.loads((dest / 'gym_20261102_python.json').read_text())['start']
end = max(v[-1, 0] for v in data.values())
n = int(np.ceil(end - start))
acc = data['accel']; gyro = data['gyro']
def bins(arr, vals):
    sec = np.floor(arr[:, 0] - start).astype(int)
    ok = (sec >= 0) & (sec < n)
    count = np.bincount(sec[ok], minlength=n)
    return np.bincount(sec[ok], vals[ok], minlength=n) / np.maximum(count, 1), count
mag = np.linalg.norm(acc[:, 1:], axis=1)
m, count = bins(acc, mag); m2, _ = bins(acc, mag**2)
gm, gc = bins(gyro, np.linalg.norm(gyro[:, 1:] - np.median(gyro[:, 1:], axis=0), axis=1))
still = (count >= 15) & (gc >= 15) & (np.sqrt(np.maximum(0, m2-m*m)) < .03) & (gm < .6*np.median(gm[gc >= 15]))
use = np.convolve(still, np.ones(3), 'same') == 3
wins = []; a = 0
while a < n:
    if not use[a]: a += 1; continue
    b = a
    while b < n and (use[b] or (b+2 < n and use[b:b+3].sum() >= 2)): b += 1
    if b-a >= 20: wins.append((a,b))
    a = b
# Keep entire continuous PPG runs around the windows (padding prevents filter-edge artifacts).
# Arrival stamps are retained; the test reconstructs the same per-run clock as the engine.
ppg = data['ppg']; keep = np.zeros(len(ppg), bool)
for a,b in sorted(wins, key=lambda w:w[1]-w[0], reverse=True)[:40]:
    keep |= (ppg[:,0] >= start+a-5) & (ppg[:,0] < start+b+5)
lines = ['ts,kind,ppg,ax,ay,az\n']
for t,x,y,z in gyro: lines.append(f'{t:.6f},gyro,,{x:g},{y:g},{z:g}\n')
for t,x in ppg[keep]: lines.append(f'{t:.6f},ppg,{x:g},,,\n')
# Tail not present in the frozen Task F accel fixture, retained at full precision.
for t,x,y,z in acc[acc[:,0] >= 1793643660]: lines.append(f'{t:.6f},accel,,{x:.9g},{y:.9g},{z:.9g}\n')
path = dest / 'gym_20261102_rich.csv.gz'
path.write_bytes(gzip.compress(''.join(lines).encode(), compresslevel=9, mtime=0))
db = sqlite3.connect(f'file:{root.as_posix()}/heartline.db?mode=ro', uri=True)
hr = db.execute('select ts,bpm from hr_samples where ts >= ? and ts <= ? order by ts', (start,end)).fetchall()
workouts = [json.loads(r[0]) for r in db.execute('select data from workouts where start <= ? order by start',(end,))
            if json.loads(r[0]).get('end',0) >= start]
(dest / 'gym_20261102_hr.json').write_text(json.dumps(dict(start=start,end=end,hr=hr,workouts=workouts),separators=(',',':'))+'\n')
print('bytes',path.stat().st_size,'still windows',len(wins),'PPG rows',int(keep.sum()),'HR',len(hr))
