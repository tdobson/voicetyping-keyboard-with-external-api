#!/usr/bin/env bash
# The ONLY way to run layer 4. Installs and runs on emulator-5554 only (never bare connected*AndroidTest).
# Usage: scripts/instrumented.sh [fully.qualified.TestClass]
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh
adb -s "$EMU" get-state 2>/dev/null | grep -qx device || { echo "instrumented.sh: $EMU not attached" >&2; exit 1; }
./gradlew --console=plain assembleDebug assembleDebugAndroidTest
adb -s "$EMU" install -r -g app/build/outputs/apk/debug/app-debug.apk
adb -s "$EMU" install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
out="$(adb -s "$EMU" shell am instrument -w -r ${1:+-e class "$1"} net.tdobson.groqvoice.test/androidx.test.runner.AndroidJUnitRunner)"
echo "$out" | grep -E 'INSTRUMENTATION_STATUS: (class|test)=|^OK \(|FAILURES!!!|Tests run:|shortMsg' || true
echo "$out" | grep -qE 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' && { echo "$out" | tail -60; exit 1; }
# Status -4 is an assumption failure: "OK" would hide it, but on the emulator a skipped test means a broken guard.
echo "$out" | grep -q 'INSTRUMENTATION_STATUS_CODE: -4' && { echo "$out" | grep -B8 'STATUS_CODE: -4' | tail -40; echo "tests were skipped by an assumption" >&2; exit 1; }
echo "$out" | grep -qE '^OK \([1-9][0-9]* tests?\)' || { echo "$out" | tail -60; echo "no passing tests reported" >&2; exit 1; }
