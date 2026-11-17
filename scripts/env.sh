# shellcheck shell=bash
# Source me: sets JAVA_HOME (a full JDK with javac), ANDROID_HOME and the emulator serial.
export ANDROID_HOME="${ANDROID_HOME:-/home/tdobson/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
for c in "${JAVA_HOME:-}" /usr/lib/jvm/java-21-openjdk-amd64 /usr/lib/jvm/temurin-21-jdk-amd64; do
  if [ -n "$c" ] && [ -x "$c/bin/javac" ]; then export JAVA_HOME="$c"; break; fi
done
[ -x "${JAVA_HOME:-}/bin/javac" ] || { echo "env.sh: no JDK 21 with javac found" >&2; return 1 2>/dev/null || exit 1; }
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
export EMU=emulator-5554
export ANDROID_SERIAL="$EMU"   # safety net: Gradle connected tasks may only touch the emulator
