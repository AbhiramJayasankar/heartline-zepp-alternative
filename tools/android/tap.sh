#!/bin/bash
# tap.sh "<text prefix>" : tap the first element whose text or content description starts with it.
# Phone from ANDROID_SERIAL (or the only one connected). Layouts shift, so run it right before each tap.
export MSYS_NO_PATHCONV=1
A="adb ${ANDROID_SERIAL:+-s $ANDROID_SERIAL}"
$A shell uiautomator dump /data/local/tmp/ui.xml >/dev/null
b=$($A shell cat /data/local/tmp/ui.xml | tr '>' '\n' | grep -E "(text|content-desc)=\"$1" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
$A shell rm -f /data/local/tmp/ui.xml
set -- $b
if [ -z "$4" ]; then echo "not found"; exit 1; fi
x=$(( ($1 + $3) / 2 )); y=$(( ($2 + $4) / 2 ))
$A shell input tap $x $y; echo "tapped $x $y"
