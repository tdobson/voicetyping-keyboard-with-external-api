#!/usr/bin/env bash
# Usage: scripts/emulator.sh start|stop|status   (AVD pixel_api35, always addressed as emulator-5554)
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh
case "${1:-status}" in
  start)
    adb -s "$EMU" get-state 2>/dev/null | grep -qx device && { echo "already running"; exit 0; }
    # shellcheck disable=SC2086 # EMU_EXTRA_ARGS is intentionally word-split
    nohup "$ANDROID_HOME/emulator/emulator" -avd pixel_api35 -no-window -no-snapshot-save -no-boot-anim \
      -gpu swiftshader_indirect ${EMU_EXTRA_ARGS:-} >/tmp/groqvoice-emulator.log 2>&1 &
    for _ in $(seq 1 150); do
      [ "$(adb -s "$EMU" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break; sleep 2
    done
    [ "$(adb -s "$EMU" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] || { echo "boot timeout; see /tmp/groqvoice-emulator.log"; exit 1; }
    for s in window_animation_scale transition_animation_scale animator_duration_scale; do
      adb -s "$EMU" shell settings put global "$s" 0; done
    echo "booted" ;;
  stop) adb -s "$EMU" emu kill || true ;;
  status) adb -s "$EMU" get-state 2>/dev/null || echo "not attached" ;;
  *) echo "usage: $0 start|stop|status" >&2; exit 2 ;;
esac
