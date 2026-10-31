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

async function api(path, method = "GET") {
  if (DEMO) return demoApi(path, method);
  const res = await fetch(path, { method });
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
  } else if (msg.type === "event") {
    state.events.unshift({ ts: msg.ts, level: msg.level, msg: msg.msg });
    state.events.length = Math.min(state.events.length, 200);
    renderLog();
  }
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

  for (const id of ["refresh-btn", "probe-btn", "gatt-btn"]) $(id).disabled = !connected;

  const hrBtn = $("hr-btn");
  hrBtn.disabled = !connected;
  hrBtn.setAttribute("aria-pressed", String(!!s.hr?.streaming));
  hrBtn.textContent = s.hr?.streaming ? "Stop live heart rate" : "Start live heart rate";

  renderReadout();
  renderBattery();
  renderFacts();
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

async function runProbe() {
  const btn = $("probe-btn");
  const out = $("probe");
  btn.disabled = true;
  btn.textContent = "Probing for 5 seconds…";
  try {
    const r = await api("/api/raw/probe", "POST");
    out.hidden = false;
    out.innerHTML = `
      <p class="probe-verdict" data-ok="${r.supported}">${r.supported ? `Streaming works: ${r.packets} packets received.` : "Refused by the watch."}</p>
      <dl>
        <dt>Start reply</dt><dd class="hex">${esc(r.replies?.start || "–")}</dd>
        <dt>Go reply</dt><dd class="hex">${esc(r.replies?.go || "–")}</dd>
        <dt>Stop reply</dt><dd class="hex">${esc(r.replies?.stop || "–")}</dd>
        <dt>Data packets</dt><dd>${r.packets}</dd>
      </dl>
      ${r.note ? `<p class="panel-copy" style="margin:12px 0 0">${esc(r.note)}</p>` : ""}`;
  } catch (e) {
    out.hidden = false;
    out.innerHTML = `<p class="probe-verdict" data-ok="false">Probe failed: ${esc(e.message)}</p>`;
  } finally {
    btn.textContent = "Run probe again";
    btn.disabled = state.status?.connection !== "connected";
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
$("probe-btn").addEventListener("click", runProbe);

document.querySelectorAll(".ranges button").forEach((b) => b.addEventListener("click", () => {
  document.querySelectorAll(".ranges button").forEach((o) => o.setAttribute("aria-selected", String(o === b)));
  state.minutes = Number(b.dataset.minutes);
  loadHistory();
}));

new ResizeObserver(() => renderChart()).observe($("chart"));
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
      status: { connection: "connected", error: null, model: "Amazfit GTR 2e", mac: "C1:0B:05:00:0A:46", firmware: "V1.7.3.1",
        battery: { level: 68, charging: false, last_charge_a: "2026-10-24T10:50:35", last_charge_b: "2026-10-31T04:10:58", raw: "", read_at: now },
        hr: { streaming: true, last_bpm: 83, last_ts: now - 4 }, connected_since: now - 754, rssi: -54 },
      history,
    };
    setNotice("Demo data: not connected to a real watch.");
  }
  const routes = {
    "/api/status": () => demo.status,
    "/api/events": () => ({ events: [
      { ts: now - 4, level: "info", msg: "Heart rate 83 bpm" },
      { ts: now - 700, level: "info", msg: "Live heart rate started" },
      { ts: now - 752, level: "info", msg: "Unlocked with auth key" },
      { ts: now - 754, level: "info", msg: "Connected to C1:0B:05:00:0A:46" },
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
    "/api/raw/probe": () => ({ supported: false, replies: { start: "10010304", go: "100204", stop: "100301" }, packets: 0, note: "Every start command is answered with status 0x03." }),
  };
  const key = path.split("?")[0];
  return new Promise((resolve) => setTimeout(() => resolve((routes[key] || (() => ({})))()), key === "/api/raw/probe" ? 1200 : 80));
}
function demoSocket() {
  setInterval(() => {
    const ts = Date.now() / 1000;
    const bpm = 70 + Math.round(Math.random() * 18);
    demo.history.push({ ts, bpm });
    handleMessage({ type: "hr", ts, bpm });
  }, 6000);
}

init();
