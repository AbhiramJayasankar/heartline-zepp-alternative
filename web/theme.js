"use strict";
// Colour theme: Auto follows the system, Light and Dark pin it. Remembered in this browser only.

(() => {
  const ORDER = ["auto", "light", "dark"];
  const LABEL = { auto: "Auto", light: "Light", dark: "Dark" };
  const read = () => { try { return localStorage.getItem("theme") || "auto"; } catch { return "auto"; } };
  const write = (v) => { try { localStorage.setItem("theme", v); } catch { /* private window */ } };

  function apply(mode) {
    if (mode === "auto") document.documentElement.removeAttribute("data-theme");
    else document.documentElement.dataset.theme = mode;
    const btn = document.getElementById("theme-btn");
    if (btn) {
      btn.textContent = LABEL[mode];
      btn.setAttribute("aria-label", mode === "auto" ? "Colour theme: follows your system" : `Colour theme: ${mode}`);
    }
  }

  // Canvas and SVG charts read their colours when they draw, so redraw them after a switch.
  function redraw() {
    const fns = [
      typeof renderChart === "function" && renderChart,
      typeof renderActivity === "function" && renderActivity,
      typeof drawWave === "function" && drawWave,
      typeof renderTrends === "function" && renderTrends,
      typeof renderSession === "function" && renderSession,
      typeof drawMotion === "function" && drawMotion,
      typeof renderGps === "function" && renderGps,
    ];
    for (const fn of fns) if (fn) { try { fn(); } catch { /* not ready yet */ } }
  }

  apply(read());
  document.addEventListener("DOMContentLoaded", () => {
    apply(read());
    document.getElementById("theme-btn").addEventListener("click", () => {
      const next = ORDER[(ORDER.indexOf(read()) + 1) % ORDER.length];
      write(next);
      apply(next);
      redraw();
    });
    matchMedia("(prefers-color-scheme: dark)").addEventListener("change", () => { if (read() === "auto") redraw(); });
  });
})();
