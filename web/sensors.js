"use strict";
// Gyroscope, compass and GPS (raw sensor mask bits 0x10, 0x40, 0x08). Builds on app.js globals.

const motion = {
  gyro: [],
  mag: [],
  magMin: [Infinity, Infinity, Infinity],
  magMax: [-Infinity, -Infinity, -Infinity],
  gps: null,
  dirty: false,
};

// Magnetometer units are undocumented; Earth's field reads ~3200 here, so a full turn spans roughly twice that.
const CAL_SPAN = 2000;

window.selectedSensors = () => [...document.querySelectorAll("#sensor-picks input:checked")].map((i) => i.value);

hooks.message.push((msg) => {
  if (msg.type === "raw") {
    const cap = WAVE_SECONDS * RATE * 2;
    if (msg.gyro?.length) motion.gyro.push(...msg.gyro);
    if (msg.mag?.length) {
      motion.mag.push(...msg.mag);
      for (const v of msg.mag) for (let k = 0; k < 3; k++) {
        motion.magMin[k] = Math.min(motion.magMin[k], v[k]);
        motion.magMax[k] = Math.max(motion.magMax[k], v[k]);
      }
    }
    if (motion.gyro.length > cap) motion.gyro.splice(0, motion.gyro.length - cap);
    if (motion.mag.length > cap) motion.mag.splice(0, motion.mag.length - cap);
    if ((msg.gyro?.length || msg.mag?.length) && !motion.dirty) {
      motion.dirty = true;
      requestAnimationFrame(drawMotion);
    }
  } else if (msg.type === "gps") {
    motion.gps = msg;
    renderGps();
  }
});

hooks.status.push((s) => {
  if (s.raw?.streaming && Array.isArray(s.raw.sensors)) {
    for (const box of document.querySelectorAll("#sensor-picks input")) box.checked = s.raw.sensors.includes(box.value);
  }
  if (s.gps && !motion.gps) {
    motion.gps = s.gps;
    renderGps();
  }
});

// Changing the ticks while streaming restarts the stream with the new set.
$("sensor-picks").addEventListener("change", async () => {
  if (!state.status?.raw?.streaming) return;
  const sensors = window.selectedSensors();
  if (!sensors.length) return;
  try {
    await api("/api/raw/start", "POST", { sensors });
    log("info", `Raw sensors now: ${sensors.join(", ")}`);
  } catch (e) {
    log("error", `Raw sensors: ${e.message}`);
  }
});

// ---------- Gyroscope + compass ----------

function drawMotion() {
  motion.dirty = false;
  $("gyro-empty").hidden = motion.gyro.length > 0;
  const { ctx, w, h } = sizeCanvas($("gyro-canvas"));
  const recent = motion.gyro.slice(-WAVE_SECONDS * RATE);
  if (recent.length > 1) {
    const lim = Math.max(200, ...recent.flat().map(Math.abs)) * 1.1;
    ["--ink", "--pulse", "--sleep-light"].forEach((c, axis) =>
      plotLine(ctx, recent.map((v) => v[axis]), w, h, -lim, lim, cssVar(c), 1.5, 4, 4));
  }
  drawCompass();
}

function headingDeg() {
  const m = motion.mag[motion.mag.length - 1];
  const a = wave.accel[wave.accel.length - 1];
  if (!m) return null;
  const centre = [0, 1, 2].map((k) => (motion.magMin[k] + motion.magMax[k]) / 2);
  const [mx, my, mz] = m.map((v, k) => v - centre[k]);
  let xh = mx, yh = my;
  if (a) {
    // Tilt compensation with the accelerometer (standard e-compass equations).
    const roll = Math.atan2(a[1], a[2]);
    const pitch = Math.atan2(-a[0], Math.hypot(a[1], a[2]));
    xh = mx * Math.cos(pitch) + mz * Math.sin(pitch);
    yh = mx * Math.sin(roll) * Math.sin(pitch) + my * Math.cos(roll) - mz * Math.sin(roll) * Math.cos(pitch);
  }
  return (Math.atan2(-yh, xh) * 180 / Math.PI + 360) % 360;
}

function calibration() {
  const spans = [0, 1, 2].map((k) => motion.magMax[k] - motion.magMin[k]);
  return Math.min(1, Math.min(spans[0], spans[1]) / CAL_SPAN);
}

function drawCompass() {
  const svg = $("compass-svg");
  const heading = headingDeg();
  const parts = [`<circle class="compass-ring" cx="100" cy="100" r="88"/>`];
  for (let d = 0; d < 360; d += 30) {
    const r1 = d % 90 ? 82 : 76, rad = (d * Math.PI) / 180;
    parts.push(`<line class="compass-tick" x1="${100 + 88 * Math.sin(rad)}" y1="${100 - 88 * Math.cos(rad)}" x2="${100 + r1 * Math.sin(rad)}" y2="${100 - r1 * Math.cos(rad)}"/>`);
  }
  [["N", 0], ["E", 90], ["S", 180], ["W", 270]].forEach(([l, d]) => {
    const rad = (d * Math.PI) / 180;
    parts.push(`<text class="compass-label${l === "N" ? " compass-label-n" : ""}" x="${100 + 64 * Math.sin(rad)}" y="${104 - 64 * Math.cos(rad)}">${l}</text>`);
  });
  if (heading != null) {
    parts.push(`<g transform="rotate(${heading.toFixed(1)} 100 100)">
      <polygon class="compass-needle" points="100,26 108,100 92,100"/>
      <polygon class="compass-tail" points="100,174 108,100 92,100"/></g>`);
  }
  svg.innerHTML = parts.join("");
  $("compass-heading").textContent = heading == null ? "–" : `${Math.round(heading)}°`;
  const cal = calibration();
  $("compass-note").textContent = heading == null
    ? "Tick Compass and start raw sensors."
    : cal < 1
      ? `Turn the watch slowly in a figure-eight to calibrate (${Math.round(cal * 100)}%). Until then the heading drifts.`
      : "Calibrated for this session. Direction not yet checked against a real compass.";
}

// ---------- GPS ----------

function renderGps() {
  const g = motion.gps;
  if (!g) return;
  const sats = g.satellites || [];
  const heard = sats.filter((s) => s.snr).length;
  $("g-fix").textContent = g.fix ? (g.quality >= 2 ? "Fix (differential)" : "Fix") : "Searching";
  $("g-sats").textContent = `${g.sats_used || 0} used, ${heard} heard, ${sats.length} in view`;
  if (g.fix && g.lat != null) {
    const lat = g.lat.toFixed(5), lon = g.lon.toFixed(5);
    $("g-pos").innerHTML = `<a href="https://www.openstreetmap.org/?mlat=${lat}&mlon=${lon}#map=17/${lat}/${lon}" target="_blank" rel="noopener">${lat}, ${lon}</a>`;
  } else {
    $("g-pos").textContent = "No fix yet";
  }
  $("g-alt").textContent = g.fix && g.alt_m != null ? `${Math.round(g.alt_m)} m` : "–";
  $("g-speed").textContent = g.speed_mps != null ? `${(g.speed_mps * 3.6).toFixed(1)} km/h` : "–";
  $("g-hdop").textContent = g.hdop != null ? `${g.hdop}${g.hdop <= 2 ? ", good" : g.hdop <= 5 ? ", fair" : ", poor"}` : "–";
  $("g-time").textContent = g.utc ? new Date(g.utc).toLocaleTimeString([], { hour12: false }) : "–";
  drawSky(sats);
}

function drawSky(sats) {
  const parts = [];
  for (const el of [0, 30, 60]) parts.push(`<circle class="sky-ring" cx="100" cy="100" r="${((90 - el) / 90) * 86}"/>`);
  parts.push(`<line class="sky-ring" x1="14" y1="100" x2="186" y2="100"/><line class="sky-ring" x1="100" y1="14" x2="100" y2="186"/>`);
  [["N", 100, 10], ["E", 194, 103], ["S", 100, 198], ["W", 6, 103]].forEach(([l, x, y]) =>
    parts.push(`<text class="compass-label${l === "N" ? " compass-label-n" : ""}" x="${x}" y="${y}">${l}</text>`));
  for (const s of sats) {
    if (s.elev == null || s.az == null) continue;
    const r = ((90 - s.elev) / 90) * 86, rad = (s.az * Math.PI) / 180;
    const x = 100 + r * Math.sin(rad), y = 100 - r * Math.cos(rad);
    const q = !s.snr ? "none" : s.snr >= 25 ? "good" : "weak";
    parts.push(`<circle class="sky-sat" data-snr="${q}" cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="5"><title>${esc(s.system)} ${esc(s.id)}: ${s.snr ? `${s.snr} dB` : "no signal"}</title></circle>`);
    parts.push(`<text class="sky-label" x="${x.toFixed(1)}" y="${(y - 8).toFixed(1)}">${esc(s.id)}</text>`);
  }
  $("sky-svg").innerHTML = parts.join("");
}

drawCompass();
drawSky([]);
new ResizeObserver(() => drawMotion()).observe($("gyro-canvas").parentElement);

// ---------- Demo data ----------

if (DEMO) {
  let n = 0;
  setInterval(() => {
    const gyro = [], mag = [];
    for (let i = 0; i < 5; i++, n++) {
      const t = n / RATE;
      gyro.push([Math.round(300 * Math.sin(t * 1.7)), Math.round(180 * Math.sin(t * 0.9 + 1)), Math.round(90 * Math.cos(t * 2.3))]);
      const turn = t * 0.6;
      mag.push([Math.round(1500 * Math.cos(turn)), Math.round(1500 * Math.sin(turn)), -860]);
    }
    handleMessage({ type: "raw", ts: Date.now() / 1000, ppg: [], accel: [], gyro, mag });
  }, 200);
  const sats = [["05", 62, 40, 41], ["12", 28, 300, 33], ["18", 15, 120, 0], ["24", 45, 210, 22], ["29", 70, 160, 38], ["65", 35, 80, 19], ["72", 10, 250, 0]]
    .map(([id, elev, az, snr], i) => ({ id, system: i > 4 ? "GLONASS" : "GPS", elev, az, snr: snr || null }));
  setTimeout(() => handleMessage({ type: "gps", fix: true, quality: 1, lat: 51.50135, lon: -0.14189, alt_m: 24, speed_mps: 1.3,
    course: 90, hdop: 1.4, sats_used: 5, utc: new Date().toISOString(), satellites: sats }), 600);
}
