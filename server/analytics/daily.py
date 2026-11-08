"""Daily scores from per-minute history: sleep, resting HR, zones, load/strain, readiness, trends.

Inputs are plain lists so this stays pure: `minutes` = [{"ts", "state", "hr" (int|None), "steps"}] in
time order (state from protocol.activity_states), `profile` = /api/profile dict, `hrv_by_day` and
`health` = optional per-day values from measurements and the watch's own health series. Anything that
cannot be computed is None, never filled in. Method notes and sources: METHODS.md.
"""
import math
from collections import defaultdict
from datetime import date, datetime, timedelta
from statistics import mean, median, pstdev

SLEEP = {"sleep_light", "sleep_deep", "sleep_rem"}
NO_DATA = {"not_worn", "charging", "unknown"}
GAP_MIN = 30                  # awake minutes allowed inside one sleep block
NIGHT_START_H = 18            # a night for date D runs from D-1 18:00 to D 18:00
ZONES = ((0.5, 0.6), (0.6, 0.7), (0.7, 0.8), (0.8, 0.9), (0.9, 10.0))
STRAIN_K = 120.0              # strain = 21 * (1 - exp(-TRIMP / K)); heuristic calibration
MIN_NIGHT = 60                # a main block with less sleep than this is not treated as a night
KCAL_FROM = 0.6               # Keytel is an exercise equation: count minutes from 60% HRmax


def day_of(ts: float) -> date:
    return datetime.fromtimestamp(ts).date()


def hr_max_of(profile: dict) -> int | None:
    if profile.get("max_hr"):
        return int(profile["max_hr"])
    if profile.get("age"):
        return round(208 - 0.7 * profile["age"])        # Tanaka et al. 2001
    return None


# ---- sleep -------------------------------------------------------------------

def sleep_blocks(minutes: list[dict]) -> list[dict]:
    """Group sleep minutes into blocks, bridging up to GAP_MIN awake minutes; not-worn breaks a block."""
    blocks, cur, gap = [], None, 0
    for m in minutes:
        st = m["state"]
        if st in SLEEP:
            if cur is None:
                cur = {"start": m["ts"], "end": m["ts"], "rows": []}
            cur["rows"].extend(cur.pop("pending", []))
            cur["rows"].append(m)
            cur["end"] = m["ts"]
            gap = 0
        elif cur is not None:
            gap += 1
            if st in NO_DATA or gap > GAP_MIN:
                cur.pop("pending", None)
                blocks.append(cur)
                cur, gap = None, 0
            else:
                cur.setdefault("pending", []).append(m)
    if cur is not None:
        cur.pop("pending", None)
        blocks.append(cur)
    for b in blocks:
        rows = b["rows"]
        b["deep"] = sum(r["state"] == "sleep_deep" for r in rows)
        b["rem"] = sum(r["state"] == "sleep_rem" for r in rows)
        b["light"] = sum(r["state"] == "sleep_light" for r in rows)
        b["asleep"] = b["deep"] + b["rem"] + b["light"]
        b["awake"] = len(rows) - b["asleep"]
    return blocks


NIGHT_JOIN_S = 2 * 3600   # blocks this close to the main one are the same night (a long wake in the small hours)
JOIN_MIN_ASLEEP = 30      # ...but a shorter block only joins within JOIN_NEAR_S: minutes marked as sleep an hour
JOIN_NEAR_S = 1800        # or two before bed are usually sitting still, not a first sleep


def night_like(b: dict) -> bool:
    """Sleep that can be a night: >= 3 h, or any part of it between midnight and 8:00. Naps are neither."""
    if b["asleep"] >= 180:
        return True
    for ts in (b["start"], b["end"]):
        mid = datetime.combine(datetime.fromtimestamp(ts).date(), datetime.min.time()).timestamp()
        if b["start"] < mid + 8 * 3600 and b["end"] >= mid:
            return True
    return False


def _gap(a: dict, b: dict) -> float:
    return max(0, a["start"] - b["end"], b["start"] - a["end"])


def night_for(blocks: list[dict], d: date, last_ts: float | None = None) -> tuple[dict | None, int]:
    """The night ending on d: the main night-like block ending in d's night window, merged with the blocks chained to
    it (within NIGHT_JOIN_S, or JOIN_NEAR_S for a block under JOIN_MIN_ASLEEP), and its total sleep minutes. No night if the main block is under MIN_NIGHT minutes. For the day
    still in progress (last_ts inside the window, before noon), a night only counts once a block of >= 3 h has ended
    >= 30 min before the latest data, so a night that is still going on is not scored as a short one."""
    lo = datetime.combine(d - timedelta(days=1), datetime.min.time()).timestamp() + NIGHT_START_H * 3600
    hi = lo + 86400
    inside = [b for b in blocks if lo <= b["end"] < hi]
    nights = [b for b in inside if night_like(b)]
    if not nights:
        return None, 0
    main = max(nights, key=lambda b: b["asleep"])
    if main["asleep"] < MIN_NIGHT:
        return None, 0
    if last_ts is not None and lo <= last_ts < hi and datetime.fromtimestamp(last_ts).hour < 12:
        if main["asleep"] < 180 or last_ts - main["end"] < 1800:
            return None, 0
    part, grew = [main], True
    while grew:
        grew = False
        for b in inside:
            reach = NIGHT_JOIN_S if b["asleep"] >= JOIN_MIN_ASLEEP else JOIN_NEAR_S
            if all(b is not p for p in part) and any(_gap(p, b) <= reach for p in part):
                part.append(b)
                grew = True
    if len(part) > 1:
        # One stretch from first sleep to last: window, stages and total agree; awake between blocks is awake in bed.
        part.sort(key=lambda b: b["start"])
        n = {"start": part[0]["start"], "end": max(b["end"] for b in part), "rows": [r for b in part for r in b["rows"]]}
        for k in ("deep", "rem", "light", "asleep"):
            n[k] = sum(b[k] for b in part)
        n["awake"] = int((n["end"] - n["start"]) / 60 + 1) - n["asleep"]
        main = n
    return main, main["asleep"]


def sleep_score(asleep: int, efficiency: float, deep: int, rem: int) -> int:
    """0-100: duration (50, full at 8 h), efficiency (20, 70->95%), deep share (15, full at 13%),
    REM share (15, full at 20%). Heuristic weights in the spirit of Fitbit/Oura sleep scores."""
    if asleep <= 0:
        return 0
    s = 50 * min(1.0, asleep / 480)
    s += 20 * min(1.0, max(0.0, (efficiency - 70) / 25))
    s += 15 * min(1.0, deep / asleep / 0.13)
    s += 15 * min(1.0, rem / asleep / 0.20)
    return round(s)


def resting_hr_from(rows: list[dict]) -> int | None:
    """Lowest 30-minute rolling median of valid HR (needs >= 15 readings in the window)."""
    hr = [(r["ts"], r["hr"]) for r in rows if r["hr"]]
    if len(hr) < 15:
        return None
    best, j = None, 0
    for i in range(len(hr)):
        while hr[i][0] - hr[j][0] >= 1800:
            j += 1
        if i - j + 1 >= 15:
            v = median(h for _, h in hr[j:i + 1])
            best = v if best is None else min(best, v)
    return round(best) if best is not None else None


# ---- load --------------------------------------------------------------------

def zone_minutes(hrs: list[int], hr_max: int) -> dict:
    z = {f"z{i + 1}": 0 for i in range(5)}
    for h in hrs:
        f = h / hr_max
        for i, (a, b) in enumerate(ZONES):
            if a <= f < b:
                z[f"z{i + 1}"] += 1
    return z


def banister_trimp(hrs: list[int], hr_rest: float, hr_max: float, sex: str | None) -> float:
    """Banister TRIMP per minute: HRr * a * e^(b * HRr); counted only from 50% HRmax (exercise range)."""
    a, b = (0.86, 1.67) if sex == "female" else (0.64, 1.92)
    total = 0.0
    for h in hrs:
        if h < 0.5 * hr_max or hr_max <= hr_rest:
            continue
        r = min(1.0, max(0.0, (h - hr_rest) / (hr_max - hr_rest)))
        total += r * a * math.exp(b * r)
    return total


def strain_of(trimp: float) -> float:
    return round(21 * (1 - math.exp(-trimp / STRAIN_K)), 1)


def keytel_kcal(hrs: list[int], profile: dict, hr_max: int) -> float | None:
    """Keytel et al. 2005 energy expenditure from HR, for minutes in the exercise range (>= 60% HRmax)."""
    age, w, sex = profile.get("age"), profile.get("weight_kg"), profile.get("sex")
    if not (age and w and sex):
        return None
    kcal = 0.0
    for h in hrs:
        if h < KCAL_FROM * hr_max:
            continue
        if sex == "male":
            kj = -55.0969 + 0.6309 * h + 0.1988 * w + 0.2017 * age
        else:
            kj = -20.4022 + 0.4472 * h - 0.1263 * w + 0.074 * age
        kcal += max(0.0, kj / 4.184)
    return round(kcal)


# ---- regularity, fitness -----------------------------------------------------

def sleep_regularity(minutes: list[dict]) -> float | None:
    """Sleep Regularity Index (Phillips et al. 2017): -100 + 200 * P(same sleep/wake state 24 h apart),
    over minute pairs where both minutes have data. Needs >= 6 h of paired minutes."""
    state = {m["ts"] // 60: (m["state"] in SLEEP) for m in minutes if m["state"] not in NO_DATA}
    same = total = 0
    for k, s in state.items():
        o = state.get(k + 1440)
        if o is not None:
            total += 1
            same += s == o
    if total < 360:
        return None
    return round(-100 + 200 * same / total, 1)


def awake_resting_hr_from(rows: list[dict]) -> int | None:
    """Lowest 10-minute median HR while awake and still (worn, no steps, intensity <= 30; >= 8 readings; runs break at gaps > 2 min)."""
    still = [m for m in rows if m["state"] not in SLEEP and m["state"] not in NO_DATA and m["steps"] == 0
             and m.get("intensity", 0) <= 30 and m["hr"] is not None and 30 <= m["hr"] <= 220]
    best, run_start = None, 0
    for i in range(len(still)):
        if i > 0 and still[i]["ts"] - still[i - 1]["ts"] > 120:
            run_start = i
        j = i
        while j > run_start and still[i]["ts"] - still[j - 1]["ts"] < 600:
            j -= 1
        if i - j + 1 >= 8:
            v = median(m["hr"] for m in still[j:i + 1])
            best = v if best is None else min(best, v)
    return round(best) if best is not None else None


def active_minutes_of(ms: list[dict], hr_max: int | None, hr_rest: float | None, workouts=()) -> int:
    """Walking (>= 40 steps, or >= 20 next to such a minute), HR >= 30% of reserve, or inside a workout with
    HR >= 20% of reserve or >= 20 steps; awake and worn only."""
    reserve = hr_max - hr_rest if hr_max and hr_rest and hr_rest > 0 and hr_max > hr_rest else None
    walking = lambda i: ms[i]["steps"] >= 40
    n = 0
    for i, m in enumerate(ms):
        if m["state"] in SLEEP or m["state"] in NO_DATA:
            continue
        near = (i > 0 and m["ts"] - ms[i - 1]["ts"] <= 90 and walking(i - 1)) or                (i < len(ms) - 1 and ms[i + 1]["ts"] - m["ts"] <= 90 and walking(i + 1))
        walk = walking(i) or (m["steps"] >= 20 and near)
        hr = m["hr"] if m["hr"] is not None and 30 <= m["hr"] <= 220 else None
        effort = reserve is not None and hr is not None and hr >= hr_rest + 0.3 * reserve
        in_workout = any(s <= m["ts"] < e for s, e in workouts)
        working = in_workout and (m["steps"] >= 20 or (reserve is not None and hr is not None and hr >= hr_rest + 0.2 * reserve))
        n += bool(walk or effort or working)
    return n


def vo2max_uth(hr_max: int | None, rhr: float | None, sex: str | None) -> float | None:
    """Uth et al. 2004: VO2max ~= k * HRmax / HRrest, k = 15.3 men, 14.5 women (later work), 15.0 unknown."""
    if not hr_max or not rhr:
        return None
    k = {"male": 15.3, "female": 14.5}.get(sex, 15.0)
    return round(k * hr_max / rhr, 1)


def fitness_age(vo2: float | None, sex: str | None) -> int | None:
    """Age at which an average person has this VO2max, from a linear fit to population norms
    (men ~54 at 20 falling ~0.36/yr; women ~44 falling ~0.28/yr). Coarse; heuristic."""
    if vo2 is None:
        return None
    base, slope = {"male": (54.0, 0.36), "female": (44.0, 0.28)}.get(sex, (49.0, 0.32))
    return int(min(90, max(20, round(20 + (base - vo2) / slope))))


# ---- main --------------------------------------------------------------------

def _cap(z: float) -> float:
    """How far one signal can move readiness: z-scores beyond ±2 count as 2."""
    return min(2.0, max(-2.0, z))


def _z(value, base: list[float], floor: float) -> float | None:
    if value is None or len(base) < 3:
        return None
    sd = max(pstdev(base), floor)
    return (value - mean(base)) / sd


def compute(minutes: list[dict], profile: dict, days: int, today: date | None = None,
            hrv_by_day: dict | None = None, health: dict | None = None, rhythm_latest: dict | None = None,
            workouts: list | None = None) -> dict:
    today = today or date.today()
    hrv_by_day = hrv_by_day or {}
    health = health or {}
    hr_max = hr_max_of(profile)
    sex = profile.get("sex")

    by_day: dict[date, list[dict]] = defaultdict(list)
    for m in minutes:
        by_day[day_of(m["ts"])].append(m)
    blocks = sleep_blocks(minutes)
    last_ts = minutes[-1]["ts"] if minutes else None

    # First pass: per-day facts that don't depend on baselines.
    first = min(by_day) if by_day else today
    span = max(days, 35)
    dates = [today - timedelta(days=i) for i in range(span - 1, -1, -1)]
    rows = {}
    awake_rest = {}
    for d in dates:
        if d < first - timedelta(days=1):
            continue
        ms = by_day.get(d, [])
        main, asleep_total = night_for(blocks, d, last_ts)
        night = {}
        if main:
            in_bed = (main["end"] - main["start"]) / 60 + 1
            eff = round(100 * main["asleep"] / in_bed, 1) if in_bed else None
            night = {"sleep_minutes": asleep_total, "sleep_efficiency": eff, "deep_min": main["deep"],
                     "light_min": main["light"], "rem_min": main["rem"], "awake_min": main["awake"],
                     "sleep_score": sleep_score(main["asleep"], eff or 0, main["deep"], main["rem"]),
                     "sleep_start": main["start"], "sleep_end": main["end"]}
        rhr = health.get("resting_hr", {}).get(d)
        if rhr is None and main:
            rhr = resting_hr_from(main["rows"])
        awake_hr = [m["hr"] for m in ms if m["hr"] and m["state"] not in SLEEP]
        worn = sum(m["state"] not in NO_DATA for m in ms)
        row = {
            "date": d.isoformat(), "resting_hr": rhr,
            "hrv_rmssd": hrv_by_day.get(d, health.get("hrv", {}).get(d)),
            "sleep_minutes": None, "sleep_efficiency": None, "deep_min": None, "light_min": None,
            "rem_min": None, "awake_min": None, "sleep_score": None,
            "steps": sum(m["steps"] for m in ms) if ms else None,
            "active_minutes": None,  # set below, once resting HR is known
            "worn_minutes": worn if ms else None,
            "strain": None, "trimp": None, "hr_zones": None,
            "spo2_avg": health.get("spo2", {}).get(d), "stress_avg": health.get("stress", {}).get(d),
            "readiness": None, "calories_active": None,
        }
        row.update(night)
        if hr_max and awake_hr:
            row["hr_zones"] = zone_minutes(awake_hr, hr_max)
        rows[d] = (row, awake_hr)
        awake_rest[d] = awake_resting_hr_from(ms)

    rhr_values = [r["resting_hr"] for r, _ in rows.values() if r["resting_hr"]]
    # Reserve wants resting HR awake (Karvonen): profile, else median awake resting, else sleeping + 8.
    awake_values = [v for v in awake_rest.values() if v]
    hr_rest = (profile.get("resting_hr") or (median(awake_values) if awake_values else None)
               or (median(rhr_values) + 8 if rhr_values else None))
    for d, (row, _) in rows.items():
        if by_day.get(d):
            row["active_minutes"] = active_minutes_of(by_day[d], hr_max, hr_rest, workouts or ())
    for d, (row, awake_hr) in rows.items():
        if hr_max and hr_rest and awake_hr:
            t = banister_trimp(awake_hr, hr_rest, hr_max, sex)
            row["trimp"] = round(t, 1)
            row["strain"] = strain_of(t)
            row["calories_active"] = keytel_kcal(awake_hr, profile, hr_max)

    # Readiness: today's RHR / HRV vs the previous 28 days, last night's sleep, yesterday's strain.
    ordered = sorted(rows)
    for d in ordered:
        row = rows[d][0]
        prev = [rows[p][0] for p in ordered if d - timedelta(days=28) <= p < d]
        row["_factors"] = factors = []
        zs = []
        z = _z(row["resting_hr"], [p["resting_hr"] for p in prev if p["resting_hr"]], 2.0)
        if z is not None:
            z = _cap(z)
            zs.append((-z, 0.30))
            factors.append(("Resting heart rate", -z, f"{row['resting_hr']} bpm vs your usual "
                            f"{round(mean(p['resting_hr'] for p in prev if p['resting_hr']))}"))
        # HRV on a log scale (ln RMSSD): readings skew high. Spread at least 0.1, about 10%.
        base_hrv = [math.log(p["hrv_rmssd"]) for p in prev if p["hrv_rmssd"] and p["hrv_rmssd"] > 0]
        z = _z(math.log(row["hrv_rmssd"]) if row["hrv_rmssd"] and row["hrv_rmssd"] > 0 else None, base_hrv, 0.1)
        if z is not None:
            z = _cap(z)
            zs.append((z, 0.35))
            factors.append(("HRV", z, f"Awake RMSSD {row['hrv_rmssd']:.0f} ms vs your usual {math.exp(mean(base_hrv)):.0f}"))
        if row["sleep_score"] is not None:
            z = _cap((row["sleep_score"] - 70) / 15)
            zs.append((z, 0.25))
            factors.append(("Sleep", z, f"sleep score {row['sleep_score']}, "
                            f"{row['sleep_minutes'] // 60} h {row['sleep_minutes'] % 60} min asleep"))
        y = rows.get(d - timedelta(days=1), (None,))[0]
        if y and y["strain"] is not None:
            z = _cap(-(y["strain"] - 14) / 4 if y["strain"] > 14 else 0.0)
            zs.append((z, 0.10))
            factors.append(("Yesterday's strain", z, f"strain {y['strain']}"))
        if zs and any(name in ("Resting heart rate", "Sleep") for name, *_ in factors):
            wsum = sum(w for _, w in zs)
            comb = sum(z * w for z, w in zs) / wsum
            row["readiness"] = int(min(100, max(0, round(70 + 15 * comb))))

    window = [rows[d][0] for d in ordered if d > today - timedelta(days=days)]
    trow = rows.get(today, (None,))[0]

    def avg(key, n):
        vals = [rows[d][0][key] for d in ordered if d > today - timedelta(days=n) and rows[d][0][key] is not None]
        return round(mean(vals), 1) if vals else None

    rhr7 = avg("resting_hr", 7)
    # Uth's formula wants resting HR measured awake and lying still; sleeping HR runs lower and would inflate VO2max.
    awake_vals = [awake_rest[d] for d in ordered if d > today - timedelta(days=7) and awake_rest.get(d) is not None]
    vo2 = vo2max_uth(hr_max, round(mean(awake_vals), 1) if awake_vals else None, sex)
    trimps = {d: rows[d][0]["trimp"] for d in ordered if rows[d][0]["trimp"] is not None}
    have_days = (today - first).days + 1
    acute = [v for d, v in trimps.items() if d > today - timedelta(days=7)]
    chronic = [v for d, v in trimps.items() if d > today - timedelta(days=28)]
    acwr = (round(mean(acute) / mean(chronic), 2)
            if have_days >= 21 and acute and chronic and mean(chronic) > 0 else None)

    today_out = {"readiness": None, "readiness_factors": [], "strain": None, "sleep_score": None,
                 "body_battery": None, "recommendation": None, "note": None, "last_readiness": None}
    done = [rows[d][0] for d in ordered if rows[d][0]["readiness"] is not None]
    if done:
        today_out["last_readiness"] = {"date": done[-1]["date"], "value": done[-1]["readiness"]}
    if trow:
        today_out.update(readiness=trow["readiness"], strain=trow["strain"], sleep_score=trow["sleep_score"])
        for name, z, detail in trow["_factors"]:
            impact = "positive" if z > 0.3 else "negative" if z < -0.3 else "neutral"
            today_out["readiness_factors"].append({"name": name, "impact": impact, "detail": detail})
        today_out["body_battery"] = body_battery(by_day.get(today, []), trow, hr_max)
        today_out["recommendation"] = recommendation(trow["readiness"], trow["strain"])
        if trow["sleep_score"] is None:
            today_out["note"] = ("No finished night yet today. Readiness, sleep score and body battery appear "
                                 "after you wake up with the watch on.")

    alerts = make_alerts(rows, ordered, today, profile, hr_max, acwr, rhythm_latest)
    for r, _ in rows.values():
        r.pop("_factors", None)
    return {
        "days": window,
        "today": today_out,
        "trends": {
            "resting_hr_7d": rhr7, "resting_hr_28d": avg("resting_hr", 28),
            "hrv_7d": avg("hrv_rmssd", 7), "hrv_28d": avg("hrv_rmssd", 28),
            "vo2max_estimate": vo2, "fitness_age": fitness_age(vo2, sex),
            "sleep_regularity": sleep_regularity([m for m in minutes if day_of(m["ts"]) > today - timedelta(days=days)]),
            "cardio_load_7d": round(sum(acute), 1) if acute else None, "acute_chronic_ratio": acwr,
            "hr_max_used": hr_max, "hr_rest_used": hr_rest,
        },
        "alerts": alerts,
    }


def body_battery(ms: list[dict], trow: dict, hr_max: int | None) -> int | None:
    """Garmin-style energy estimate (heuristic, not Firstbeat's model): start the day at
    20 + 0.8 * sleep score, drain 0.06/min awake plus more in higher HR zones (or with steps when HRmax is
    unknown), recharge 0.15/min during naps. Returns the value at the latest minute."""
    if trow.get("sleep_score") is None or trow.get("sleep_end") is None:
        return None
    level = min(100.0, 20 + 0.8 * trow["sleep_score"])
    extra = (0.05, 0.10, 0.20, 0.35, 0.50)
    for m in ms:
        if m["ts"] <= trow["sleep_end"] or m["state"] in NO_DATA:
            continue
        if m["state"] in SLEEP:
            level += 0.15
            continue
        drain = 0.06
        if hr_max and m["hr"]:
            f = m["hr"] / hr_max
            for i, (a, b) in enumerate(ZONES):
                if a <= f < b:
                    drain += extra[i]
        elif m["steps"]:
            drain += min(0.3, m["steps"] * 0.002)
        level -= drain
        level = min(100.0, max(0.0, level))
    return round(level)


def recommendation(readiness: int | None, strain: float | None) -> str | None:
    if readiness is None:
        return None
    if readiness >= 80:
        return "Well recovered. A good day for a hard workout."
    if readiness >= 65:
        return "Recovered enough for a normal training day."
    if readiness >= 50:
        return "Partly recovered. Keep effort moderate and get to bed on time."
    return "Your body is under strain. Favour rest, easy movement and an early night."


def make_alerts(rows, ordered, today, profile, hr_max, acwr, rhythm_latest) -> list[dict]:
    alerts = []
    trow = rows.get(today, (None,))[0]
    prev = [rows[p][0] for p in ordered if today - timedelta(days=28) <= p < today]
    base_rhr = [p["resting_hr"] for p in prev if p["resting_hr"]]
    if trow and trow["resting_hr"] and len(base_rhr) >= 3 and trow["resting_hr"] >= mean(base_rhr) + 5:
        base_hrv = [p["hrv_rmssd"] for p in prev if p["hrv_rmssd"]]
        hrv_down = trow["hrv_rmssd"] and base_hrv and trow["hrv_rmssd"] < 0.85 * mean(base_hrv)
        alerts.append({"level": "warn", "title": "Resting heart rate is up",
                       "detail": f"{trow['resting_hr']} bpm, {trow['resting_hr'] - mean(base_rhr):.0f} above your usual"
                                 + (" and HRV is down." if hrv_down else ".")
                                 + " Hard training, alcohol, poor sleep or an oncoming illness can all do this."})
    if trow and trow["sleep_minutes"] is not None and trow["sleep_minutes"] < 360:
        alerts.append({"level": "warn" if trow["sleep_minutes"] < 300 else "info", "title": "Short night",
                       "detail": f"{trow['sleep_minutes'] // 60} h {trow['sleep_minutes'] % 60} min of sleep."})
    if acwr is not None and acwr > 1.5:
        alerts.append({"level": "warn", "title": "Training load jumped",
                       "detail": f"This week's load is {acwr:.1f}x your 4-week average; injury risk rises above 1.5."})
    y = rows.get(today - timedelta(days=1), (None,))[0]
    if y and y["worn_minutes"] is not None and y["worn_minutes"] < 16 * 60:
        alerts.append({"level": "info", "title": "Watch off for long stretches",
                       "detail": f"Yesterday it was worn {y['worn_minutes'] // 60} h; scores are less reliable."})
    spo2 = trow and trow["spo2_avg"]
    if spo2 and spo2 < 92:
        alerts.append({"level": "warn", "title": "Low blood oxygen readings",
                       "detail": f"Average SpO2 {spo2:.0f}%. Wrist SpO2 is noisy; if it persists, ask a doctor."})
    if rhythm_latest and rhythm_latest.get("status") == "possible_af":
        alerts.append({"level": "warn", "title": "Irregular rhythm in your last measurement",
                       "detail": "Repeat it sitting still. If it recurs, ask a doctor for an ECG. Not a diagnosis."})
    missing = [k for k in ("age", "sex", "weight_kg") if not profile.get(k)]
    if missing or not hr_max:
        alerts.append({"level": "info", "title": "Complete your profile",
                       "detail": "Add age, sex and weight to unlock heart rate zones, strain, calories and VO2max."})
    return alerts
