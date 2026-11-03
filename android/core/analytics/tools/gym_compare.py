"""Replay the unmodified Python prototype on the checked-in gzip fixture and compare with Kotlin.

Run :core:analytics:test first, then `python core/analytics/tools/gym_compare.py` from android/.
No scratch recordings or workout log are needed. numpy/scipy are verification-only prerequisites.
After a deliberate change to gym_prototype.py, `--write` stores its output as the new oracle (then rerun the tests).
"""
import csv
import sys
import datetime as dt
import gzip
import json
from pathlib import Path

import numpy as np
import gym_prototype as prototype


root = Path(__file__).resolve().parents[1]
resources = root / 'src/test/resources/recordings'
reference = json.loads((resources / 'gym_20261102_python.json').read_text())
with gzip.open(resources / 'gym_20261102.csv.gz', 'rt') as source:
    accel = np.array([[float(r[c]) for c in ('ts', 'ax', 'ay', 'az')] for r in csv.DictReader(source)])
gait = json.loads((root / 'build/gym_gait.json').read_text())
assert gait == reference['gait'], 'The gait exclusion mask changed; inspect before regenerating the oracle.'
sets = []
for span in np.split(accel, np.where(np.diff(accel[:, 0]) > .5)[0] + 1):
    origin, scores = prototype.periodicity(span)
    off = origin - accel[0, 0]
    excluded = np.array([any(a <= off + s < b for a, b in gait) for s in range(len(scores))])
    for a, b in prototype.detect_sets(scores, excluded):
        reps, tempo = prototype.count_reps(span, origin, a, b)
        sets.append(dict(start=off + a, end=off + b, reps=reps, tempo=tempo))
if '--write' in sys.argv:
    reference['sets'] = sets
    (resources / 'gym_20261102_python.json').write_text(json.dumps(reference, indent=2) + '\n')
    print(f'Wrote {len(sets)} oracle sets; rerun :core:analytics:test, then this script without --write.')
    sys.exit()
assert sets == reference['sets'], 'Python output differs from the checked-in oracle.'
with (root / 'build/gym_kotlin.csv').open() as source:
    kotlin = list(csv.DictReader(source))
assert len(kotlin) == len(sets)
log = json.loads((resources / 'gym_20261102_log.json').read_text())
remaining = [r for r in log if r['type'] != 'cardio' and r['start']]
def offset(iso):
    return dt.datetime.fromisoformat(iso).timestamp() - accel[0, 0]
def clock(second):
    return dt.datetime.fromtimestamp(accel[0, 0] + second, dt.timezone(dt.timedelta(hours=5.5))).strftime('%H:%M:%S')
lines = ['| # | Python interval IST | Kotlin interval IST | Reps Python/Kotlin | Logged reps | Tempo s |',
         '|---|---|---|---|---|---|']
errors = []
boundary_error = 0
for i, (p, k) in enumerate(zip(sets, kotlin), 1):
    a, b = float(k['start']), float(k['end'])
    boundary_error = max(boundary_error, abs(p['start'] - a), abs(p['end'] - b))
    assert abs(p['start'] - a) <= 2 and abs(p['end'] - b) <= 2, (p, k)
    assert p['reps'] == int(k['reps']), (p, k)
    assert abs(p['tempo'] - float(k['tempo'])) <= .04 + 1e-12, (p, k)
    match = max(remaining, key=lambda r: min(b, offset(r['end'])) - max(a, offset(r['start'])), default=None)
    if match and min(b, offset(match['end'])) > max(a, offset(match['start'])):
        errors.append(abs(p['reps'] - match['reps']))
        remaining.remove(match)
    else:
        match = None
    logged = f"{match['reps']:g}" if match else 'unmatched'
    lines.append(f"| {i} | {clock(p['start'])}–{clock(p['end'])} | {clock(a)}–{clock(b)} | {p['reps']}/{k['reps']} | {logged} | {p['tempo']:.2f} |")
lines += ['', f'{len(sets)} sets; maximum boundary difference {boundary_error:g} s; all rep counts identical.',
          f'{len(errors)} matches / 27 resistance sets (including the pre-capture first set); '
          f'recall {len(errors)/27:.4f}; precision {len(errors)/len(sets):.4f}; '
          f'within 2 reps {sum(e<=2 for e in errors)}/{len(errors)}; MAE {np.mean(errors):.4f}.']
report = '\n'.join(lines) + '\n'
(root / 'build/gym_comparison.md').write_text(report, encoding='utf-8')
print(report)
