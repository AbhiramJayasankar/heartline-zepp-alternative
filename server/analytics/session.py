"""Full analysis of one raw recording, in the /api/insights/session response shape."""
import numpy as np

from . import hrv, ppg, respiration, rhythm


def analyze(rec: ppg.Recording, name: str) -> dict:
    beats = ppg.detect_beats(rec)
    iv = hrv.intervals(beats.times, beats.good)
    keep = ~(iv.ectopic | iv.artifact)
    nn = iv.rr[keep]
    # A handful of intervals, or a rate outside 35-200 bpm, almost always means missed or doubled beats.
    hr = round(float(60 / nn.mean()), 1) if len(nn) >= 10 else None
    if hr is not None and not 35 <= hr <= 200:
        hr = None
    resp = respiration.breathing_rate(
        beats.times[beats.good], beats.baseline[beats.good], beats.amplitude[beats.good], iv.t[keep], nn)
    rhythm_out = rhythm.assess(iv, hr)
    rhythm_out["notes"] = beats.notes + rhythm_out["notes"]

    mt, mv = ppg.motion_series(rec)
    step = max(1, int(np.ceil(len(mt) / 500))) if len(mt) else 1
    usable = float(nn.sum())
    return {
        "file": name,
        "start": rec.start,
        "duration_s": round(rec.duration_s, 1),
        "signal_quality": round(ppg.signal_quality(beats), 3),
        "usable_s": round(usable, 1),
        "hr_bpm": hr,
        "beats": int(len(beats.times)),
        "hrv": hrv.hrv_metrics(iv),
        "rhythm": rhythm_out,
        "respiratory_rate": resp["rate"],
        "respiration": resp["components"],
        "rr": [{"t": round(float(t), 3), "rr_ms": round(float(r) * 1000, 1), "ectopic": bool(e)}
               for t, r, e in zip(iv.t, iv.rr, iv.ectopic)],
        "ppg_preview": ppg.preview(beats),
        "preview_fs": round(ppg.WORK_FS / ppg.preview_step(len(beats.filtered)), 3) if len(beats.filtered) else None,
        "beat_times": [round(float(t), 3) for t in beats.times],
        "beat_good": [bool(g) for g in beats.good],
        "motion": [{"t": round(float(t), 2), "g": round(float(g), 4)} for t, g in zip(mt[::step], mv[::step])],
    }
