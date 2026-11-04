"use strict";

const $ = (id) => document.getElementById(id);
const DEMO = new URLSearchParams(location.search).has("demo");

const state = {
  status: null,
  samples: [],          // [{ts, bpm}] for the selected range, oldest first
  minutes: 10,
  deviceInfo: null,
  battery: null,
  events: [],
};

// Later modules (insights.js) register here to react to status, socket messages and connecting.
const hooks = { status: [], message: [], connected: [] };

const CONNECTING = new Set(["scanning", "connecting", "authenticating"]);
const LINK_TEXT = {
  disconnected: "Not connected",
  scanning: "Looking for the watch…",
  connecting: "Connecting…",
  authenticating: "Unlocking with your key…",
  connected: "Connected",
  error: "Connection failed",
};

// ---------- API ----------

async function api(path, method = "GET", payload) {
  if (DEMO) return demoApi(path, method);
  const opts = { method };
  if (payload !== undefined) {
    opts.body = JSON.stringify(payload);
    opts.headers = { "Content-Type": "application/json" };
  }
  const res = await fetch(path, opts);
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body.error || `${res.status} ${res.statusText}`);
  return body;
}

function connectSocket() {
  if (DEMO) return demoSocket();
  const ws = new WebSocket(`${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/ws`);
  ws.onmessage = (e) => handleMessage(JSON.parse(e.data));
  ws.onclose = () => {
    setNotice("Lost contact with the dashboard server. Retrying…", "error");
    setTimeout(connectSocket, 2000);
  };
  ws.onopen = () => setNotice(null);
}

function handleMessage(msg) {
  if (msg.type === "status") {
    const { type, ...status } = msg;
    applyStatus(status);
  } else if (msg.type === "hr") {
    onHeartRate(msg.ts, msg.bpm);
  } else if (msg.type === "raw") {
    onRaw(msg);
  } else if (msg.type === "event") {
    state.events.unshift({ ts: msg.ts, level: msg.level, msg: msg.msg });
    state.events.length = Math.min(state.events.length, 200);
    renderLog();
    // A sync may be started by another open page, so refresh whenever the server reports one finished.
    if (msg.msg.startsWith("Activity sync done")) loadActivity();
  }
  for (const fn of hooks.message) fn(msg);
}

// ---------- Status ----------

function applyStatus(status) {
  const prev = state.status;
  state.status = status;
  if (status.battery) state.battery = status.battery;
  renderStatus();
  const nowConnected = status.connection === "connected";
  const wasConnected = prev && prev.connection === "connected";
  if (nowConnected && !wasConnected) {
    loadDeviceInfo();
    loadGatt();
    if (!state.autoSynced) {
      state.autoSynced = true;
      syncActivity();
    }
    for (const fn of hooks.connected) fn();
  }
}

function renderStatus() {
  const s = state.status;
  if (!s) return;
  const conn = s.connection;
  const connected = conn === "connected";

  $("link").dataset.state = conn;
  let text = LINK_TEXT[conn] || conn;
  if (connected && s.connected_since) text = `Connected for ${duration(Date.now() / 1000 - s.connected_since)}`;
  if (conn === "error" && s.error) text = `Connection failed: ${s.error}`;
  $("link-text").textContent = text;

  const btn = $("connect-btn");
  btn.disabled = CONNECTING.has(conn);
  btn.textContent = connected ? "Disconnect" : CONNECTING.has(conn) ? "Connecting…" : "Connect";
  btn.classList.toggle("btn-primary", !connected);

  if (conn === "error") {
    setNotice("The watch did not answer. If your phone is nearby with Bluetooth on, it is probably holding the watch: turn off the phone's Bluetooth, then connect again.", "error");
    state.connNotice = true;
  } else if (state.connNotice) {
    setNotice(null);
    state.connNotice = false;
  }

  for (const id of ["refresh-btn", "vibrate-btn", "gatt-btn"]) $(id).disabled = !connected;
  $("sync-btn").disabled = !connected || state.syncing;

  const hrBtn = $("hr-btn");
  hrBtn.disabled = !connected;
  hrBtn.setAttribute("aria-pressed", String(!!s.hr?.streaming));
  hrBtn.textContent = s.hr?.streaming ? "Stop live heart rate" : "Start live heart rate";

  const rawOn = !!s.raw?.streaming;
  const rawBtn = $("raw-btn");
  rawBtn.disabled = !connected;
  rawBtn.setAttribute("aria-pressed", String(rawOn));
  rawBtn.textContent = rawOn ? "Stop raw sensors" : "Start raw sensors";
  $("wave-file").hidden = !(rawOn && s.raw?.file);
  if (rawOn && s.raw?.file) $("wave-file").textContent = `Recording every sample to ${s.raw.file}`;

  renderReadout();
  renderBattery();
  renderFacts();
  for (const fn of hooks.status) fn(s, connected);
}

function renderReadout() {
  const s = state.status;
  const hr = s?.hr || {};
  const fresh = hr.last_bpm && hr.last_ts && Date.now() / 1000 - hr.last_ts < 120;
  $("bpm").textContent = hr.last_bpm ? hr.last_bpm : "–";
  $("bpm-wrap").dataset.live = String(!!(fresh && hr.streaming));

  let meta;
  if (!s || s.connection !== "connected") meta = s?.connection === "error"
    ? "Could not reach the watch."
    : CONNECTING.has(s?.connection) ? "Connecting to the watch…" : "Connect to the watch to see your heart rate.";
  else if (hr.streaming && !hr.last_bpm) meta = "Measuring. The first reading can take up to 30 seconds; keep the watch snug on your wrist.";
  else if (hr.last_ts) meta = hr.streaming ? `Live, updated ${ago(hr.last_ts)}.` : `Last reading ${ago(hr.last_ts)}.`;
  else meta = "Start live heart rate to measure now.";
  $("bpm-meta").textContent = meta;
}

function renderBattery() {
  const b = state.battery;
  $("battery").hidden = !b;
  if (!b) return;
  $("battery-fill").style.width = `${b.level}%`;
  $("battery").dataset.low = String(b.level <= 20);
  $("battery-text").textContent = `${b.level}%${b.charging ? " charging" : ""}`;
}

function renderFacts() {
  const s = state.status || {};
  const b = state.battery;
  const d = state.deviceInfo || {};
  $("f-model").textContent = s.model || "–";
  $("f-mac").textContent = s.mac || "–";
  $("f-firmware").textContent = d.software || s.firmware || "–";
  $("f-battery").textContent = b ? `${b.level}%, ${b.charging ? "charging" : "not charging"}` : "–";
  const charged = b ? [b.last_charge_a, b.last_charge_b].filter(Boolean).sort().pop() : null;
  $("f-charged").textContent = charged ? formatDate(charged) : "–";
  $("f-serial").textContent = d.serial || "–";
  $("f-hardware").textContent = d.hardware || "–";
  $("f-uptime").textContent = s.connection === "connected" && s.connected_since
    ? duration(Date.now() / 1000 - s.connected_since) : "–";
}

// ---------- Heart rate ----------

function onHeartRate(ts, bpm) {
  if (!state.status) return;
  if (bpm > 0) {
    state.status.hr = { ...(state.status.hr || {}), last_bpm: bpm, last_ts: ts };
    state.samples.push({ ts, bpm });
    trimSamples();
    const wrap = $("bpm-wrap");
    wrap.classList.remove("beat");
    void wrap.offsetWidth;
    wrap.classList.add("beat");
  }
  renderReadout();
  renderChart();
}

function trimSamples() {
  const cutoff = Date.now() / 1000 - state.minutes * 60;
  state.samples = state.samples.filter((p) => p.ts >= cutoff);
}

async function loadHistory() {
  try {
    const { samples } = await api(`/api/hr/history?minutes=${state.minutes}`);
    state.samples = samples.filter((p) => p.bpm > 0).sort((a, b) => a.ts - b.ts);
  } catch {
    state.samples = [];
  }
  renderChart();
}

function renderChart() {
  const pts = state.samples;
  const svg = $("chart-svg");
  const box = $("chart").getBoundingClientRect();
  const W = Math.max(box.width, 1), H = Math.max(box.height, 1);
  const pad = { l: 14, r: 44, t: 18, b: 26 };

  const bpms = pts.map((p) => p.bpm);
  const count = bpms.length;
  $("stat-count").textContent = count;
  $("stat-min").textContent = count ? Math.min(...bpms) : "–";
  $("stat-max").textContent = count ? Math.max(...bpms) : "–";
  $("stat-avg").textContent = count ? Math.round(bpms.reduce((a, b) => a + b, 0) / count) : "–";
  $("chart-empty").hidden = count > 0;

  const now = Date.now() / 1000;
  const t0 = now - state.minutes * 60;
  const lo = count ? Math.max(30, Math.floor((Math.min(...bpms) - 8) / 10) * 10) : 50;
  const hi = count ? Math.min(220, Math.ceil((Math.max(...bpms) + 8) / 10) * 10) : 110;
  const x = (t) => pad.l + ((t - t0) / (now - t0)) * (W - pad.l - pad.r);
  const y = (v) => pad.t + (1 - (v - lo) / (hi - lo)) * (H - pad.t - pad.b);

  const parts = [];
  const stepV = hi - lo > 60 ? 20 : 10;
  for (let v = lo; v <= hi; v += stepV) {
    parts.push(`<line class="axis-line" x1="${pad.l}" x2="${W - pad.r}" y1="${y(v)}" y2="${y(v)}"/>`);
    parts.push(`<text class="axis-label" x="${W - pad.r + 8}" y="${y(v) + 4}">${v}</text>`);
  }
  const ticks = state.minutes <= 10 ? 5 : state.minutes <= 60 ? 6 : 6;
  for (let i = 0; i <= ticks; i++) {
    const t = t0 + (i / ticks) * (now - t0);
    const anchor = i === 0 ? "start" : i === ticks ? "end" : "middle";
    parts.push(`<text class="axis-label" x="${x(t)}" y="${H - 6}" text-anchor="${anchor}">${i === ticks ? "now" : clock(t)}</text>`);
  }

  if (count) {
    // Break the line where readings are more than 3 minutes apart, so gaps show as gaps.
    let d = "";
    pts.forEach((p, i) => {
      const gap = i === 0 || p.ts - pts[i - 1].ts > 180;
      d += `${gap ? "M" : "L"}${x(p.ts).toFixed(1)},${y(p.bpm).toFixed(1)}`;
    });
    parts.push(`<path class="trace" d="${d}"/>`);
    if (count <= 120) {
      for (const p of pts.slice(0, -1)) parts.push(`<circle class="trace-dot" cx="${x(p.ts)}" cy="${y(p.bpm)}" r="2.5"/>`);
    }
    const last = pts[count - 1];
    parts.push(`<circle class="trace-last" cx="${x(last.ts)}" cy="${y(last.bpm)}" r="5"/>`);
  }

  svg.setAttribute("viewBox", `0 0 ${W} ${H}`);
  svg.innerHTML = parts.join("");
}

// ---------- Panels ----------

async function loadDeviceInfo() {
  try {
    const [info, battery] = await Promise.all([api("/api/device-info"), api("/api/battery")]);
    state.deviceInfo = info;
    state.battery = battery;
  } catch (e) {
    log("error", `Could not read watch details: ${e.message}`);
  }
  renderBattery();
  renderFacts();
}

async function loadGatt() {
  const box = $("gatt");
  try {
    const { services } = await api("/api/gatt");
    const chars = services.reduce((n, s) => n + s.characteristics.length, 0);
    $("gatt-copy").textContent = `${services.length} services, ${chars} characteristics. New features usually start here.`;
    box.innerHTML = services.map((s) => `
      <details>
        <summary><span>${esc(s.name || "Unknown service")} <span class="hex">${shortUuid(s.uuid)}</span></span><span class="count">${s.characteristics.length}</span></summary>
        <ul>${s.characteristics.map((c) => `<li><span class="hex">${shortUuid(c.uuid)}</span><span class="props">${c.properties.map(esc).join(", ")}</span></li>`).join("")}</ul>
      </details>`).join("");
    $("gatt-btn").textContent = "Reload";
  } catch (e) {
    box.innerHTML = "";
    $("gatt-copy").textContent = `Could not load the service list: ${e.message}`;
  }
}

async function vibrate() {
  const btn = $("vibrate-btn");
  btn.disabled = true;
  try {
    await api("/api/vibrate", "POST");
    btn.textContent = "Vibrated";
    setTimeout(() => { btn.textContent = "Vibrate watch"; }, 2000);
  } catch (e) {
    log("error", `Vibrate: ${e.message}`);
  }
  btn.disabled = state.status?.connection !== "connected";
}

// ---------- Raw sensors: live PPG + accelerometer ----------

const WAVE_SECONDS = 8;
const RATE = 25;
const wave = { ppg: [], accel: [], dirty: false };

function onRaw(msg) {
  const cap = WAVE_SECONDS * RATE * 2;
  if (msg.ppg?.length) wave.ppg.push(...msg.ppg);
  if (msg.accel?.length) wave.accel.push(...msg.accel);
  if (wave.ppg.length > cap) wave.ppg.splice(0, wave.ppg.length - cap);
  if (wave.accel.length > cap) wave.accel.splice(0, wave.accel.length - cap);
  if (!wave.dirty) {
    wave.dirty = true;
    requestAnimationFrame(drawWave);
  }
}

// The PPG drifts slowly with pressure and ambient light; subtract a 1 s moving
// average and flip it so each heartbeat points up, like a pulse trace.
function detrend(values) {
  const out = [];
  let sum = 0;
  for (let i = 0; i < values.length; i++) {
    sum += values[i];
    if (i >= RATE) sum -= values[i - RATE];
    const n = Math.min(i + 1, RATE);
    out.push(sum / n - values[i]);
  }
  return out.slice(Math.min(RATE, Math.floor(out.length / 2)));
}

function estimateBpm(signal) {
  if (signal.length < RATE * 4) return null;
  const s = signal.slice(-RATE * WAVE_SECONDS);
  const mean = s.reduce((a, b) => a + b, 0) / s.length;
  const sd = Math.sqrt(s.reduce((a, b) => a + (b - mean) ** 2, 0) / s.length);
  const peaks = [];
  for (let i = 2; i < s.length - 2; i++) {
    const v = s[i];
    if (v > mean + 0.3 * sd && v >= s[i - 1] && v >= s[i - 2] && v > s[i + 1] && v > s[i + 2]
        && (!peaks.length || i - peaks[peaks.length - 1] >= RATE * 0.33)) peaks.push(i);
  }
  if (peaks.length < 3) return null;
  const gaps = peaks.slice(1).map((p, i) => p - peaks[i]).sort((a, b) => a - b);
  const median = gaps[Math.floor(gaps.length / 2)];
  const bpm = Math.round((60 * RATE) / median);
  return bpm >= 35 && bpm <= 200 ? bpm : null;
}

function sizeCanvas(canvas) {
  const r = canvas.getBoundingClientRect();
  const dpr = window.devicePixelRatio || 1;
  const w = Math.max(1, Math.round(r.width * dpr)), h = Math.max(1, Math.round(r.height * dpr));
  if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
  const ctx = canvas.getContext("2d");
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, r.width, r.height);
  return { ctx, w: r.width, h: r.height };
}

function cssVar(name) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

function plotLine(ctx, values, w, h, lo, hi, color, width, top = 26, bottom = 10) {
  if (values.length < 2) return;
  const n = WAVE_SECONDS * RATE;
  const start = Math.max(0, values.length - n);
  const span = hi - lo || 1;
  ctx.beginPath();
  for (let i = start; i < values.length; i++) {
    const x = w - ((values.length - 1 - i) / (n - 1)) * w;
    const y = top + (1 - (values[i] - lo) / span) * (h - top - bottom);
    i === start ? ctx.moveTo(x, y) : ctx.lineTo(x, y);
  }
  ctx.strokeStyle = color;
  ctx.lineWidth = width;
  ctx.lineJoin = "round";
  ctx.stroke();
}

function drawWave() {
  wave.dirty = false;
  const ppg = detrend(wave.ppg);
  $("ppg-empty").hidden = wave.ppg.length > 0;

  {
    const { ctx, w, h } = sizeCanvas($("ppg-canvas"));
    const recent = ppg.slice(-WAVE_SECONDS * RATE);
    if (recent.length > 1) {
      const lo = Math.min(...recent), hi = Math.max(...recent);
      const pad = (hi - lo) * 0.12;
      plotLine(ctx, recent, w, h, lo - pad, hi + pad, cssVar("--ink"), 2.25);
    }
  }
  {
    const { ctx, w, h } = sizeCanvas($("accel-canvas"));
    const recent = wave.accel.slice(-WAVE_SECONDS * RATE);
    if (recent.length > 1) {
      const all = recent.flat();
      const lo = Math.min(...all), hi = Math.max(...all);
      const pad = Math.max(0.05, (hi - lo) * 0.1);
      ["--ink", "--pulse", "--sleep-light"].forEach((c, axis) =>
        plotLine(ctx, recent.map((v) => v[axis]), w, h, lo - pad, hi + pad, cssVar(c), 1.5, 24, 8));
    }
  }

  const bpm = estimateBpm(ppg);
  $("wave-bpm").textContent = bpm ?? "–";
  const acc = wave.accel.slice(-RATE * 2);
  if (acc.length > 4) {
    const mags = acc.map(([x, y, z]) => Math.hypot(x, y, z));
    const mean = mags.reduce((a, b) => a + b, 0) / mags.length;
    const sd = Math.sqrt(mags.reduce((a, b) => a + (b - mean) ** 2, 0) / mags.length);
    $("wave-motion").textContent = sd < 0.02 ? "Still" : sd < 0.08 ? "Light" : "Moving";
  }
}

async function toggleRaw() {
  const on = !!state.status?.raw?.streaming;
  const btn = $("raw-btn");
  btn.disabled = true;
  try {
    const body = !on && window.selectedSensors ? { sensors: window.selectedSensors() } : undefined;
    const { streaming } = await api(on ? "/api/raw/stop" : "/api/raw/start", "POST", body);
    state.status.raw = { ...(state.status.raw || {}), streaming };
    if (streaming) { wave.ppg = []; wave.accel = []; }
  } catch (e) {
    log("error", `Raw sensors: ${e.message}`);
  }
  renderStatus();
}

// ---------- Activity history ----------

const act = { hours: 24, data: null };

async function loadActivity() {
  try {
    act.data = await api(`/api/activity?hours=${act.hours}`);
  } catch {
    act.data = null;
  }
  renderActivity();
}

async function syncActivity() {
  if (state.syncing) return;
  state.syncing = true;
  const btn = $("sync-btn");
  btn.disabled = true;
  btn.textContent = "Syncing…";
  try {
    const r = await api("/api/activity/sync", "POST");
    log("info", r.minutes_added ? `Synced ${r.minutes_added} minutes of activity` : "Activity already up to date");
  } catch (e) {
    if (!/already running/.test(e.message)) log("error", `Activity sync: ${e.message}`);
  }
  state.syncing = false;
  btn.textContent = "Sync from watch";
  btn.disabled = state.status?.connection !== "connected";
  loadActivity();
}

function hm(minutes) {
  const h = Math.floor(minutes / 60), m = minutes % 60;
  return h ? `${h}<small>h</small> ${m}<small>m</small>` : `${m}<small>m</small>`;
}

function renderActivity() {
  const d = act.data;
  const samples = d?.samples || [];
  const s = d?.summary;
  $("act-empty").hidden = samples.length > 0;
  $("a-steps").textContent = s ? s.steps.toLocaleString() : "–";
  $("a-active").innerHTML = s ? hm(s.active_minutes) : "–";
  $("a-sleep").innerHTML = s ? hm(s.sleep_minutes) : "–";
  $("a-worn").innerHTML = s ? hm(s.worn_minutes) : "–";
  $("a-hr").innerHTML = s && s.hr_min != null ? `${s.hr_min}–${s.hr_max}<small> avg ${Math.round(s.hr_avg)}</small>` : "–";
  $("act-sync-text").textContent = d?.last_sync
    ? `Last synced ${ago(d.last_sync)}. Syncing reads the watch's history without deleting anything, so Zepp still gets it later.`
    : "Minute-by-minute history stored on the watch. Syncing reads it without deleting anything, so Zepp still gets it later.";

  const { ctx, w, h } = sizeCanvas($("act-canvas"));
  const now = Date.now() / 1000;
  const t0 = now - act.hours * 3600;
  const pad = { l: 10, r: 40, t: 10, b: 24 };
  const band = 26;
  const plotTop = pad.t + band + 10;
  const plotH = h - plotTop - pad.b;
  const x = (t) => pad.l + ((t - t0) / (now - t0)) * (w - pad.l - pad.r);
  const minuteW = Math.max(1, (w - pad.l - pad.r) / (act.hours * 60));
  const color = {
    sleep_deep: cssVar("--sleep-deep"), sleep_light: cssVar("--sleep-light"), sleep_rem: cssVar("--sleep-rem"),
    active: cssVar("--pulse"), idle: cssVar("--idle"), charging: cssVar("--warn"),
  };
  const soft = cssVar("--ink-soft"), line = cssVar("--line");

  // Axis: hour ticks
  ctx.font = `500 11px ${cssVar("--text")}`;
  ctx.fillStyle = soft;
  const pxPerHour = (w - pad.l - pad.r) / act.hours;
  const tickEvery = [1, 3, 6, 12, 24, 48].find((n) => n * pxPerHour >= 64) || 48;
  const first = new Date(t0 * 1000); first.setMinutes(0, 0, 0);
  for (let t = first.getTime() / 1000 + 3600; t < now; t += 3600) {
    const hour = new Date(t * 1000).getHours();
    // Every 48 h means midnight on every other day.
    if (tickEvery <= 24 ? hour % tickEvery : (hour || Math.floor(t / 86400) % 2)) continue;
    const px = x(t);
    ctx.fillStyle = line; ctx.fillRect(px, pad.t, 1, h - pad.t - pad.b);
    ctx.fillStyle = soft;
    const label = hour === 0 && tickEvery >= 12
      ? new Date(t * 1000).toLocaleDateString([], { weekday: "short" })
      : `${String(hour).padStart(2, "0")}:00`;
    ctx.fillText(label, px + 3, h - 7);
  }

  if (!samples.length) return;

  // State band; not-worn minutes get a hatch so they read as "no data" rather than blank
  const hatch = document.createElement("canvas");
  hatch.width = hatch.height = 6;
  const hctx = hatch.getContext("2d");
  hctx.strokeStyle = line;
  hctx.lineWidth = 1.5;
  hctx.beginPath(); hctx.moveTo(0, 6); hctx.lineTo(6, 0); hctx.stroke();
  const hatchFill = ctx.createPattern(hatch, "repeat");
  for (const p of samples) {
    const c = p.state === "not_worn" ? hatchFill : color[p.state];
    if (!c) continue;
    ctx.fillStyle = c;
    ctx.fillRect(x(p.ts), pad.t, minuteW + 0.5, band);
  }

  // Steps as bars (per minute, max ~200)
  const maxSteps = Math.max(30, ...samples.map((p) => p.steps));
  ctx.fillStyle = color.active;
  ctx.globalAlpha = 0.55;
  for (const p of samples) {
    if (!p.steps) continue;
    const bh = (p.steps / maxSteps) * plotH * 0.9;
    ctx.fillRect(x(p.ts), plotTop + plotH - bh, minuteW + 0.5, bh);
  }
  ctx.globalAlpha = 1;

  // Heart rate line, breaking over gaps
  const hrs = samples.filter((p) => p.hr);
  if (hrs.length) {
    const lo = Math.max(30, Math.min(...hrs.map((p) => p.hr)) - 5);
    const hi = Math.max(...hrs.map((p) => p.hr)) + 5;
    const y = (v) => plotTop + (1 - (v - lo) / (hi - lo)) * plotH;
    ctx.beginPath();
    hrs.forEach((p, i) => {
      const gap = i === 0 || p.ts - hrs[i - 1].ts > 600;
      gap ? ctx.moveTo(x(p.ts), y(p.hr)) : ctx.lineTo(x(p.ts), y(p.hr));
    });
    ctx.strokeStyle = cssVar("--ink");
    ctx.lineWidth = 1.5;
    ctx.lineJoin = "round";
    ctx.stroke();
    ctx.fillStyle = soft;
    for (const v of [lo, Math.round((lo + hi) / 2), hi]) ctx.fillText(String(Math.round(v)), w - pad.r + 6, y(v) + 4);
  }
}

function renderLog() {
  const list = $("log");
  if (!state.events.length) {
    list.innerHTML = `<li class="log-empty">Nothing yet. Connection steps and errors will show up here.</li>`;
    return;
  }
  list.innerHTML = state.events.slice(0, 100).map((e) =>
    `<li data-level="${esc(e.level)}"><time>${clock(e.ts, true)}</time><span>${esc(e.msg)}</span></li>`).join("");
}

function log(level, msg) {
  state.events.unshift({ ts: Date.now() / 1000, level, msg });
  renderLog();
}

function setNotice(text, level = "info") {
  const n = $("notice");
  n.hidden = !text;
  n.textContent = text || "";
  n.dataset.level = text ? level : "";
}

// ---------- Formatting ----------

function esc(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}
function shortUuid(u) {
  const m = /^0000([0-9a-f]{4})-0000-1000-8000-00805f9b34fb$/i.exec(u) || /^0000([0-9a-f]{4})-0000-3512-2118-0009af100700$/i.exec(u);
  return m ? `0x${m[1].toUpperCase()}` : u;
}
function clock(ts, seconds = false) {
  const opts = { hour: "2-digit", minute: "2-digit", hour12: false };
  if (seconds) opts.second = "2-digit";
  return new Date(ts * 1000).toLocaleTimeString([], opts);
}
function formatDate(iso) {
  const d = new Date(iso);
  return isNaN(d) ? iso : d.toLocaleString([], { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit", hour12: false });
}
function duration(sec) {
  sec = Math.max(0, Math.round(sec));
  if (sec < 60) return `${sec} s`;
  const m = Math.floor(sec / 60);
  if (m < 60) return `${m} min`;
  return `${Math.floor(m / 60)} h ${m % 60} min`;
}
function ago(ts) {
  const s = Math.round(Date.now() / 1000 - ts);
  return s < 5 ? "just now" : `${duration(s)} ago`;
}

// ---------- Wiring ----------

$("connect-btn").addEventListener("click", async () => {
  const connected = state.status?.connection === "connected";
  try {
    applyStatus(await api(connected ? "/api/disconnect" : "/api/connect", "POST"));
  } catch (e) {
    setNotice(e.message, "error");
  }
});

$("hr-btn").addEventListener("click", async () => {
  const on = !!state.status?.hr?.streaming;
  const btn = $("hr-btn");
  btn.disabled = true;
  try {
    const { streaming } = await api(on ? "/api/hr/stop" : "/api/hr/start", "POST");
    state.status.hr = { ...(state.status.hr || {}), streaming };
  } catch (e) {
    log("error", `Heart rate: ${e.message}`);
  }
  renderStatus();
});

$("refresh-btn").addEventListener("click", loadDeviceInfo);
$("gatt-btn").addEventListener("click", loadGatt);
$("vibrate-btn").addEventListener("click", vibrate);
$("raw-btn").addEventListener("click", toggleRaw);
$("sync-btn").addEventListener("click", syncActivity);

document.querySelectorAll("#act-ranges button").forEach((b) => b.addEventListener("click", () => {
  document.querySelectorAll("#act-ranges button").forEach((o) => o.setAttribute("aria-selected", String(o === b)));
  act.hours = Number(b.dataset.hours);
  loadActivity();
}));

document.querySelectorAll("#hr-ranges button").forEach((b) => b.addEventListener("click", () => {
  document.querySelectorAll("#hr-ranges button").forEach((o) => o.setAttribute("aria-selected", String(o === b)));
  state.minutes = Number(b.dataset.minutes);
  loadHistory();
}));

new ResizeObserver(() => renderChart()).observe($("chart"));
new ResizeObserver(() => renderActivity()).observe($("timeline"));
new ResizeObserver(() => drawWave()).observe($("ppg-canvas").parentElement);
setInterval(() => { renderStatus(); renderChart(); }, 5000);

async function init() {
  renderLog();
  try {
    applyStatus(await api("/api/status"));
    const { events } = await api("/api/events?limit=100");
    state.events = events;
    renderLog();
  } catch (e) {
    setNotice(`Cannot reach the dashboard server: ${e.message}`, "error");
  }
  loadHistory();
  loadActivity();
  connectSocket();
}

// ---------- Demo mode (?demo): fake data for layout work, clearly labelled ----------

let demo;
function demoApi(path, method) {
  const now = Date.now() / 1000;
  if (!demo) {
    const history = [];
    for (let t = now - 86400; t < now; t += 45) {
      const base = 64 + 10 * Math.sin(t / 5400) + (Math.random() - 0.5) * 6;
      if (Math.random() > 0.15) history.push({ ts: t, bpm: Math.round(base) });
    }
    demo = {
      status: { connection: "connected", error: null, model: "Amazfit GTR 2e", mac: "C1:2A:3B:4C:5D:6E", firmware: "V1.7.3.1",
        battery: { level: 68, charging: false, last_charge_a: "2026-10-24T10:50:35", last_charge_b: "2026-10-31T04:10:58", raw: "", read_at: now },
        hr: { streaming: true, last_bpm: 83, last_ts: now - 4 }, raw: { streaming: true, file: "data/raw/20261101-021500.csv" },
        connected_since: now - 754, rssi: -54 },
      history,
      activity: demoActivity(now),
    };
    setNotice("Demo data: not connected to a real watch.");
  }
  const routes = {
    "/api/status": () => demo.status,
    "/api/events": () => ({ events: [
      { ts: now - 4, level: "info", msg: "Heart rate 83 bpm" },
      { ts: now - 700, level: "info", msg: "Live heart rate started" },
      { ts: now - 752, level: "info", msg: "Unlocked with auth key" },
      { ts: now - 754, level: "info", msg: "Connected to C1:2A:3B:4C:5D:6E" },
      { ts: now - 790, level: "warn", msg: "Watch not found, retrying scan" },
    ] }),
    "/api/hr/history": () => ({ samples: demo.history.filter((p) => p.ts > now - state.minutes * 60) }),
    "/api/device-info": () => ({ serial: "47512/12345678", hardware: "V0.70.19.4", software: "V1.7.3.1", system_id: null, pnp_id: null }),
    "/api/battery": () => demo.status.battery,
    "/api/gatt": () => ({ services: [
      { uuid: "0000180d-0000-1000-8000-00805f9b34fb", name: "Heart Rate", characteristics: [
        { uuid: "00002a37-0000-1000-8000-00805f9b34fb", properties: ["notify"] },
        { uuid: "00002a39-0000-1000-8000-00805f9b34fb", properties: ["read", "write"] }] },
      { uuid: "0000fee0-0000-1000-8000-00805f9b34fb", name: "Anhui Huami Information Technology Co.", characteristics: [
        { uuid: "00000001-0000-3512-2118-0009af100700", properties: ["notify", "write-without-response"] },
        { uuid: "00000006-0000-3512-2118-0009af100700", properties: ["read", "notify"] }] }] }),
    "/api/activity": () => {
      const hours = Number(new URLSearchParams(path.split("?")[1]).get("hours")) || 24;
      const samples = demo.activity.filter((p) => p.ts > now - hours * 3600);
      const hrs = samples.filter((p) => p.hr).map((p) => p.hr);
      return { samples, last_sync: now - 300, summary: {
        steps: samples.reduce((a, p) => a + p.steps, 0),
        hr_min: Math.min(...hrs), hr_max: Math.max(...hrs), hr_avg: hrs.reduce((a, b) => a + b, 0) / hrs.length,
        active_minutes: samples.filter((p) => p.state === "active").length,
        worn_minutes: samples.filter((p) => p.state !== "not_worn").length,
        sleep_minutes: samples.filter((p) => p.state.startsWith("sleep")).length } };
    },
    "/api/activity/sync": () => ({ minutes_added: 0, from: null, to: null }),
    "/api/raw/start": () => ({ streaming: true }),
    "/api/raw/stop": () => ({ streaming: false }),
    "/api/vibrate": () => ({ ok: true }),
  };
  const key = path.split("?")[0];
  const route = routes[key] || window.extraDemoRoutes?.[key] || (() => ({}));
  return new Promise((resolve) => setTimeout(() => resolve(route(path, method)), key.endsWith("/measure") ? 3000 : 80));
}
function demoActivity(now) {
  const out = [];
  const end = Math.floor(now / 60) * 60;
  for (let t = end - 7 * 86400; t < end; t += 60) {
    const h = new Date(t * 1000).getHours() + new Date(t * 1000).getMinutes() / 60;
    let state = "idle", steps = 0, hr = 62 + Math.round(Math.random() * 10);
    if (h >= 0.5 && h < 7) {
      const cycle = ((h - 0.5) * 60) % 90;
      state = cycle < 20 ? "sleep_light" : cycle < 50 ? "sleep_deep" : cycle < 70 ? "sleep_light" : "sleep_rem";
      hr = 52 + Math.round(Math.random() * 6);
    } else if ((h > 8 && h < 8.6) || (h > 18 && h < 19.2) || (h > 13 && h < 13.3)) {
      state = "active"; steps = 70 + Math.round(Math.random() * 50); hr = 95 + Math.round(Math.random() * 25);
    } else if (h > 22.5 && h < 23.2) {
      state = "not_worn"; hr = null;
    } else if (Math.random() < 0.08) {
      steps = Math.round(Math.random() * 30);
    }
    out.push({ ts: t, kind: 0, state, intensity: steps, steps, hr, sleep: 0, deep: 0, rem: 0 });
  }
  return out;
}
function demoSocket() {
  setInterval(() => {
    const ts = Date.now() / 1000;
    const bpm = 70 + Math.round(Math.random() * 18);
    demo.history.push({ ts, bpm });
    handleMessage({ type: "hr", ts, bpm });
  }, 6000);
  // Synthetic pulse: a sharp systolic rise, slower fall and a small dicrotic notch, on a drifting baseline.
  let n = 0;
  setInterval(() => {
    const ppg = [], accel = [];
    for (let i = 0; i < 5; i++, n++) {
      const t = n / RATE, phase = (t * 1.3) % 1;
      const beat = phase < 0.15 ? phase / 0.15 : Math.exp(-(phase - 0.15) * 4) + 0.15 * Math.exp(-((phase - 0.45) ** 2) / 0.003);
      ppg.push(Math.round(41000 + 600 * Math.sin(t / 6) - 160 * beat + (Math.random() - 0.5) * 12));
      accel.push([-0.76 + (Math.random() - 0.5) * 0.01, -0.17 + (Math.random() - 0.5) * 0.01, -0.63 + (Math.random() - 0.5) * 0.01]);
    }
    handleMessage({ type: "raw", ts: Date.now() / 1000, ppg, accel });
  }, 200);
}

// Wait for every script (insights.js too) so their hooks exist before the first status arrives.
document.addEventListener("DOMContentLoaded", init);
