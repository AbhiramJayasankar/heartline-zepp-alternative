"""One gym session as an HTML page: the set log (CSV) laid over the watch's heart rate.

Usage:
  python tools/workout_report.py LOG.csv HEARTLINE.db OUT.html [--weight 79] [--age 22] [--rest 71]

LOG.csv is the per-set log (session_id, exercise, set_number, start/end_datetime_ist, weight_kg, weight_type,
reps_reported, ...). HEARTLINE.db is a copy of the phone's database: the watch workout that overlaps the sets gives
second-by-second heart rate (falling back to the per-minute history). Nothing is written except OUT.html.
"""
import argparse
import csv
import html
import json
import sqlite3
import statistics as st
from datetime import datetime, timedelta, timezone

IST = timezone(timedelta(hours=5, minutes=30))
PALETTE = ["#d1495b", "#2e86ab", "#e8a33d", "#3d9970", "#8e6cbf", "#c26b3a", "#4b7f9c"]

ap = argparse.ArgumentParser()
ap.add_argument("log"); ap.add_argument("db"); ap.add_argument("out")
ap.add_argument("--weight", type=float, default=79); ap.add_argument("--age", type=int, default=22)
ap.add_argument("--rest", type=float, default=None, help="awake resting heart rate; default: from the database")
args = ap.parse_args()


def ts(s):
    return datetime.fromisoformat(s).timestamp() if s else None


def num(s):
    try:
        return float(s)
    except (TypeError, ValueError):
        return None


rows = list(csv.DictReader(open(args.log, encoding="utf-8")))
sets = []
for r in rows:
    start, end = ts(r.get("start_datetime_ist")), ts(r.get("end_datetime_ist"))
    w = num(r.get("weight_kg"))
    assisted = r.get("weight_type") == "assistance"
    load = (args.weight - w) if assisted and w is not None else w
    reps = num(r.get("reps_reported"))
    sets.append(dict(ex=r["exercise"], n=int(float(r["set_number"])), start=start, end=end, w=w, assisted=assisted, load=load,
                     reps=reps, kind=r.get("set_type", ""), status=r.get("status", ""), failure=r.get("reached_failure") == "True",
                     diff=num(r.get("difficulty_out_of_10")), rir=num(r.get("reps_in_reserve")), rest=num(r.get("rest_before_seconds")),
                     notes=r.get("notes", ""), session=r.get("session_id", "")))
timed = [s for s in sets if s["start"]]
t0 = min(s["start"] for s in timed); t1 = max((s["end"] or s["start"]) for s in timed)

db = sqlite3.connect(f"file:{args.db}?mode=ro", uri=True)
work = None
for start, data, track in db.execute("SELECT start, data, track FROM workouts ORDER BY start"):
    d = json.loads(data)
    if d.get("start", start) <= t1 and d.get("end", start) >= t0:
        work = (d, json.loads(track) if track else [])
hr = []
if work and work[1]:
    hr = [(work[0]["start"] + p["t"], p["hr"]) for p in work[1] if p.get("hr")]
if not hr:
    hr = [(t, h) for t, h in db.execute("SELECT ts, hr FROM activity WHERE ts BETWEEN ? AND ? AND hr BETWEEN 30 AND 220", (t0 - 900, t1 + 900))]
if args.rest is None:
    # The app's awake resting heart rate is computed, not stored: use the lowest 10-minute median of still, awake minutes
    # over the last week as the same stand-in.
    acts = []
    kind = None
    for t, k, i, s_, h in db.execute("SELECT ts, kind, intensity, steps, hr FROM activity WHERE ts BETWEEN ? AND ? ORDER BY ts", (t0 - 7 * 86400, t0)):
        k &= 15
        kind = kind if k in (0, 10) else k  # 0 and 10 repeat the previous minute's kind
        # 9 light sleep, 11 deep sleep, 3 not worn, 6 charging: never "still and awake".
        acts.append((t, i if kind not in (3, 6, 9, 11) else 999, s_, h))
    # Same as the app: each day's lowest 10-minute median while still and awake, then the median across days.
    best = {}
    win = []
    for t, i, s, h in acts:
        if s == 0 and i <= 30 and h and 30 <= h <= 220:
            win = [x for x in win if t - x[0] < 600 and t - win[-1][0] <= 120] + [(t, h)] if win else [(t, h)]
            if len(win) >= 8:
                d = datetime.fromtimestamp(t, IST).date()
                best[d] = min(best.get(d, 999), st.median(x[1] for x in win))
        else:
            win = []
    args.rest = round(st.median(best.values())) if best else 70
hr_max = round(208 - 0.7 * args.age)
reserve = hr_max - args.rest
line20, line30 = args.rest + 0.2 * reserve, args.rest + 0.3 * reserve


def hr_between(a, b):
    return [h for t, h in hr if a <= t <= b]


for s in timed:
    end = s["end"] or s["start"]
    s["hr_start"] = (hr_between(s["start"] - 10, s["start"] + 5) or [None])[0]
    s["hr_peak"] = max(hr_between(s["start"], end + 30), default=None)
    after = hr_between(end + 55, end + 65)
    s["hr_60"] = after[0] if after else None

exercises = list(dict.fromkeys(s["ex"] for s in sets))
color = {e: PALETTE[i % len(PALETTE)] for i, e in enumerate(exercises)}
done = [s for s in sets if s["status"] == "completed" and s["reps"]]
working = [s for s in done if s["kind"] == "working"]
volume = sum((s["load"] or 0) * s["reps"] for s in done if s["load"])
tut = sum((s["end"] - s["start"]) for s in timed if s["end"])
rests = [s["rest"] for s in sets if s["rest"]]
hrs = [h for _, h in hr if t0 - 120 <= _ <= t1 + 120]
fmt = lambda t: datetime.fromtimestamp(t, IST).strftime("%H:%M")
day = datetime.fromtimestamp(t0, IST)

# ---- heart rate chart ----
W, H, L, R, T, B = 1000, 320, 44, 16, 46, 34
lo_t, hi_t = t0 - 240, t1 + 300
ys = [h for t, h in hr if lo_t <= t <= hi_t] or [60, 140]
lo_y, hi_y = (min(ys) // 10) * 10 - 5, (max(ys) // 10 + 1) * 10 + 5
X = lambda t: L + (t - lo_t) / (hi_t - lo_t) * (W - L - R)
Y = lambda h: T + (hi_y - h) / (hi_y - lo_y) * (H - T - B)
svg = [f'<svg viewBox="0 0 {W} {H}" role="img" aria-label="Heart rate through the session with each set shaded">']
for g in range(int(lo_y // 10 + 1) * 10, int(hi_y) + 1, 10):
    svg.append(f'<line x1="{L}" x2="{W-R}" y1="{Y(g):.1f}" y2="{Y(g):.1f}" class="grid"/><text x="{L-8}" y="{Y(g)+4:.1f}" class="ax" text-anchor="end">{g}</text>')
m = int(lo_t // 600 + 1) * 600
while m < hi_t:
    svg.append(f'<line x1="{X(m):.1f}" x2="{X(m):.1f}" y1="{T}" y2="{H-B}" class="grid"/><text x="{X(m):.1f}" y="{H-B+18}" class="ax" text-anchor="middle">{fmt(m)}</text>')
    m += 600
for s in timed:
    if s["end"]:
        svg.append(f'<rect x="{X(s["start"]):.1f}" y="{T}" width="{max(2, X(s["end"]) - X(s["start"])):.1f}" height="{H-T-B}" fill="{color[s["ex"]]}" opacity=".22"><title>{html.escape(s["ex"])} set {s["n"]}</title></rect>')
for v, name in [(line20, "20% of reserve"), (line30, "30% of reserve")]:
    if lo_y < v < hi_y:
        svg.append(f'<line x1="{L}" x2="{W-R}" y1="{Y(v):.1f}" y2="{Y(v):.1f}" class="ref"/><text x="{L+6}" y="{Y(v)-5:.1f}" class="ax lbl">{name}, {v:.0f}</text>')
pts = " ".join(f"{X(t):.1f},{Y(h):.1f}" for t, h in hr if lo_t <= t <= hi_t)
svg.append(f'<polyline points="{pts}" class="hr"/>')
for k, e in enumerate([e for e in exercises if any(s["ex"] == e and s["end"] for s in timed)]):
    es = [s for s in timed if s["ex"] == e and s["end"]]
    short = e.split(" (")[0].split(" /")[0].replace(" machine", "")
    svg.append(f'<text x="{X(es[0]["start"]):.1f}" y="{T - 22 + 12 * (k % 2)}" class="exl" fill="{color[e]}">{html.escape(short)}</text>')
svg.append(f'<text x="{L-8}" y="{T-6}" class="ax" text-anchor="end">bpm</text></svg>')

# ---- per-exercise tables ----
blocks = []
for e in exercises:
    es = [s for s in sets if s["ex"] == e]
    vol = sum((s["load"] or 0) * (s["reps"] or 0) for s in es if s["status"] == "completed" and s["load"])
    best = max((s for s in es if s["reps"] and s["load"] is not None and s["status"] == "completed"), key=lambda s: (s["load"], s["reps"]), default=None)
    trs = []
    for s in es:
        load = "–" if s["w"] is None else (f'{s["w"]:g} kg assist <span class="m">≈ {s["load"]:g} kg lifted</span>' if s["assisted"] else f'{s["w"]:g} kg')
        reps = "–" if s["reps"] is None else f'{s["reps"]:g}'
        dur = f'{s["end"] - s["start"]:.0f} s' if s["start"] and s["end"] else "–"
        rest = f'{s["rest"] / 60:.1f} min' if s["rest"] else "–"
        peak = s.get("hr_peak"); rec = (peak - s["hr_60"]) if peak and s.get("hr_60") else None
        tags = []
        if s["kind"] == "warm-up": tags.append('<span class="tag">warm-up</span>')
        if s["failure"]: tags.append('<span class="tag f">failure</span>')
        if s["status"] == "skipped": tags.append('<span class="tag">skipped</span>')
        if s["diff"]: tags.append(f'<span class="tag">{s["diff"]:g}/10</span>')
        if s["rir"] is not None: tags.append(f'<span class="tag">{s["rir"]:g} RIR</span>')
        trs.append(f'<tr><td>{s["n"]}</td><td>{load}</td><td>{reps}</td><td>{dur}</td><td>{rest}</td>'
                   f'<td>{peak or "–"}</td><td>{(f"−{rec}" if rec > 0 else f"+{-rec}" if rec < 0 else "0") if rec is not None else "–"}</td><td>{"".join(tags)} <span class="m">{html.escape("" if s["notes"] in ("Warm-up", "Failure") or __import__("re").fullmatch(r"[0-9.]+(/10| RIR)", s["notes"]) else s["notes"])}</span></td></tr>')
    head = f'<span class="m">{vol:,.0f} kg moved' + (f' · best {best["load"]:g} kg × {best["reps"]:g}' if best else "") + "</span>"
    blocks.append(f'<section class="ex"><h3><i style="background:{color[e]}"></i>{html.escape(e)} {head}</h3>'
                  '<table><thead><tr><th>Set</th><th>Load</th><th>Reps</th><th>Time</th><th>Rest before</th><th>Peak bpm</th><th>Drop in 60 s</th><th></th></tr></thead>'
                  f'<tbody>{"".join(trs)}</tbody></table></section>')

# ---- observations, all from the numbers above ----
obs = []
peaks = [(s["hr_peak"], s) for s in timed if s.get("hr_peak")]
if peaks:
    p, s = max(peaks, key=lambda x: x[0])
    obs.append(f'Highest heart rate came on {html.escape(s["ex"].lower())} set {s["n"]} ({p} bpm). Arm and back work this size rarely pushes heart rate far; this was a strength session, not cardio.')
recs = [(s["hr_peak"] - s["hr_60"]) for s in timed if s.get("hr_peak") and s.get("hr_60")]
if recs:
    obs.append(f'Heart rate fell a median {st.median(recs):.0f} bpm in the minute after each set. Bigger drops later in a session usually mean you are recovering well between sets.')
if rests:
    obs.append(f'Rest between sets ran {min(rests)/60:.1f}–{max(rests)/60:.1f} min (median {st.median(rests)/60:.1f}). For hypertrophy work 1.5–3 min is typical; the long ones were mostly exercise changes.')
fails = [s for s in done if s["failure"]]
if fails:
    obs.append(f'{len(fails)} sets went to failure: ' + ", ".join(f'{html.escape(s["ex"].lower())} {s["n"]}' for s in fails) + ". Failure on small muscles (forearms, biceps) is low-risk; keep it rarer on big lifts.")
skipped = [s for s in sets if s["status"] == "skipped"]
for s in skipped:
    obs.append(f'{html.escape(s["ex"])} was skipped: {html.escape(s["notes"])}. Worth watching if lower-back fatigue shows up again.')
in_band = sum(1 for h in hrs if h >= line20) / len(hrs) * 100 if hrs else 0
obs.append(f'{in_band:.0f}% of the session sat at or above 20% of your heart rate reserve ({line20:.0f} bpm), the line Heartline uses to count minutes inside a workout as active.')

summary = [
    ("Duration", f"{(t1 - t0) / 60:.0f} min", f"{fmt(t0)}–{fmt(t1)}"),
    ("Working sets", f"{len(working)}", f"{len(done)} sets done in all"),
    ("Volume", f"{volume:,.0f} kg", "load × reps, assisted pull-ups as bodyweight minus assistance"),
    ("Under tension", f"{tut / 60:.1f} min", f"{tut / (t1 - t0) * 100:.0f}% of the session"),
    ("Heart rate", f"{st.mean(hrs):.0f} bpm" if hrs else "–", f"range {min(hrs)}–{max(hrs)}" if hrs else ""),
    ("Watch", f'{work[0].get("calories", "–")} kcal' if work else "–", f'{work[0].get("type", "")}, {work[0].get("steps", 0)} steps' if work else "no watch workout found"),
]
cards = "".join(f'<div class="card"><div class="k">{k}</div><div class="v">{v}</div><div class="m">{m}</div></div>' for k, v, m in summary)
title = rows[0].get("session_id", "Workout").split("_", 1)[-1].replace("_", ", ").capitalize() if rows else "Workout"

page = f"""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>{html.escape(title)} · {day:%d %b}</title>
<link rel="preconnect" href="https://fonts.googleapis.com"><link href="https://fonts.googleapis.com/css2?family=Barlow+Condensed:wght@500;700&family=IBM+Plex+Sans:wght@400;500&display=swap" rel="stylesheet">
<style>
:root{{--paper:#f6f5f2;--surface:#fff;--ink:#17171a;--soft:#6b6a70;--line:#e3e1dc;--pulse:#d1495b}}
@media (prefers-color-scheme:dark){{:root{{--paper:#0f0f11;--surface:#18181b;--ink:#efeef0;--soft:#9a99a1;--line:#2a2a2f}}}}
*{{box-sizing:border-box}}body{{margin:0;background:var(--paper);color:var(--ink);font:15px/1.55 "IBM Plex Sans",system-ui,sans-serif}}
main{{max-width:1040px;margin:0 auto;padding:40px 20px 80px}}
h1{{font:700 54px/1 "Barlow Condensed","Arial Narrow",system-ui,sans-serif;margin:0 0 6px;letter-spacing:.2px}}h2{{font:700 26px/1.1 "Barlow Condensed","Arial Narrow",system-ui,sans-serif;margin:44px 0 12px}}
h3{{font:600 17px/1.3 "IBM Plex Sans",system-ui,sans-serif;margin:0 0 10px;display:flex;align-items:center;gap:10px;flex-wrap:wrap}}h3 i{{width:12px;height:12px;border-radius:3px;display:inline-block}}
.sub{{color:var(--soft);margin:0 0 28px}}.m{{color:var(--soft);font-size:13px;font-weight:400}}
.cards{{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px}}
.card{{background:var(--surface);border:1px solid var(--line);border-radius:14px;padding:14px 16px}}.k{{color:var(--soft);font-size:13px}}
.v{{font:700 32px/1.15 "Barlow Condensed","Arial Narrow",system-ui,sans-serif}}.chart{{background:var(--surface);border:1px solid var(--line);border-radius:14px;padding:12px 12px 4px;overflow-x:auto}}
svg{{width:100%;min-width:640px;height:auto;display:block}}.grid{{stroke:var(--line)}}.ax{{fill:var(--soft);font-size:12px}}
.hr{{fill:none;stroke:var(--pulse);stroke-width:1.8;stroke-linejoin:round}}.ref{{stroke:var(--soft);stroke-dasharray:4 5;opacity:.7}}.exl{{font-size:12px;font-weight:600}}.lbl{{paint-order:stroke;stroke:var(--surface);stroke-width:4px}}
.ex{{background:var(--surface);border:1px solid var(--line);border-radius:14px;padding:16px;margin:0 0 12px;overflow-x:auto}}
table{{border-collapse:collapse;width:100%;min-width:620px;font-variant-numeric:tabular-nums}}th{{text-align:left;color:var(--soft);font-weight:500;font-size:13px;padding:6px 10px 6px 0;border-bottom:1px solid var(--line)}}
td{{padding:8px 10px 8px 0;border-bottom:1px solid var(--line);vertical-align:top}}tr:last-child td{{border-bottom:0}}
.tag{{display:inline-block;font-size:12px;border:1px solid var(--line);border-radius:999px;padding:0 8px;margin:0 4px 2px 0}}.tag.f{{border-color:var(--pulse);color:var(--pulse)}}
ul.obs{{padding-left:20px;max-width:75ch}}ul.obs li{{margin:0 0 8px}}footer{{color:var(--soft);font-size:13px;margin-top:40px;max-width:75ch}}
</style></head><body><main>
<h1>{html.escape(title)}</h1>
<p class="sub">{day:%A %d %B %Y}, {fmt(t0)}–{fmt(t1)} IST. Sets from your log, heart rate from the watch.</p>
<div class="cards">{cards}</div>
<h2>Heart rate, set by set</h2>
<div class="chart">{"".join(svg)}</div>
<p class="m">Shaded bands are your sets, coloured by exercise. Dashed lines: 20% and 30% of your heart rate reserve (max {hr_max} from age, awake resting {args.rest:.0f}).</p>
<h2>What stands out</h2><ul class="obs">{"".join(f"<li>{o}</li>" for o in obs)}</ul>
<h2>Every set</h2>{"".join(blocks)}
<footer>Peak bpm is the highest reading from the start of a set to 30 s after it ends; "drop in 60 s" is that peak minus the reading a minute after the set. Sets without times (the last assisted pull-up) are listed but not drawn. Volume counts completed sets only.</footer>
</main></body></html>"""
open(args.out, "w", encoding="utf-8").write(page)
print("wrote", args.out, "sets", len(sets), "hr points", len(hr), "rest", args.rest, "max", hr_max)
