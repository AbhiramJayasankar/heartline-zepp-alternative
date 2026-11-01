"""Write the reviewable comparison table from the most recent passing JUnit run."""
import json
import pathlib
import re
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
reports = sorted((ROOT / "build/test-results/test").glob("TEST-*.xml"))
assert reports, "Run :core:analytics:test first"
tests = failures = skipped = 0
session_lines, daily_lines, diagnostics = [], [], []
for report in reports:
    root = ET.parse(report).getroot()
    tests += int(root.attrib["tests"])
    failures += int(root.attrib["failures"]) + int(root.attrib["errors"])
    skipped += int(root.attrib["skipped"])
    for line in (root.findtext("system-out") or "").splitlines():
        if line.startswith("COMPARISON "):
            session_lines.append(line.removeprefix("COMPARISON ").split(" | "))
        elif line.startswith("DAILY "):
            daily_lines.append(line.removeprefix("DAILY ").split(" | "))
        elif line.startswith("DIAGNOSTIC "):
            diagnostics.append(line.removeprefix("DIAGNOSTIC "))
assert failures == 0 and skipped == 0, f"{failures} failed, {skipped} skipped"
assert len(session_lines) == 10 and len(daily_lines) == 5, "Run the complete test suite (no --tests filter)"
lines = ["# Python/Kotlin golden comparison", "", f"{tests} JUnit tests passed; no failures or skips.", "",
         "The paired values below are **Python / Kotlin**. pNN50 is in percentage points; HR is bpm; "
         "RMSSD and SDNN are milliseconds; respiration is breaths/minute.", "",
         "| Recording | HR | RMSSD | SDNN | pNN50 | Beats | SQI | Rhythm | Ectopic | Respiration |",
         "|---|---:|---:|---:|---:|---:|---:|---|---:|---:|"]
for row in session_lines:
    values = [row[0]] + [value.split(" ", 1)[1] for value in row[1:]]
    lines.append("| " + " | ".join(values) + " |")
lines += ["", "Session tests also compare the complete response tree, including notes, feature flags, RR values, "
          "preview samples, beat usability, motion, frequency-domain HRV and respiration components.", "",
          "| Daily golden | Numeric fields | Maximum absolute difference | Strings/nulls |",
          "|---|---:|---:|---|"]
for name, count, delta, equality in daily_lines:
    lines.append(f"| {name.strip()} | {count.split()[0]} | {delta.split()[-1]} | Equal |")
lines += ["", "## Constant-input diagnostic", "",
          "The additional flat, nonzero input has **bit-for-bit equal filtered samples**. NumPy's default "
          "unstable argsort chooses a different order for exactly equal-height peaks. Kotlin chooses "
          "rightmost first. This changes spurious detections in filter roundoff (~1e-10 raw counts). "
          "It is retained as a diagnostic, outside the ten session parity goldens, and is not claimed "
          "to meet their session-level tolerances.", ""]
flat = json.loads((ROOT / "src/test/resources/goldens/flat.json").read_text())
lines.append(f"Python constant input: beats={flat['beats']}, HR={flat['hr_bpm']}, "
             f"RMSSD={flat['hrv']['rmssd_ms']}, rhythm={flat['rhythm']['status']}.")
lines += ["", *diagnostics, "", "## Build scope", "",
          "The scaffold's unmodified root settings reference a missing `:core:data` directory. "
          "Tests were run with `-I core/analytics/tools/analytics-only.init.gradle`, which redirects "
          "other project descriptors into empty directories under analytics/build. The plain root "
          "invocation remains blocked by that pre-existing scaffold issue.", ""]
(ROOT / "COMPARISON.md").write_text("\n".join(lines), encoding="utf-8")
print(f"Wrote {ROOT / 'COMPARISON.md'} ({tests} passing tests)")
