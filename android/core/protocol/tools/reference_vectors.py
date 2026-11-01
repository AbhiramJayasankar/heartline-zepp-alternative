"""Regenerate JVM test oracles from the read-only Python implementation; no watch access.

Run with amazfit-gtr2e/.venv/Scripts/python.exe -B reference_vectors.py <reference-root>.
Only src/test/resources/python_reference.json in this module is written.
"""
import collections
import json
import pathlib
import random
import struct
import sys

sys.dont_write_bytecode = True
root = pathlib.Path(sys.argv[1]).resolve()
sys.path.insert(0, str(root))
from server import health as h, protocol as p
from server.audio import split_frames
from server.gps import Nmea

fx = json.loads((root / 'tests/fixtures/health_fetch.json').read_text())
out = {'health': {}, 'settings': [], 'notifications': [], 'raw': [], 'splits': [], 'gps': []}
for kind, parser in [('manual_hr', h.parse_manual_hr), ('stress', h.parse_stress), ('pai', h.parse_pai),
                     ('spo2_sleep', h.parse_spo2), ('temperature', h.parse_temperature)]:
    args = [bytes.fromhex(fx[kind]['data'])]
    if kind in ('stress', 'temperature'):
        args.append(p.parse_fetch_start(bytes.fromhex(fx[kind]['reply']))[1])
    if kind == 'temperature':
        args.append(fx[kind]['end_ts'])
    out['health'][kind] = parser(*args)
out['workout'] = h.parse_workout_summary(bytes.fromhex(fx['workout_summary']['data']))
out['track'] = h.parse_workout_details(bytes.fromhex(fx['workout_details']['data']))
out['setting_specs'] = h.SETTINGS
for spec in h.SETTINGS:
    values = ([False, True, 0, 1, '', 'false'] if spec['type'] == 'bool' else
              [100, 140, 180, '150', 150.9] if spec['type'] == 'int' else [o['value'] for o in spec['options']])
    for value in values:
        current = {'hr_alert': False, 'hr_alert_threshold': 140}
        target, command = h.setting_command(spec['key'], h.validate_setting(spec['key'], value), current)
        out['settings'].append({'key': spec['key'], 'value': value, 'current': current,
                                'target': target, 'command': command.hex()})
for kind in ['message', 'generic', 'call']:
    for title, body in [('Hi', 'there'), ('', ''), ('Mum', ''), ('T', 'é' * 400),
                        ('🙂' * 40, '🙂é中' * 200), ('a' * 31 + 'é', 'x' * 512), ('T', 'x' * 220 + '🙂')]:
        out['notifications'].append({'kind': kind, 'title': title, 'body': body,
                                     'payload': h.notification_payload(title, body, kind, 'Heartline').hex()})
rng = random.Random(20261101)
for typ in [0, 1, 3, 4, 5, 6, 7, 255]:
    for length in range(0, 64):
        packet = bytes([typ, rng.randrange(256)]) + rng.randbytes(length)
        kind, values = p.parse_raw(packet)
        if kind == 'mic':
            values = [v.hex() for v in values]
        out['raw'].append({'packet': packet.hex(), 'kind': kind, 'values': values})
for _ in range(150):
    good = (bytes([10, 8]) + bytes(9)) * rng.randrange(1, 5)
    data = rng.randbytes(rng.randrange(8)) + good + bytes([12, 8]) + rng.randbytes(rng.randrange(12))
    frames, tail, skipped = split_frames(data)
    out['splits'].append({'data': data.hex(), 'frames': [f.hex() for f in frames],
                          'tail': tail.hex(), 'skipped': skipped})
n = Nmea()
sentences = [
    '$GPGSV,2,1,03,05,45,120,38,12,10,300,,\r\n',
    '$GLGSV,1,1,01,65,20,80,30\r\n',
    '$GPGSV,2,2,03,15,50,180,41\r\n',
    '$GNRMC,215503.00,A,2830.0000,N,07700.0000,E,1.0,90.0,011126,,,A\r\n',
    '$GPGGA,215503.00,2830.0000,N,07700.0000,E,1,07,1.2,215.0,M,,M,,\r\n',
    '$GNRMC,215504.00,V,,,,,0.0,,011126,,,A\r\n',
    '$GNVTG,,,,,,,3.6,K\r\n',
    '$GPGGA,215504.00,,,,,0,00,99.9,,M,,M,,\r\n',
    '$GPGGA,215505.00,3350.1234,S,15110.5678,W,2,08,0.9,-5,M,,M,,\r\n',
    '$GPGSV,1,1,01,20,40,50,10\r\n',
    '$GPGGA,,,,,,1,03,2,20\r\n',
    '$GPGGA,,,,,,bad,03,2,20\r\n',
    '$GNRMC,996199.00,A,,,,,1.2,45,011126\r\n',
]
for text in sentences:
    updated = n.feed(text)
    out['gps'].append({'text': text, 'updated': updated, 'state': json.loads(json.dumps(n.state))})
capture = root / 'data/capture_all.json'
if capture.exists():
    counts, samples = collections.Counter(), collections.Counter()
    for _, raw in json.loads(capture.read_text()):
        kind, values = p.parse_raw(bytes.fromhex(raw))
        counts[kind] += 1
        samples[kind] += len(values)
    print('capture packets:', dict(counts), 'samples:', dict(samples))
dest = pathlib.Path(__file__).resolve().parent.parent / 'src/test/resources/python_reference.json'
dest.write_text(json.dumps(out, ensure_ascii=True, indent=2), encoding='utf-8')
print('Wrote', dest, 'raw vectors:', len(out['raw']), 'split vectors:', len(out['splits']))
