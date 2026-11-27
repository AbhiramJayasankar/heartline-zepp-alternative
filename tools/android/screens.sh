#!/bin/bash
# screens.sh <name> <n> [out-dir] : n screenshots scrolling down the current page, stitched side by side into
# <out-dir>/<name>.png (default: the system temp folder; screenshots of real data stay out of the repository).
# Needs Python with Pillow. Phone from ANDROID_SERIAL (or the only one connected).
A="adb ${ANDROID_SERIAL:+-s $ANDROID_SERIAL}"
out="${3:-${TMPDIR:-${TEMP:-/tmp}}}"
size=$($A shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1); w=${size%x*}; h=${size#*x}
for i in $(seq 1 "$2"); do
  $A exec-out screencap -p > "$out/$1-$i.png"
  $A shell input swipe $((w / 2)) $((h * 3 / 4)) $((w / 2)) $((h / 4)) 600; sleep 1.5
done
python - "$out" "$1" "$2" <<'PY'
import sys
from PIL import Image
out, name, n = sys.argv[1], sys.argv[2], int(sys.argv[3])
ims = [Image.open(f"{out}/{name}-{i}.png") for i in range(1, n + 1)]
w = 360; h = round(ims[0].height * w / ims[0].width)
o = Image.new("RGB", (w * n, h), "white")
for k, im in enumerate(ims): o.paste(im.resize((w, h)), (w * k, 0))
o.save(f"{out}/{name}.png")
print(f"{out}/{name}.png")
PY
