"use strict";
// Today, heart check, trends, sleep and workouts, watch controls. Builds on app.js globals.

const ins = {
  daily: null,
  health: null,
  session: null,
  checkSeconds: 60,
  measuring: false,
  healthSynced: false,
  settings: null,
};

// ---------- Hooks into app.js ----------

hooks.message.push((msg) => {
  if (msg.type === "steps") renderLiveSteps(msg);
  else if (msg.type === "device_event") log("info", `Watch: ${msg.name}`);
  else if (msg.type === "event" && /^Health sync done/.test(msg.msg)) { loadHealth(); loadDaily(); }
});

hooks.status.push((s, connected) => {
  for (const id of ["health-sync-btn", "time-btn", "notify-btn"]) $(id).disabled = !connected;
  $("check-btn").disabled = !connected || ins.measuring;
  if (s.steps) renderLiveSteps(s.steps);
  else if (!connected) $("live-steps").hidden = true;
});

hooks.connected.push(() => {
  loadSettings();
  if (!ins.healthSynced) {
    ins.healthSynced = true;
    syncHealth();
  }
});

function renderLiveSteps(s) {
  if (s?.steps == null) return;
  $("live-steps").hidden = false;
  $("live-steps-value").textContent = s.steps.toLocaleString();
}

// ---------- Small helpers ----------

const fmt = {
  int: (v) => (v == null ? "–" : Math.round(v).toLocaleString()),
  one: (v) => (v == null ? "–" : (Math.round(v * 10) / 10).toString()),
  hm: (min) => {
    if (min == null) return "–";
    const h = Math.floor(min / 60), m = Math.round(min % 60);
    return h ? `${h}<small>h</small> ${m}<small>m</small>` : `${m}<small>m</small>`;
  },
};

function unit(value, u) {
  return value === "–" ? value : `${value}<small>${u}</small>`;
}

function svgSize(svg) {
  const r = svg.getBoundingClientRect();
  const w = Math.max(r.width, 10), h = Math.max(r.height, 10);
  svg.setAttribute("viewBox", `0 0 ${w} ${h}`);
  return { w, h };
}

function dayLabel(date) {
  return new Date(`${date}T12:00:00`).toLocaleDateString([], { weekday: "short" }).slice(0, 2);
}

// ---------- Today + trends ----------

async function loadDaily() {
  try {
    ins.daily = await api("/api/insights/daily?days=14");
  } catch {
    ins.daily = null;
  }
  renderToday();
  renderTrends();
}

async function loadHealth() {
  try {
    ins.health = await api("/api/health?days=14");
  } catch {
    ins.health = null;
  }
  renderTrends();
  renderSessions();
}

async function syncHealth() {
  const btn = $("health-sync-btn");
  btn.disabled = true;
  btn.textContent = "Syncing…";
  try {
    const r = await api("/api/health/sync", "POST");
    const got = Object.entries(r.types || {}).filter(([, v]) => v.added > 0).map(([k, v]) => `${v.added} ${k.replace(/_/g, " ")}`);
    log("info", got.length ? `Health data synced: ${got.join(", ")}` : "Health data already up to date");
  } catch (e) {
    if (!/already running/.test(e.message)) log("error", `Health sync: ${e.message}`);
  }
  btn.textContent = "Sync health data";
  btn.disabled = state.status?.connection !== "connected";
  loadHealth();
  loadDaily();
}

function renderToday() {
  const t = ins.daily?.today || {};
  const days = ins.daily?.days || [];
  const latest = days[days.length - 1] || {};

  // Before tonight's sleep is finished, show the last readiness rather than a blank.
  const last = t.readiness == null ? t.last_readiness : null;
  const ready = t.readiness ?? last?.value;
  $("t-readiness").textContent = fmt.int(ready);
  $("t-readiness").parentElement.dataset.band = ready == null ? "" : ready >= 70 ? "high" : ready >= 45 ? "mid" : "low";
  $("t-readiness").parentElement.querySelector(".score-label").textContent = last
    ? `Readiness, ${new Date(`${last.date}T12:00:00`).toLocaleDateString([], { day: "numeric", month: "short" })}`
    : "Readiness";
  $("t-strain").textContent = fmt.one(t.strain);
  $("t-sleep").textContent = fmt.int(t.sleep_score);
  $("t-battery").textContent = fmt.int(t.body_battery);
  $("t-rhr").textContent = fmt.int(latest.resting_hr ?? ins.daily?.trends?.resting_hr_7d);
  $("t-rec").textContent = t.recommendation || t.note || "Sync activity and take a heart check to get today's readiness.";

  const factors = t.readiness_factors || [];
  $("t-factors").innerHTML = factors.length
    ? factors.map((f) => `<li data-impact="${esc(f.impact)}"><span><strong>${esc(f.name)}</strong> ${esc(f.detail || "")}</span></li>`).join("")
    : `<li class="muted">Nothing yet. Readiness needs a few nights of sleep and some heart checks to learn your baseline.</li>`;

  const alerts = ins.daily?.alerts || [];
  $("t-alerts").hidden = !alerts.length;
  $("t-alert-list").innerHTML = alerts.map((a) =>
    `<li data-level="${esc(a.level)}"><strong>${esc(a.title)}</strong>${esc(a.detail || "")}</li>`).join("");
}

function renderTrends() {
  const d = ins.daily;
  const tr = d?.trends || {};
  $("tr-vo2").innerHTML = unit(fmt.one(tr.vo2max_estimate), "ml/kg/min");
  $("tr-age").innerHTML = unit(fmt.int(tr.fitness_age), "years");
  $("tr-sri").innerHTML = unit(fmt.int(tr.sleep_regularity), "/100");
  $("tr-load").textContent = fmt.int(tr.cardio_load_7d);
  $("tr-acwr").textContent = tr.acute_chronic_ratio == null ? "–" : tr.acute_chronic_ratio.toFixed(2);

  const days = d?.days || [];
  const byDay = (key) => days.map((x, i) => ({ i, y: x[key], label: x.date }));
  const series = ins.health?.series || {};
  // Watch-side series arrive as timestamped points; fold them into the same 14 day slots (daily mean).
  const daily = (points, field = "value") => {
    if (!days.length) return [];
    return days.map((x, i) => {
      const vals = (points || []).filter((p) => new Date(p.ts * 1000).toLocaleDateString("sv") === x.date).map((p) => p[field]).filter((v) => v != null);
      return { i, y: vals.length ? vals.reduce((a, b) => a + b, 0) / vals.length : null, label: x.date };
    });
  };
  const pick = (a, b) => (a.some((p) => p.y != null) ? a : b);

  const charts = [
    { title: "Readiness", pts: byDay("readiness"), type: "line", fmt: fmt.int },
    { title: "Resting heart rate", pts: pick(byDay("resting_hr"), daily(series.resting_hr)), type: "line", fmt: fmt.int, unit: "bpm", base: tr.resting_hr_28d },
    { title: "HRV (RMSSD)", pts: pick(byDay("hrv_rmssd"), daily(series.hrv)), type: "line", fmt: fmt.int, unit: "ms", base: tr.hrv_28d },
    { title: "Sleep", pts: byDay("sleep_minutes"), type: "bar", fmt: (v) => fmt.hm(v), html: true },
    { title: "Sleep score", pts: byDay("sleep_score"), type: "line", fmt: fmt.int },
    { title: "Steps", pts: byDay("steps"), type: "bar", fmt: fmt.int },
    { title: "Strain", pts: byDay("strain"), type: "line", fmt: fmt.one, accent: "pulse" },
    { title: "Active time", pts: byDay("active_minutes"), type: "bar", fmt: (v) => fmt.hm(v), html: true, accent: "pulse" },
    { title: "Blood oxygen", pts: pick(byDay("spo2_avg"), daily(series.spo2)), type: "line", fmt: fmt.int, unit: "%" },
    { title: "Stress", pts: pick(byDay("stress_avg"), daily(series.stress)), type: "line", fmt: fmt.int, accent: "pulse" },
    { title: "PAI", pts: daily(series.pai, "total"), type: "bar", fmt: fmt.int },
    { title: "Breathing in sleep", pts: daily(series.sleep_respiratory_rate), type: "line", fmt: fmt.one, unit: "/min" },
    { title: "Temperature", pts: daily(series.temperature), type: "line", fmt: fmt.one, unit: "°C" },
  ];

  const box = $("multiples");
  box.innerHTML = charts.map((c, n) => `
    <div class="mini"${c.accent ? ` data-accent="${c.accent}"` : ""}>
      <div class="mini-head"><span class="mini-title">${c.title}</span><span class="mini-now" id="mini-now-${n}"></span></div>
      <svg id="mini-svg-${n}" role="img" aria-label="${c.title} over two weeks"></svg>
    </div>`).join("");
  charts.forEach((c, n) => {
    const last = [...c.pts].reverse().find((p) => p.y != null);
    const now = $(`mini-now-${n}`);
    const shown = last ? c.fmt(last.y) : "–";
    now.innerHTML = c.html ? shown : unit(shown, c.unit || "");
    drawMini($(`mini-svg-${n}`), c, days.length || 14);
  });
}

function drawMini(svg, c, slots) {
  const { w, h } = svgSize(svg);
  const pts = c.pts.filter((p) => p.y != null);
  if (!pts.length) {
    svg.innerHTML = `<text class="m-empty" x="0" y="${h / 2 + 4}">No data yet</text>`;
    return;
  }
  const ys = pts.map((p) => p.y).concat(c.base != null ? [c.base] : []);
  let lo = Math.min(...ys), hi = Math.max(...ys);
  if (c.type === "bar") lo = 0;
  if (hi === lo) { hi += 1; lo -= c.type === "bar" ? 0 : 1; }
  const pad = { t: 4, b: 14 };
  const step = w / slots;
  const x = (i) => step * i + step / 2;
  const y = (v) => pad.t + (1 - (v - lo) / (hi - lo)) * (h - pad.t - pad.b);
  const parts = [];
  if (c.base != null) parts.push(`<line class="m-base" x1="0" x2="${w}" y1="${y(c.base)}" y2="${y(c.base)}"/>`);
  if (c.type === "bar") {
    const bw = Math.max(2, step * 0.62);
    for (const p of pts) parts.push(`<rect class="m-bar" x="${x(p.i) - bw / 2}" y="${y(p.y)}" width="${bw}" height="${Math.max(1, y(lo) - y(p.y))}" rx="1.5"/>`);
  } else {
    let d = "";
    pts.forEach((p, k) => { d += `${k && p.i - pts[k - 1].i === 1 ? "L" : "M"}${x(p.i).toFixed(1)},${y(p.y).toFixed(1)}`; });
    parts.push(`<path class="m-line" d="${d}"/>`);
    for (const p of pts) parts.push(`<circle class="m-dot" cx="${x(p.i)}" cy="${y(p.y)}" r="2.2"/>`);
  }
  const labels = c.pts.length ? [c.pts[0], c.pts[c.pts.length - 1]] : [];
  labels.forEach((p, k) => {
    if (!p?.label) return;
    parts.push(`<text class="m-axis" x="${k ? w : 0}" y="${h - 1}" text-anchor="${k ? "end" : "start"}">${k ? "Today" : dayLabel(p.label)}</text>`);
  });
  svg.innerHTML = parts.join("");
}

// ---------- Heart check ----------

const RHYTHM_TEXT = {
  regular: "Regular rhythm",
  irregular: "Irregular rhythm",
  possible_af: "Signs of possible AFib",
  insufficient_data: "Not enough clean signal",
};
const DISCLAIMER = "A wellness estimate from your wrist pulse, not a medical diagnosis. If you have symptoms, see a doctor.";

async function loadRecordings(selectFile) {
  try {
    const { recordings } = await api("/api/insights/recordings");
    const sel = $("check-file");
    sel.innerHTML = recordings.length
      ? recordings.map((r) => `<option value="${esc(r.file)}">${new Date(r.start * 1000).toLocaleString([], { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit", hour12: false })}, ${Math.round(r.duration_s)} s</option>`).join("")
      : `<option value="">No recordings yet</option>`;
    if (selectFile) sel.value = selectFile;
    // Default to the newest recording long enough for HRV; short ones are usually sensor tests.
    const usable = recordings.find((r) => r.duration_s >= 50);
    if (!selectFile && usable) sel.value = usable.file;
    if (!ins.session && recordings.length) loadSession(sel.value);
  } catch {
    /* insights not available yet */
  }
}

async function loadSession(file) {
  if (!file) return;
  try {
    ins.session = await api(`/api/insights/session?file=${encodeURIComponent(file)}`);
  } catch (e) {
    log("error", `Heart check analysis: ${e.message}`);
    return;
  }
  renderSession();
}

async function startCheck() {
  if (ins.measuring) return;
  ins.measuring = true;
  const seconds = ins.checkSeconds;
  const btn = $("check-btn");
  btn.disabled = true;
  btn.textContent = "Measuring…";
  $("check-progress").hidden = false;
  const fill = $("check-bar-fill");
  fill.style.width = "0";
  const t0 = Date.now();
  const tick = setInterval(() => {
    const left = Math.max(0, seconds - (Date.now() - t0) / 1000);
    fill.style.width = `${Math.min(100, ((seconds - left) / seconds) * 100)}%`;
    $("check-countdown").textContent = left > 0 ? `Hold still, ${Math.ceil(left)} s left` : "Analysing…";
  }, 500);
  try {
    ins.session = await api("/api/insights/measure", "POST", { seconds });
    renderSession();
    loadRecordings(ins.session.file);
    loadDaily();
  } catch (e) {
    log("error", `Heart check: ${e.message}`);
    $("check-countdown").textContent = `Heart check failed: ${e.message}`;
  } finally {
    clearInterval(tick);
    ins.measuring = false;
    btn.textContent = "Start heart check";
    btn.disabled = state.status?.connection !== "connected";
    setTimeout(() => { $("check-progress").hidden = true; }, 1500);
  }
}

function renderSession() {
  const s = ins.session;
  if (!s) return;
  const hrv = s.hrv || {};
  const r = s.rhythm || {};
  $("rhythm").dataset.status = r.status || "none";
  $("rhythm-value").textContent = RHYTHM_TEXT[r.status] || "No check yet";
  const notes = (r.notes || []).filter((n) => !/not a (medical )?diagnosis/i.test(n));
  $("rhythm-notes").textContent = [...notes, DISCLAIMER].join(" ");

  $("c-hr").innerHTML = unit(fmt.int(s.hr_bpm), "bpm");
  $("c-rmssd").innerHTML = unit(fmt.int(hrv.rmssd_ms), "ms");
  $("c-sdnn").innerHTML = unit(fmt.int(hrv.sdnn_ms), "ms");
  $("c-pnn50").innerHTML = unit(hrv.pnn50 == null ? "–" : fmt.int(hrv.pnn50 <= 1 ? hrv.pnn50 * 100 : hrv.pnn50), "%");
  $("c-stress").textContent = fmt.int(hrv.stress_index);
  $("c-resp").innerHTML = unit(fmt.one(s.respiratory_rate), "/min");
  $("c-lfhf").textContent = hrv.lf_hf == null ? "–" : hrv.lf_hf.toFixed(2);
  $("c-ectopic").textContent = fmt.int(r.ectopic_beats);
  $("c-sqi").innerHTML = unit(s.signal_quality == null ? "–" : fmt.int(s.signal_quality * 100), "%");
  drawTacho();
  drawPoincare();
  drawBeats();
}

function drawTacho() {
  const svg = $("tacho-svg");
  const { w, h } = svgSize(svg);
  const rr = ins.session?.rr || [];
  if (rr.length < 2) { svg.innerHTML = `<text class="p-empty" x="0" y="${h / 2}">No beats to show</text>`; return; }
  const vals = rr.map((p) => p.rr_ms);
  const lo = Math.min(...vals) - 30, hi = Math.max(...vals) + 30;
  const tMax = rr[rr.length - 1].t || 1;
  const pad = { l: 4, r: 38, t: 6, b: 16 };
  const x = (t) => pad.l + (t / tMax) * (w - pad.l - pad.r);
  const y = (v) => pad.t + (1 - (v - lo) / (hi - lo)) * (h - pad.t - pad.b);
  let d = "";
  rr.forEach((p, i) => { d += `${i ? "L" : "M"}${x(p.t).toFixed(1)},${y(p.rr_ms).toFixed(1)}`; });
  const parts = [`<path class="p-line" d="${d}"/>`];
  for (const p of rr) parts.push(`<circle class="${p.ectopic ? "dot-ectopic" : "dot"}" cx="${x(p.t)}" cy="${y(p.rr_ms)}" r="${p.ectopic ? 3.5 : 2}"/>`);
  for (const v of [lo + 30, (lo + hi) / 2, hi - 30]) parts.push(`<text class="p-axis" x="${w - pad.r + 6}" y="${y(v) + 3}">${Math.round(v)} ms</text>`);
  parts.push(`<text class="p-axis" x="${pad.l}" y="${h - 2}">0 s</text><text class="p-axis" x="${w - pad.r}" y="${h - 2}" text-anchor="end">${Math.round(tMax)} s</text>`);
  svg.innerHTML = parts.join("");
}

function drawPoincare() {
  const svg = $("poincare-svg");
  const { w, h } = svgSize(svg);
  const rr = ins.session?.rr || [];
  if (rr.length < 3) { svg.innerHTML = `<text class="p-empty" x="0" y="${h / 2}">No beats to show</text>`; return; }
  const vals = rr.map((p) => p.rr_ms);
  const lo = Math.min(...vals) - 40, hi = Math.max(...vals) + 40;
  const pad = 26;
  const s = (v) => pad + ((v - lo) / (hi - lo)) * (Math.min(w, h) - pad * 1.4);
  const size = Math.min(w, h);
  const parts = [`<line class="ident" x1="${s(lo)}" y1="${size - s(lo)}" x2="${s(hi)}" y2="${size - s(hi)}"/>`];
  for (let i = 0; i < rr.length - 1; i++) {
    const ect = rr[i].ectopic || rr[i + 1].ectopic;
    parts.push(`<circle class="${ect ? "dot-ectopic" : "dot"}" cx="${s(rr[i].rr_ms)}" cy="${size - s(rr[i + 1].rr_ms)}" r="${ect ? 3.5 : 2.6}"/>`);
  }
  parts.push(`<text class="p-axis" x="${s((lo + hi) / 2)}" y="${size - 4}" text-anchor="middle">interval n, ms</text>`);
  parts.push(`<text class="p-axis" x="10" y="${size / 2}" transform="rotate(-90 10 ${size / 2})" text-anchor="middle">interval n+1, ms</text>`);
  parts.push(`<text class="p-axis" x="${pad}" y="${size - 14}">${Math.round(lo + 40)}</text><text class="p-axis" x="${s(hi - 40)}" y="${size - 14}" text-anchor="end">${Math.round(hi - 40)}</text>`);
  svg.innerHTML = parts.join("");
}

function drawBeats() {
  const svg = $("beats-svg");
  const { w, h } = svgSize(svg);
  const s = ins.session;
  const sig = s?.ppg_preview || [];
  if (sig.length < 2) { svg.innerHTML = `<text class="p-empty" x="0" y="${h / 2}">No signal to show</text>`; return; }
  // Show the last 15 s so individual beats stay readable.
  const dur = s.duration_s || sig.length / 25;
  const showS = Math.min(15, dur);
  const perS = sig.length / dur;
  const from = Math.max(0, Math.floor(sig.length - showS * perS));
  const part = sig.slice(from);
  const t0 = from / perS;
  const lo = Math.min(...part), hi = Math.max(...part);
  const x = (t) => ((t - t0) / showS) * w;
  const y = (v) => 6 + (1 - (v - lo) / (hi - lo || 1)) * (h - 12);
  let d = "";
  part.forEach((v, i) => { d += `${i ? "L" : "M"}${x(t0 + i / perS).toFixed(1)},${y(v).toFixed(1)}`; });
  const parts = [`<path class="p-line" d="${d}"/>`];
  for (const bt of s.beat_times || []) {
    if (bt < t0) continue;
    const i = Math.min(sig.length - 1, Math.round(bt * perS));
    parts.push(`<circle class="beat-mark" cx="${x(bt)}" cy="${y(sig[i])}" r="3"/>`);
  }
  svg.innerHTML = parts.join("");
}

// ---------- Sleep sessions and workouts ----------

function renderSessions() {
  const sleeps = ins.health?.sleep_sessions || [];
  const works = ins.health?.workouts || [];
  const when = (ts) => new Date(ts * 1000).toLocaleString([], { weekday: "short", day: "numeric", month: "short", hour: "2-digit", minute: "2-digit", hour12: false });
  const cssColor = (v) => `var(${v})`;

  $("sleep-list").innerHTML = sleeps.length ? sleeps.slice().reverse().map((s) => {
    const parts = [["--sleep-deep", s.deep_min], ["--sleep-light", s.light_min], ["--sleep-rem", s.rem_min], ["--idle", s.awake_min]];
    const total = parts.reduce((a, [, v]) => a + (v || 0), 0) || 1;
    const asleep = (s.deep_min || 0) + (s.light_min || 0) + (s.rem_min || 0);
    return `<li>
      <div class="session-top"><span>${when(s.start)}</span><span>${fmt.hm(asleep).replace(/<\/?small>/g, "")} asleep</span></div>
      <div class="session-bar">${parts.map(([c, v]) => v ? `<i style="width:${(v / total) * 100}%;background:${cssColor(c)}"></i>` : "").join("")}</div>
      <div class="session-meta">Deep ${s.deep_min ?? "–"} min, light ${s.light_min ?? "–"} min, REM ${s.rem_min ?? "–"} min, awake ${s.awake_min ?? "–"} min</div>
    </li>`;
  }).join("") : `<li class="muted">No sleep sessions synced yet.</li>`;

  $("workout-list").innerHTML = works.length ? works.slice().reverse().map((w) => {
    const meta = [
      w.avg_hr && `avg ${w.avg_hr} bpm`, w.max_hr && `max ${w.max_hr} bpm`, w.calories && `${w.calories} kcal`,
      w.steps && `${w.steps.toLocaleString()} steps`, w.distance_m && `${(w.distance_m / 1000).toFixed(2)} km`,
    ].filter(Boolean).join(", ");
    return `<li>
      <div class="session-top"><span>${esc(String(w.type || "Workout"))}</span><span>${when(w.start)}, ${Math.round((w.duration_s || 0) / 60)} min</span></div>
      <div class="session-meta">${esc(meta || "No details")}</div>
    </li>`;
  }).join("") : `<li class="muted">No workouts synced yet.</li>`;
}

// ---------- Watch controls ----------

async function loadSettings() {
  const box = $("settings");
  try {
    const { settings } = await api("/api/settings");
    ins.settings = settings;
    box.innerHTML = settings.length ? settings.map(settingRow).join("") : `<p class="muted">The watch did not report any settings.</p>`;
  } catch (e) {
    box.innerHTML = `<p class="muted">Could not load settings: ${esc(e.message)}</p>`;
  }
}

function settingRow(s) {
  const id = `set-${s.key}`;
  let control;
  if (s.type === "bool") {
    control = `<label class="switch"><input type="checkbox" id="${id}" data-key="${esc(s.key)}" ${s.value ? "checked" : ""} aria-label="${esc(s.label)}"><span></span></label>`;
  } else if (s.type === "enum") {
    control = `<select id="${id}" data-key="${esc(s.key)}">${s.value == null ? `<option value="" selected>Not set</option>` : ""}${(s.options || []).map((o) => {
      const v = typeof o === "object" ? o.value : o, l = typeof o === "object" ? o.label : o;
      return `<option value="${esc(v)}" ${String(v) === String(s.value) ? "selected" : ""}>${esc(l)}</option>`;
    }).join("")}</select>`;
  } else {
    control = `<input type="number" id="${id}" data-key="${esc(s.key)}" value="${s.value ?? ""}" ${s.min != null ? `min="${s.min}"` : ""} ${s.max != null ? `max="${s.max}"` : ""} style="width:90px">`;
  }
  return `<div class="setting"><label for="${id}">${esc(s.label)}</label>${control}</div>`;
}

$("settings").addEventListener("change", async (e) => {
  const el = e.target;
  const key = el.dataset.key;
  if (!key) return;
  const def = (ins.settings || []).find((s) => s.key === key);
  let value = el.type === "checkbox" ? el.checked : el.value;
  if (def?.type === "int") value = Number(value);
  if (def?.type === "enum" && def.options?.length && typeof (def.options[0].value ?? def.options[0]) === "number") value = Number(value);
  el.disabled = true;
  try {
    await api("/api/settings", "POST", { key, value });
    log("info", `Watch setting changed: ${def?.label || key}`);
  } catch (err) {
    log("error", `Setting ${def?.label || key}: ${err.message}`);
    if (el.type === "checkbox") el.checked = !el.checked;
  }
  el.disabled = false;
});

$("notify-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const status = $("notify-status");
  status.textContent = "Sending…";
  try {
    await api("/api/notify", "POST", { title: $("n-title").value, body: $("n-body").value, kind: $("n-kind").value });
    status.textContent = "Sent. Check your wrist.";
  } catch (err) {
    status.textContent = `Not sent: ${err.message}`;
  }
});

$("time-btn").addEventListener("click", async () => {
  const btn = $("time-btn");
  btn.disabled = true;
  try {
    await api("/api/time/sync", "POST");
    btn.textContent = "Clock set";
    setTimeout(() => { btn.textContent = "Set watch clock"; }, 2000);
  } catch (err) {
    log("error", `Set watch clock: ${err.message}`);
  }
  btn.disabled = state.status?.connection !== "connected";
});

const PROFILE_FIELDS = { age: "p-age", sex: "p-sex", weight_kg: "p-weight", height_cm: "p-height", max_hr: "p-maxhr", resting_hr: "p-rhr" };

async function loadProfile() {
  try {
    const p = await api("/api/profile");
    for (const [k, id] of Object.entries(PROFILE_FIELDS)) $(id).value = p[k] ?? "";
  } catch {
    /* insights not available yet */
  }
}

$("profile-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const body = {};
  for (const [k, id] of Object.entries(PROFILE_FIELDS)) {
    const v = $(id).value;
    body[k] = v === "" ? null : k === "sex" ? v : Number(v);
  }
  try {
    await api("/api/profile", "POST", body);
    $("profile-status").textContent = "Saved. Zones, VO2max and calories now use it.";
    loadDaily();
  } catch (err) {
    $("profile-status").textContent = `Not saved: ${err.message}`;
  }
});

// ---------- Wiring ----------

$("health-sync-btn").addEventListener("click", syncHealth);
$("check-btn").addEventListener("click", startCheck);
$("check-file").addEventListener("change", (e) => loadSession(e.target.value));
document.querySelectorAll("#check-len button").forEach((b) => b.addEventListener("click", () => {
  document.querySelectorAll("#check-len button").forEach((o) => o.setAttribute("aria-checked", String(o === b)));
  ins.checkSeconds = Number(b.dataset.seconds);
}));

new ResizeObserver(() => { renderTrends(); renderSession(); }).observe($("trends"));

document.addEventListener("DOMContentLoaded", () => {
  loadDaily();
  loadHealth();
  loadRecordings();
  loadProfile();
});

// ---------- Demo data for the new endpoints ----------

window.extraDemoRoutes = (() => {
  const now = Date.now() / 1000;
  const today = new Date();
  const dates = [...Array(14)].map((_, i) => {
    const d = new Date(today); d.setDate(d.getDate() - 13 + i);
    return d.toLocaleDateString("sv");
  });
  const rnd = (a, b) => a + Math.random() * (b - a);
  const days = dates.map((date, i) => ({
    date, resting_hr: Math.round(rnd(54, 61)), hrv_rmssd: Math.round(rnd(38, 62)),
    sleep_minutes: Math.round(rnd(340, 470)), sleep_efficiency: rnd(0.84, 0.95), deep_min: Math.round(rnd(50, 95)), light_min: Math.round(rnd(200, 260)),
    rem_min: Math.round(rnd(60, 110)), awake_min: Math.round(rnd(10, 40)), sleep_score: Math.round(rnd(62, 88)),
    steps: Math.round(rnd(3500, 12500)), active_minutes: Math.round(rnd(25, 110)), strain: rnd(6, 16), trimp: rnd(40, 160),
    hr_zones: { z1: 40, z2: 25, z3: 12, z4: 5, z5: 1 }, spo2_avg: Math.round(rnd(95, 98)), stress_avg: Math.round(rnd(22, 48)),
    readiness: Math.round(rnd(52, 86)), calories_active: Math.round(rnd(250, 700)),
  }));
  const rr = [];
  let t = 0;
  for (let i = 0; i < 70; i++) {
    const v = 820 + 45 * Math.sin(i / 2.6) + rnd(-18, 18) + (i === 31 ? -260 : i === 32 ? 240 : 0);
    t += v / 1000;
    rr.push({ t, rr_ms: v, ectopic: i === 31 });
  }
  const preview = [];
  for (let i = 0; i < 60 * 25; i++) {
    const ph = ((i / 25) * 1.22) % 1;
    preview.push((ph < 0.15 ? ph / 0.15 : Math.exp(-(ph - 0.15) * 4)) * 100 + rnd(-4, 4));
  }
  const session = {
    file: "20261101-031200.csv", start: now - 600, duration_s: 60, signal_quality: 0.91, usable_s: 57, hr_bpm: 72.8, beats: 71,
    hrv: { mean_rr_ms: 822, sdnn_ms: 48, rmssd_ms: 44, pnn50: 0.21, sd1_ms: 31, sd2_ms: 61, lf: 812, hf: 640, lf_hf: 1.27, stress_index: 96 },
    rhythm: { status: "regular", ectopic_beats: 1, irregularity: 0.06, notes: ["One premature beat with a compensatory pause."] },
    respiratory_rate: 14.2, rr, ppg_preview: preview, beat_times: rr.map((p) => p.t), motion: [],
  };
  const series = (lo, hi, every = 3600) => {
    const out = [];
    for (let ts = now - 14 * 86400; ts < now; ts += every) out.push({ ts, value: Math.round(rnd(lo, hi)) });
    return out;
  };
  return {
    "/api/insights/daily": () => ({
      days,
      today: { readiness: 74, strain: 9.4, sleep_score: 81, body_battery: 63,
        recommendation: "You recovered well. A moderate session today fits; keep the hard effort under an hour.",
        readiness_factors: [
          { name: "HRV", impact: "positive", detail: "is 8% above your 28-day baseline." },
          { name: "Resting heart rate", impact: "neutral", detail: "matches your baseline at 57 bpm." },
          { name: "Sleep", impact: "positive", detail: "7 h 12 min with 1 h 24 min of deep sleep." },
          { name: "Training load", impact: "negative", detail: "the last 3 days were above your usual load." },
        ] },
      trends: { resting_hr_7d: 57, resting_hr_28d: 58, hrv_7d: 51, hrv_28d: 47, vo2max_estimate: 44.6, fitness_age: 29,
        sleep_regularity: 78, cardio_load_7d: 612, acute_chronic_ratio: 1.18 },
      alerts: [{ level: "info", title: "Late bedtimes", detail: "Three of the last five nights started after 1 AM, which lowers sleep regularity." }],
    }),
    "/api/health": () => ({
      series: { spo2: series(94, 99), stress: series(15, 60), pai: series(40, 90, 86400).map((p) => ({ ...p, total: p.value })),
        resting_hr: series(54, 60, 86400), max_hr: [], manual_hr: [], temperature: [], sleep_respiratory_rate: series(13, 16, 86400), hrv: [] },
      sleep_sessions: dates.slice(-5).map((d, i) => ({ start: now - (5 - i) * 86400 - 6 * 3600, end: now - (5 - i) * 86400 + 3600,
        deep_min: Math.round(rnd(50, 95)), light_min: Math.round(rnd(200, 260)), rem_min: Math.round(rnd(60, 110)), awake_min: Math.round(rnd(10, 40)) })),
      workouts: [{ start: now - 2 * 86400, end: now - 2 * 86400 + 2700, type: "Outdoor running", duration_s: 2700, avg_hr: 148, max_hr: 171, calories: 412, steps: 6120, distance_m: 6340 }],
      last_sync: now - 300,
    }),
    "/api/health/sync": () => ({ types: { spo2: { added: 12, supported: true, note: "" } } }),
    "/api/insights/recordings": () => ({ recordings: [{ file: session.file, start: session.start, duration_s: 60, ppg_samples: 1500 }] }),
    "/api/insights/session": () => session,
    "/api/insights/measure": () => session,
    "/api/profile": () => ({ age: 27, sex: "male", weight_kg: 70, height_cm: 175, max_hr: null, resting_hr: null }),
    "/api/settings": () => ({ settings: [
      { key: "hr_interval", label: "All-day heart rate", type: "enum", value: 1, options: [{ value: 0, label: "Off" }, { value: 1, label: "Every minute" }, { value: 5, label: "Every 5 min" }, { value: 10, label: "Every 10 min" }, { value: 30, label: "Every 30 min" }] },
      { key: "stress_monitoring", label: "Stress monitoring", type: "bool", value: true },
      { key: "hr_sleep_detection", label: "Sleep breathing quality", type: "bool", value: true },
      { key: "lift_wrist", label: "Lift wrist to wake", type: "bool", value: true },
      { key: "inactivity_warnings", label: "Move reminders", type: "bool", value: false },
      { key: "high_hr_alert", label: "High heart rate alert", type: "int", value: 150, min: 100, max: 200 },
    ] }),
    "/api/settings#post": () => ({ ok: true }),
    "/api/notify": () => ({ ok: true }),
    "/api/time/sync": () => ({ ok: true, time: new Date().toISOString() }),
  };
})();
