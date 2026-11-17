#!/usr/bin/env bash
# Layer 0 static guards. Usage: scripts/guards.sh [--staged]. Fails on the first violation.
set -euo pipefail
cd "$(dirname "$0")/.."
KP="gsk"; PREFIX="${KP}_"   # built at run time so this file never contains the literal prefix
fail() { echo "GUARD FAILED: $*" >&2; exit 1; }
# 1. no key prefix in staged diff (when --staged) or in tracked files
if [ "${1:-}" = "--staged" ]; then
  n=$(git diff --cached | grep -c "$PREFIX" || true); [ "$n" = 0 ] || fail "staged diff contains the Groq key prefix ($n lines)"
  git diff --cached --name-only --diff-filter=d | grep -E '(^|/)(\.local-test/|local\.properties$|\.env$)|\.(jks|keystore|ogg|apk)$' \
    && fail "forbidden file staged" || true
fi
# Tracked files: match real key shapes only. .claude/workflows/build-groq-voice.js legitimately mentions the bare
# prefix in its instructions (already committed), so a bare-prefix scan of tracked files would always fail.
git grep -nE "${PREFIX}[A-Za-z0-9]{20,}" -- . >/dev/null && fail "tracked file contains a Groq-key-shaped string" || true
# 2. core is pure Kotlin
# (Capture output rather than trusting grep's exit code: a missing directory makes grep exit 2 even on a match.)
hits=$(grep -rEn '^import (android|androidx)\.' app/src/main/java/net/tdobson/groqvoice/core app/src/test/java/net/tdobson/groqvoice/core 2>/dev/null || true)
[ -z "$hits" ] || { echo "$hits" >&2; fail "core/ must not import android or androidx"; }
# 3. logging only through Logx
hits=$(grep -rln 'android\.util\.Log' app/src/main/java 2>/dev/null | grep -v '/util/Logx.kt$' || true)
[ -z "$hits" ] || { echo "$hits" >&2; fail "use Logx, not android.util.Log"; }
# 4. no key material in BuildConfig
grep -rEn 'buildConfigField\(.*KEY' app/build.gradle.kts && fail "no keys in BuildConfig" || true
echo "guards: ok"
