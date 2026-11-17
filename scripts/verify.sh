#!/usr/bin/env bash
# Runs every test layer, cheapest first, and stops at the first failure.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh
start=$(date +%s); step() { printf '\n== [%ss] %s ==\n' "$(( $(date +%s) - start ))" "$1"; }
step "0 guards";                 scripts/guards.sh
step "1 compile + lint";         ./gradlew --console=plain assembleDebug compileDebugUnitTestKotlin compileDebugAndroidTestKotlin lintDebug
step "2 layers 1-2 (core JVM)";  ./gradlew --console=plain testDebugUnitTest --tests 'net.tdobson.groqvoice.core.*'
step "3 layers 1-3 (all JVM)";   ./gradlew --console=plain testDebugUnitTest
step "4 signed release"
if grep -q '^GROQVOICE_STORE_FILE=' ~/.gradle/gradle.properties 2>/dev/null; then
  ./gradlew --console=plain assembleRelease
  "$ANDROID_HOME/build-tools/36.1.0/apksigner" verify app/build/outputs/apk/release/app-release.apk
  REL=ran
else echo "SKIP: no GROQVOICE_* signing properties"; REL=skipped; fi
step "5 layer 4 (instrumented)"
if adb -s "$EMU" get-state 2>/dev/null | grep -qx device; then scripts/instrumented.sh; L4=ran; else echo "SKIP: $EMU not attached"; L4=skipped; fi
echo "verify.sh: PASSED (guards, lint, layers 1-3 ran; release $REL; layer 4 $L4) in $(( $(date +%s) - start ))s"
