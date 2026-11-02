"""Regenerate only Task D's workout fixtures; source database is opened read-only.

Oracle: Gadgetbridge b4f1eb5a8a6bcb85e51895f083dbb98f36d2bb9d,
devices/huami/HuamiActivitySummaryParser.parseBinaryData (v515) and
service/devices/huami/HuamiActivityDetailsParser.parse/consumeHeartRate.
Offsets below follow the Java ByteBuffer reads, not Heartline's implementation.
No speed/cadence record interpretation exists in those upstream handlers.
"""
import argparse
import collections
import hashlib
import json
import math
from pathlib import Path
import sqlite3
import struct


def summary_oracle(b):
    def read(fmt, offset):
        return struct.unpack_from('<' + fmt, b, offset)[0]

    fields = {}
    for name, offset, fmt in [
        ('version', 0, 'H'), ('typeCode', 2, 'H'), ('start', 4, 'I'), ('end', 8, 'I'),
        ('baseLongitude', 12, 'i'), ('baseLatitude', 16, 'i'), ('baseAltitudeM', 20, 'i'),
        ('steps', 24, 'i'), ('activeS', 28, 'i'), ('maxLatitude', 32, 'i'), ('minLatitude', 36, 'i'),
        ('maxLongitude', 40, 'i'), ('minLongitude', 44, 'i'), ('caloriesKcal', 48, 'f'),
        ('distanceM', 52, 'f'), ('ascentM', 56, 'f'), ('descentM', 60, 'f'),
        ('maxAltitudeM', 64, 'f'), ('minAltitudeM', 68, 'f'), ('avgAltitudeM', 72, 'f'),
        ('maxSpeedMs', 76, 'f'), ('minSpeedMs', 80, 'f'), ('avgSpeedMs', 84, 'f'),
        ('minPaceSPerM', 88, 'f'), ('maxPaceSPerM', 92, 'f'), ('avgPaceSPerM', 96, 'f'),
        ('secondaryDistanceM', 124, 'f'), ('avgHr', 132, 'h'), ('avgPaceSPerKm', 134, 'h'),
        ('avgStrideCm', 136, 'h'), ('maxHr', 138, 'h'),
    ]:
        fields[name] = read(fmt, offset)
    for name, offset, scale in [
        ('maxCadence', 100, 60), ('minCadence', 104, 60), ('avgCadence', 108, 60),
        ('maxStrideCm', 112, 100), ('minStrideCm', 116, 100), ('avgStride2Cm', 120, 100),
    ]:
        # Java multiplies as float before Math.round(float).
        value = struct.unpack('<f', struct.pack('<f', read('f', offset) * scale))[0]
        fields[name] = math.floor(value + 0.5)
    for key in ['baseAltitudeM', 'maxAltitudeM', 'minAltitudeM', 'avgAltitudeM']:
        if fields[key] in [-20000, -100000, 100000]:
            fields[key] = None
    for key in ['avgHr', 'maxHr', 'avgPaceSPerKm', 'avgStrideCm', 'minPaceSPerM', 'maxPaceSPerM', 'avgPaceSPerM']:
        if fields[key] <= 0 or (key == 'minPaceSPerM' and fields[key] > 60):
            fields[key] = None
    return fields


def details_oracle(b):
    total = previous = 0
    hr, pauses, resumes = [], [], []
    counts = collections.Counter()
    for i in range(0, len(b) - 7, 8):
        kind, offset, *payload = b[i:i + 8]
        total += offset - previous if offset >= previous else offset
        previous = offset
        counts[kind] += 1
        if kind == 1:
            if payload[1:] == [0] * 5:
                if payload[0]:
                    hr.append([total, payload[0]])
            else:
                hr.extend([payload[j], payload[j + 1]] for j in [0, 2, 4] if payload[j + 1])
        elif kind == 2:
            pauses.append(total)
        elif kind == 3:
            resumes.append(total)
    return dict(hr=hr, pauses=pauses, resumes=resumes, recordCounts=dict(counts))


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('database', type=Path)
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parents[1] / 'src/test/resources/workout_ext_real.json')
    args = parser.parse_args()
    with sqlite3.connect(args.database.resolve().as_uri() + '?mode=ro', uri=True) as db:
        stored = {s: (json.loads(j), json.loads(t)) for s, j, t in db.execute('select start,data,track from workouts')}
        summaries = list(db.execute("select ts,start,data from raw_fetches where type='workouts' order by ts"))
        details = list(db.execute("select ts,start,data from raw_fetches where type='workout_details' order by ts"))
    captures = []
    for ts, cursor, b in summaries:
        expected = summary_oracle(b)
        start = expected['start']
        # Pair each duplicate capture with the closest detail fetch for the same workout start.
        detail_ts, _, detail = min((d for d in details if int(d[1]) == start), key=lambda d: abs(d[0] - ts))
        j, track = stored[start]
        d = details_oracle(detail)
        for key, value in [('avg_hr', expected['avgHr']), ('max_hr', expected['maxHr']),
                           ('calories', round(expected['caloriesKcal'])), ('distance_m', round(expected['distanceM'])),
                           ('steps', expected['steps']), ('duration_s', expected['end'] - start)]:
            assert j[key] == value, (start, key, j[key], value)
        assert d['hr'] == [[p['t'], p['hr']] for p in track], start
        if expected['typeCode'] == 2:
            assert all(1 <= expected[k] * 3.6 <= 15 for k in ['minSpeedMs', 'avgSpeedMs', 'maxSpeedMs'])
            assert all(60 <= expected[k] <= 200 for k in ['minCadence', 'avgCadence', 'maxCadence'])
        captures.append(dict(ts=ts, fetchStart=cursor, detailTs=detail_ts, summary=b.hex(), details=detail.hex(),
                             expected=expected, stored=j, detailsExpected=d))
    result = dict(source='heartline.db; raw_fetches opened mode=ro; 14 summaries and 14 details, 7 distinct workouts',
                  gadgetbridgeRevision='b4f1eb5a8a6bcb85e51895f083dbb98f36d2bb9d',
                  captures=captures, summaryFetchCount=len(summaries), detailFetchCount=len(details))
    args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(f'{len(captures)} captures checked against stored workouts.data and full HR tracks; {args.output.stat().st_size} bytes')
    print('Fixture SHA-256:', hashlib.sha256(args.output.read_bytes()).hexdigest())


if __name__ == '__main__':
    main()
