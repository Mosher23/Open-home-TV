#!/bin/sh
# Read-only diagnostics; requires an explicit serial to avoid targeting a physical TV accidentally.
set -eu
if [ "$#" -ne 2 ]; then
    echo 'Usage: ./scripts/capture-tv-diagnostics.sh SERIAL OUTPUT_DIRECTORY' >&2
    exit 1
fi
serial="$1"
out="$2"
mkdir -p "$out"
adb -s "$serial" shell dumpsys meminfo dev.hatv.launcher > "$out/memory.txt"
adb -s "$serial" shell dumpsys gfxinfo dev.hatv.launcher > "$out/graphics.txt"
adb -s "$serial" logcat -d -s AndroidRuntime:E > "$out/crashes.txt"
adb -s "$serial" exec-out screencap -p > "$out/screen.png"
echo "Diagnostics saved in $out. Review logs before sharing."
