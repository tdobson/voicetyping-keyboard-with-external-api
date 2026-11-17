# Groq Voice keyboard v1 Implementation Plan

> **For agentic workers:** This plan is executed by the workflow `.claude/workflows/build-groq-voice.js`,
> one agent per section, strictly in order: (1) Scaffold, (2) Core logic, (3) Android layer,
> (4) Proof on emulator. All work happens on branch `feat/v1-keyboard` in
> `/home/tdobson/dev/android-groq-keyboard` (no worktrees, no remote, never push).
> Within a section, do the tasks in order. Every task is test-first (superpowers:test-driven-development):
> write the test, run it and watch it fail for the right reason, write the minimal code, run it green, commit.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A sideloadable Android keyboard ("Groq Voice", `net.tdobson.groqvoice`) that records speech with
Messenger-style mic gestures, transcribes it with Groq Whisper exactly like `~/dev/transcribeMe/voiceTyping.js`,
and types the result into the focused field, without ever losing a recording on a bad mobile network.

**Architecture:** A single Gradle `:app` module. All decision-making logic lives in the package
`net.tdobson.groqvoice.core` as plain Kotlin with no `android.*` or `androidx.*` imports (enforced by a guard
script), so it runs as fast JVM tests. Thin Android adapters (recorder, keystore, connectivity, IME service,
settings activity) implement the core's interfaces and are tested with Robolectric or on the emulator.

**Tech stack:** Kotlin 2.4.21 (AGP built-in Kotlin), AGP 9.5.1, Gradle 9.9.1 (wrapper), JDK 21, Android Views,
OkHttp 5.5.1, kotlinx-coroutines 1.11.0, AndroidX core-ktx 1.19.1, appcompat 1.8.0, lifecycle-runtime-ktx 2.11.0.
Tests: JUnit 4.13.2, kotlinx-coroutines-test 1.11.0, OkHttp `mockwebserver3` 5.5.1, Robolectric 4.18,
AndroidX test runner/rules 1.7.0, ext-junit 1.3.0, UiAutomator 2.4.1.
(All versions checked on maven.google.com, Maven Central and services.gradle.org on 2026-11-17.)

**Source of truth:** `docs/superpowers/specs/2026-11-16-groq-voice-keyboard-design.md`. This plan refines it;
refinements are listed in "Spec refinements" below so reviewers can check them.

---

## 0. Conventions that apply to every section

### 0.1 Environment (important: the JAVA_HOME in the task context is a JRE)

`/usr/lib/jvm/java-21-openjdk-amd64` has only `java`, `keytool`, `jpackage`, `rmiregistry` (Debian
`openjdk-21-jre` packages; there is no `javac`). AGP needs a full JDK. The full JDK 21 on this machine is
`/usr/lib/jvm/temurin-21-jdk-amd64` (javac 21.0.13). `scripts/env.sh` (Task 1.3) picks the first candidate that
has `bin/javac`, so it keeps working if `openjdk-21-jdk` is installed later. Do not install system packages.

```
ANDROID_HOME=/home/tdobson/Android/Sdk   (platforms/android-36, build-tools/36.1.0, emulator, platform-tools, cmdline-tools/latest)
AVD: pixel_api35 (system-images/android-35/google_apis/x86_64)
```

### 0.2 adb safety (Tim's Messenger phone shares the adb server)

- Every adb call is `adb -s emulator-5554 ...`. Never `adb kill-server`, `start-server`, `connect`, `disconnect`,
  never an adb command without `-s`.
- Never run `./gradlew connected*AndroidTest` bare: Gradle installs on every attached device, which would include
  Tim's phone. Instrumented tests run only through `scripts/instrumented.sh`, which installs and runs with
  `adb -s emulator-5554`. As a second safety net `scripts/env.sh` exports `ANDROID_SERIAL=emulator-5554`.
- Every instrumented test class starts with `assumeTrue(EmulatorOnly.isEmulator())` so it cannot change IME
  settings or record audio on a real phone even if mis-launched.

### 0.3 Secrets

- The real key is read only from `/home/tdobson/dev/transcribeMe/.env` at run time, only in the Proof section.
  Unit and instrumented tests use fake keys such as `test-key-123`.
- **Never write the Groq key prefix literally in any new or changed tracked file**, including tests, scripts and
  docs: the pre-commit check greps the staged diff for it. (The already-committed
  `.claude/workflows/build-groq-voice.js` mentions it in its instructions. Do not edit that file.) Scripts build the pattern at run time:
  `KEY_PREFIX="gsk"; PATTERN="${KEY_PREFIX}_"`. Fake keys never start with that prefix, and no code
  validates the prefix.
- Before every commit, as well as the hook: `git diff --cached | grep -c "$(printf 'gsk')_"` must print `0`.
- Never put the key on a host command line (it would show in `ps`). Use stdin or file-descriptor tricks
  shown in Section 4.

### 0.4 Package and file layout

```
app/src/main/java/net/tdobson/groqvoice/
  core/                         PURE KOTLIN ONLY (guard: no android./androidx. imports)
    retry/RetryPolicy.kt
    retry/RetryAfter.kt
    net/TimeoutPolicy.kt
    net/TranscriptionApi.kt         (TranscriptionApi, TranscriptionRequest, TranscriptionResult, KeyCheckResult)
    net/FailureClassifier.kt        (FailureClass, FailureClassifier)
    net/GroqErrorMessage.kt
    net/GroqClient.kt               (OkHttp; the only network code)
    net/GroqReachabilityProbe.kt
    net/OkHttpAwait.kt              (suspend Call.await() with cancellation)
    net/ReachabilityLoop.kt
    text/TranscriptCleaner.kt
    gesture/MicGestureDetector.kt   (GestureConfig, GestureEvent, MicGestureDetector)
    keys/EnterKeyResolver.kt
    settings/SettingsRules.kt
    controller/Ports.kt             (interfaces the Android layer implements)
    controller/Status.kt            (Status, Cue)
    controller/StatusFormatter.kt
    controller/FeedbackPolicy.kt
    controller/TranscriptionController.kt
  storage/PendingStore.kt           (java.io.File only; layer 3)
  settings/AppSettings.kt           (SharedPreferences; layer 3 Robolectric)
  settings/SettingsActivity.kt
  security/ApiKeyStore.kt           (Android Keystore; layer 4)
  audio/Recorder.kt                 (MediaRecorder; layer 4)
  net/AndroidConnectivityWaiter.kt
  ime/VoiceKeyboardService.kt
  ime/KeyboardViewBinder.kt
  ime/ImeDelivery.kt
  ime/Feedback.kt
  util/Logx.kt                      (the only file allowed to import android.util.Log)
app/src/main/res/layout/keyboard.xml, activity_settings.xml
app/src/main/res/xml/method.xml, data_extraction_rules.xml
app/src/test/java/net/tdobson/groqvoice/core/...      layer 1 and 2
app/src/test/java/net/tdobson/groqvoice/storage/...   layer 3
app/src/test/java/net/tdobson/groqvoice/settings/...  layer 3
app/src/test/java/net/tdobson/groqvoice/ime/...       layer 3
app/src/androidTest/java/net/tdobson/groqvoice/...    layer 4
scripts/env.sh, guards.sh, verify.sh, instrumented.sh, emulator.sh, install-hooks.sh, git-hooks/pre-commit
```

### 0.5 Test layers (shift-left)

| Layer | What | Where / how | Run by |
|---|---|---|---|
| 0 | Static guards: no key prefix in staged diff or tracked files, core purity, no stray `android.util.Log`, no forbidden files staged | `scripts/guards.sh` (about 1 s) | hook, verify.sh |
| 1 | Pure JVM unit tests: RetryPolicy, RetryAfter, TimeoutPolicy, TranscriptCleaner, FailureClassifier, GroqErrorMessage, MicGestureDetector, EnterKeyResolver, SettingsRules, ReachabilityLoop, StatusFormatter, FeedbackPolicy, TranscriptionController with fakes | `app/src/test/.../core/` | hook, verify.sh |
| 2 | GroqClient and GroqReachabilityProbe against OkHttp MockWebServer (real sockets, localhost) | `app/src/test/.../core/net/` | hook, verify.sh |
| 3 | PendingStore (plain JVM, TemporaryFolder), AppSettings (Robolectric), ImeDelivery leading-space and identity logic (Robolectric) | `app/src/test/.../storage`, `settings`, `ime` | verify.sh |
| 4 | Instrumented on emulator: ApiKeyStore Keystore round-trip, Recorder writes valid Ogg/Opus, IME service starts and its keys work | `app/src/androidTest/` via `scripts/instrumented.sh` | verify.sh when emulator-5554 attached |
| 5 | Signed release APK proof on emulator: real audio through emulator mic, real Groq, text appears in "Try it here" | Section 4 | Prove phase |

Layers 1 and 2 are both under the `net.tdobson.groqvoice.core` package, so the pre-commit hook runs
`./gradlew testDebugUnitTest --tests 'net.tdobson.groqvoice.core.*'`.

Coroutine tests use `runTest` with the default `StandardTestDispatcher`; all waiting in core code is `delay()`
so virtual time makes 10-retry, 242-second schedules run in milliseconds.

### 0.6 Commands

```bash
cd /home/tdobson/dev/android-groq-keyboard && source scripts/env.sh   # after Task 1.3 exists
./gradlew testDebugUnitTest --tests 'net.tdobson.groqvoice.core.retry.RetryPolicyTest'   # one test class
./gradlew testDebugUnitTest --tests 'net.tdobson.groqvoice.core.*'                       # layers 1-2
./gradlew testDebugUnitTest                                                             # layers 1-3
scripts/verify.sh                                                                       # everything, fail fast
```

### 0.7 Spec refinements (made in this plan, reviewers please check)

1. **Recording directory.** The recorder writes to `recording/<millis>.ogg` and the file moves to
   `pending/` on stop. Otherwise the in-progress file would count as "unsent" in the chip. On service start,
   `PendingStore.recoverInterrupted()` moves any valid leftover (service killed mid-recording) into `pending/`,
   so nothing is lost. This refines data-flow step 1.
2. **First-failure connectivity wait.** As on the desktop, after attempt 1 fails with any retryable error the
   controller waits on `ConnectivityGate.awaitOnline()` instead of sleeping. After attempt n >= 2 it sleeps
   `min(1000 * 2^(n-1), 60000)` ms. Total sleep across a full 10-attempt failure is 242 s, the same as desktop.
3. **429 `retry-after`.** Honoured as given in seconds or HTTP-date, but capped at 60 s (the backoff cap), so a
   "retry in 1 hour" answer cannot pin the keyboard for an hour. After 10 attempts the audio stays in `pending/`.
4. **Requests while busy.** Transcriptions are serialised by a `Mutex`. A new recording may be made while an
   earlier one is still sending; each job captured its own target field at stop time and is delivered in order.
5. **"✕" cancel** cancels every in-flight and queued transcription; their audio stays in `pending/`.
6. **ACTION_CANCEL** (system steals the touch) while pressed is treated like lifting the finger: Release if held
   >= 500 ms, else TooShort. Losing speech is worse than an extra transcription.
7. **Lock vs cancel on the same move.** The dominant axis wins: if `|dx| > |dy|`, only cancel is checked,
   otherwise only lock.
8. **Missing key or mic permission.** The press is refused before recording starts, with status
   "Add your Groq API key in Groq Voice settings" or "Allow the microphone in Groq Voice settings". Tapping the
   status opens SettingsActivity.
9. **Keyboard hidden while recording** (`onFinishInputView`) stops the recording and transcribes it. The field has
   gone, so the text goes to the clipboard plus ↺. Nothing is discarded.
10. **Settings additions:** "Try it here" multi-line field (agreed with Tim), and "Unsent recordings: N, Delete",
    so an unsendable file (Groq 400) can be cleared.
11. **Audio bitrate** 24 kbps Opus, so 60 minutes is about 10.8 MB, under Groq's 25 MB upload limit.
12. **Timeout arithmetic:** `20 KB/s` means 20 * 1024 bytes per second. OkHttp read and write inactivity
    timeouts are 30 s each.
13. **Backups:** `allowBackup="false"` and `dataExtractionRules` excluding everything (Android 12+ device
    transfer ignores `allowBackup`).

---

## Section 1: Scaffold

Outcome: an empty but complete Gradle project that builds debug and signed release APKs, with every test layer
proven by one placeholder test, plus `scripts/verify.sh` and the tracked pre-commit hook.

### Task 1.1: Gradle wrapper bootstrap

**Files:** Create `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`.

- [ ] **Step 1: Download and verify Gradle 9.9.1** (AGP 9.5 needs Gradle 9.6.0 or later.) Re-check
  `curl -s https://services.gradle.org/versions/current` first. If a newer stable 9.x exists, use it and its checksum.

```bash
mkdir -p /tmp/gradle-bootstrap && cd /tmp/gradle-bootstrap
curl -sSLO https://services.gradle.org/distributions/gradle-9.9.1-bin.zip
curl -sSL https://services.gradle.org/distributions/gradle-9.9.1-bin.zip.sha256 -o expected.sha256
echo "$(cat expected.sha256)  gradle-9.9.1-bin.zip" | sha256sum -c -    # must print OK
unzip -q gradle-9.9.1-bin.zip
```

- [ ] **Step 2: Generate the wrapper** (run from the repo; with an empty settings file so Gradle does not need AGP yet)

```bash
cd /home/tdobson/dev/android-groq-keyboard
export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
touch settings.gradle.kts
/tmp/gradle-bootstrap/gradle-9.9.1/bin/gradle wrapper --gradle-version 9.9.1 --distribution-type bin \
  --gradle-distribution-sha256-sum "$(cat /tmp/gradle-bootstrap/expected.sha256)"
./gradlew --version    # Gradle 9.9.1, JVM 21
```

- [ ] **Step 3: Commit** `chore: add Gradle 9.9.1 wrapper with pinned distribution checksum`

### Task 1.2: Project files, version catalog, signing

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`
- Create: `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/strings.xml` (`app_name` = "Groq Voice"), `app/src/main/res/values/themes.xml`,
  launcher icon (adaptive icon vector: white mic on Groq orange `#F55036`)
- Create: `app/src/main/res/xml/data_extraction_rules.xml`
- Create: `local.properties` (gitignored, `sdk.dir=/home/tdobson/Android/Sdk`)
- Modify: `.gitignore` (ensure `dist/`, `.local-test/`, `local.properties`, `*.jks`, `.env` present; they already are)

`gradle/libs.versions.toml`:

```toml
[versions]
agp = "9.5.1"
kotlin = "2.4.21"
coreKtx = "1.19.1"
appcompat = "1.8.0"
lifecycle = "2.11.0"
okhttp = "5.5.1"
coroutines = "1.11.0"
junit = "4.13.2"
robolectric = "4.18"
androidxTestCore = "1.7.0"
androidxTestRunner = "1.7.0"
androidxTestRules = "1.7.0"
androidxTestExtJunit = "1.3.0"
uiautomator = "2.4.1"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-appcompat = { module = "androidx.appcompat:appcompat", version.ref = "appcompat" }
androidx-lifecycle-runtime-ktx = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "lifecycle" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-mockwebserver3 = { module = "com.squareup.okhttp3:mockwebserver3", version.ref = "okhttp" }
coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
junit = { module = "junit:junit", version.ref = "junit" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
androidx-test-core = { module = "androidx.test:core", version.ref = "androidxTestCore" }
androidx-test-runner = { module = "androidx.test:runner", version.ref = "androidxTestRunner" }
androidx-test-rules = { module = "androidx.test:rules", version.ref = "androidxTestRules" }
androidx-test-ext-junit = { module = "androidx.test.ext:junit", version.ref = "androidxTestExtJunit" }
uiautomator = { module = "androidx.test.uiautomator:uiautomator", version.ref = "uiautomator" }
kotlin-gradle-plugin = { module = "org.jetbrains.kotlin:kotlin-gradle-plugin", version.ref = "kotlin" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
```

Root `build.gradle.kts`. AGP 9 has built-in Kotlin and must NOT apply `org.jetbrains.kotlin.android`. It
bundles KGP 2.2.10 by default, and a newer KGP on the buildscript classpath pins the Kotlin version:

```kotlin
buildscript {
    dependencies { classpath(libs.kotlin.gradle.plugin) }
}
plugins {
    alias(libs.plugins.android.application) apply false
}
```

If AGP rejects the classpath override, drop it, accept the bundled KGP, and record that in the commit message.
Do not opt out of built-in Kotlin.

`settings.gradle.kts`: `pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }`,
`dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }`,
`rootProject.name = "groq-voice"`, `include(":app")`.

`gradle.properties` (tracked, no secrets):

```properties
org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8
org.gradle.caching=true
org.gradle.parallel=true
org.gradle.configuration-cache=true
android.useAndroidX=true
kotlin.code.style=official
```

`app/build.gradle.kts` essentials:

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { alias(libs.plugins.android.application) }

val storeFile = providers.gradleProperty("GROQVOICE_STORE_FILE")
val hasReleaseSigning = storeFile.isPresent

android {
    namespace = "net.tdobson.groqvoice"
    compileSdk = 36
    defaultConfig {
        applicationId = "net.tdobson.groqvoice"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (hasReleaseSigning) create("release") {
            this.storeFile = file(storeFile.get())
            storePassword = providers.gradleProperty("GROQVOICE_STORE_PASSWORD").get()
            keyAlias = providers.gradleProperty("GROQVOICE_KEY_ALIAS").get()
            keyPassword = providers.gradleProperty("GROQVOICE_KEY_PASSWORD").get()
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { buildConfig = true; viewBinding = false }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests { isIncludeAndroidResources = true } }
    lint { abortOnError = true; checkReleaseBuilds = true }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.uiautomator)
    androidTestImplementation(libs.coroutines.test)
}
```

No `buildConfigField` may ever carry a key. The guard script fails on any `buildConfigField` whose name
contains `KEY`.

`app/proguard-rules.pro`:

```
# Strip verbose/debug/info logging from release builds (spec: no text in logcat in release).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
```

Manifest skeleton for this task (Section 3 adds the service, permissions and activity):

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:label="@string/app_name"
        android:icon="@mipmap/ic_launcher"
        android:supportsRtl="true"
        android:theme="@style/Theme.GroqVoice" />
</manifest>
```

`data_extraction_rules.xml`:

```xml
<data-extraction-rules>
    <cloud-backup><exclude domain="root" /><exclude domain="file" /><exclude domain="database" /><exclude domain="sharedpref" /><exclude domain="external" /></cloud-backup>
    <device-transfer><exclude domain="root" /><exclude domain="file" /><exclude domain="database" /><exclude domain="sharedpref" /><exclude domain="external" /></device-transfer>
</data-extraction-rules>
```

- [ ] **Step 1: Release keystore (reuse if present, never regenerate)**

```bash
if grep -q '^GROQVOICE_STORE_FILE=' ~/.gradle/gradle.properties 2>/dev/null; then
  echo "signing properties exist: reuse"
else
  test ! -e ~/.android-keystores/groq-voice-release.jks || { echo "keystore exists but no properties: STOP, ask Tim"; exit 1; }
  mkdir -p ~/.android-keystores ~/.gradle && chmod 700 ~/.android-keystores
  PW="$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | cut -c1-40)"
  # PKCS12 keystores use one password for store and key.
  GV_PW="$PW" keytool -genkeypair -keystore ~/.android-keystores/groq-voice-release.jks -storetype PKCS12 \
    -alias groqvoice -keyalg RSA -keysize 4096 -validity 10950 \
    -dname "CN=Groq Voice, O=Tim Dobson, C=GB" -storepass:env GV_PW -keypass:env GV_PW
  chmod 600 ~/.android-keystores/groq-voice-release.jks
  touch ~/.gradle/gradle.properties && chmod 600 ~/.gradle/gradle.properties
  { echo "GROQVOICE_STORE_FILE=/home/tdobson/.android-keystores/groq-voice-release.jks"
    echo "GROQVOICE_STORE_PASSWORD=$PW"; echo "GROQVOICE_KEY_ALIAS=groqvoice"; echo "GROQVOICE_KEY_PASSWORD=$PW"; } >> ~/.gradle/gradle.properties
  unset PW
fi
```

Never print the passwords. 10950 days is 30 years.

- [ ] **Step 2: Build both variants**

```bash
source scripts/env.sh 2>/dev/null || export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
./gradlew assembleDebug assembleRelease
$ANDROID_HOME/build-tools/36.1.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

Expected: BUILD SUCCESSFUL, and apksigner prints a signer certificate with `CN=Groq Voice`.

- [ ] **Step 3: Commit** `build: Android app skeleton (AGP 9.5.1, Kotlin 2.4.21, minSdk 29, target 36) with release signing from user gradle.properties`

### Task 1.3: Scripts, guards and git hook

**Files:**
- Create: `scripts/env.sh`, `scripts/guards.sh`, `scripts/verify.sh`, `scripts/instrumented.sh`,
  `scripts/emulator.sh`, `scripts/install-hooks.sh`, `scripts/git-hooks/pre-commit` (all `chmod +x`, tracked)

`scripts/env.sh` (sourced):

```bash
# shellcheck shell=bash
export ANDROID_HOME="${ANDROID_HOME:-/home/tdobson/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
for c in "${JAVA_HOME:-}" /usr/lib/jvm/java-21-openjdk-amd64 /usr/lib/jvm/temurin-21-jdk-amd64; do
  if [ -n "$c" ] && [ -x "$c/bin/javac" ]; then export JAVA_HOME="$c"; break; fi
done
[ -x "${JAVA_HOME:-}/bin/javac" ] || { echo "env.sh: no JDK 21 with javac found" >&2; return 1 2>/dev/null || exit 1; }
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
export EMU=emulator-5554
export ANDROID_SERIAL="$EMU"   # safety net: Gradle connected tasks may only touch the emulator
```

`scripts/guards.sh [--staged]` (layer 0; fail on first violation with a clear message):

```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
KP="gsk"; PREFIX="${KP}_"
fail() { echo "GUARD FAILED: $*" >&2; exit 1; }
# 1. no key prefix in staged diff (when --staged) or in tracked files
if [ "${1:-}" = "--staged" ]; then
  n=$(git diff --cached | grep -c "$PREFIX" || true); [ "$n" = 0 ] || fail "staged diff contains the Groq key prefix ($n lines)"
  git diff --cached --name-only | grep -E '(^|/)(\.local-test/|local\.properties$|\.env$)|\.(jks|keystore|ogg|apk)$' \
    && fail "forbidden file staged" || true
fi
# Tracked files: match real key shapes only. .claude/workflows/build-groq-voice.js legitimately mentions the bare
# prefix in its instructions (already committed), so a bare-prefix scan of tracked files would always fail.
git grep -nE "${PREFIX}[A-Za-z0-9]{20,}" -- . >/dev/null && fail "tracked file contains a Groq-key-shaped string" || true
# 2. core is pure Kotlin
grep -rEn '^import (android|androidx)\.' app/src/main/java/net/tdobson/groqvoice/core app/src/test/java/net/tdobson/groqvoice/core 2>/dev/null \
  && fail "core/ must not import android or androidx" || true
# 3. logging only through Logx
grep -rln 'android\.util\.Log' app/src/main/java | grep -v '/util/Logx.kt$' && fail "use Logx, not android.util.Log" || true
# 4. no key material in BuildConfig
grep -rEn 'buildConfigField\(.*KEY' app/build.gradle.kts && fail "no keys in BuildConfig" || true
echo "guards: ok"
```

`scripts/verify.sh` (fail fast, cheapest first; prints a final line saying which layers ran):

```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh
start=$(date +%s); step() { printf '\n== [%ss] %s ==\n' "$(( $(date +%s) - start ))" "$1"; }
step "0 guards";                 scripts/guards.sh
step "1 compile + lint";         ./gradlew --console=plain assembleDebug compileDebugUnitTestKotlin compileDebugAndroidTestKotlin lintDebug
step "2 layers 1-2 (core JVM)";  ./gradlew --console=plain testDebugUnitTest --tests 'net.tdobson.groqvoice.core.*'
step "3 layers 1-3 (all JVM)";   ./gradlew --console=plain testDebugUnitTest
step "4 signed release";
if grep -q '^GROQVOICE_STORE_FILE=' ~/.gradle/gradle.properties 2>/dev/null; then
  ./gradlew --console=plain assembleRelease
  "$ANDROID_HOME/build-tools/36.1.0/apksigner" verify app/build/outputs/apk/release/app-release.apk
  REL=ran
else echo "SKIP: no GROQVOICE_* signing properties"; REL=skipped; fi
step "5 layer 4 (instrumented)"
if adb -s "$EMU" get-state 2>/dev/null | grep -qx device; then scripts/instrumented.sh; L4=ran; else echo "SKIP: $EMU not attached"; L4=skipped; fi
echo "verify.sh: PASSED (guards, lint, layers 1-3 ran; release $REL; layer 4 $L4) in $(( $(date +%s) - start ))s"
```

`scripts/instrumented.sh` (the ONLY way to run layer 4):

```bash
#!/usr/bin/env bash
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
echo "$out" | grep -qE '^OK \([1-9][0-9]* tests?\)' || { echo "$out" | tail -60; echo "no passing tests reported" >&2; exit 1; }
```

`scripts/emulator.sh start|stop|status`:

```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh
case "${1:-status}" in
  start)
    adb -s "$EMU" get-state 2>/dev/null | grep -qx device && { echo "already running"; exit 0; }
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
esac
```

`scripts/git-hooks/pre-commit` (layers 0-2; target under 60 s on a warm Gradle daemon):

```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
source scripts/env.sh
t0=$(date +%s)
scripts/guards.sh --staged
./gradlew -q --console=plain testDebugUnitTest --tests 'net.tdobson.groqvoice.core.*'
echo "pre-commit: guards + layers 1-2 passed in $(( $(date +%s) - t0 ))s"
```

`scripts/install-hooks.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
git config core.hooksPath scripts/git-hooks
echo "hooks installed: core.hooksPath=scripts/git-hooks"
```

- [ ] **Step 1:** Write the scripts. Run `bash -n` on each. Run `shellcheck` too if it is installed.
- [ ] **Step 2:** Prove that the guards fail when they should (negative tests; never commit the scratch files).
  (a) Create `app/src/main/java/net/tdobson/groqvoice/core/Scratch.kt` containing `import android.util.Log`. Run
  `scripts/guards.sh` and expect a non-zero exit. Delete the file.
  (b) Run `printf '%s_fake\n' gsk > scratch.txt && git add scratch.txt`. Run `scripts/guards.sh --staged` and expect
  a non-zero exit. Then `git rm --cached -q scratch.txt && rm scratch.txt`.
  (c) Confirm that `git status` is clean apart from the new scripts.
- [ ] **Step 3:** `scripts/install-hooks.sh`.
- [ ] **Step 4: Commit** `build: verify.sh, guards, emulator helpers and tracked pre-commit hook`. The hook runs on this commit.

### Task 1.4: Prove every test layer with one placeholder test

**Files:**
- Create: `app/src/test/java/net/tdobson/groqvoice/core/HarnessTest.kt` (layer 1: `runTest { delay(60_000); assertEquals(60_000, currentTime) }` proves virtual time)
- Create: `app/src/test/java/net/tdobson/groqvoice/core/net/MockWebServerHarnessTest.kt` (layer 2: enqueue `MockResponse.Builder().code(200).body("hi").build()`, GET with a plain `OkHttpClient`, assert body "hi")
- Create: `app/src/test/java/net/tdobson/groqvoice/settings/RobolectricHarnessTest.kt` (layer 3: `@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])`, assert `ApplicationProvider.getApplicationContext<Context>().packageName == "net.tdobson.groqvoice"`)
- Create: `app/src/androidTest/java/net/tdobson/groqvoice/EmulatorOnly.kt`:

```kotlin
object EmulatorOnly {
    fun isEmulator(): Boolean =
        android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.PRODUCT.startsWith("sdk") ||
            android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.HARDWARE.contains("goldfish")
}
```

- Create: `app/src/androidTest/java/net/tdobson/groqvoice/HarnessInstrumentedTest.kt` (layer 4: `assumeTrue(EmulatorOnly.isEmulator())`, assert target context package name)

Use `@Config(sdk = [35])` on every Robolectric class, or `app/src/test/resources/robolectric.properties` with
`sdk=35`. That avoids depending on Robolectric's API 36 runtime jar; app logic does not differ between 35 and 36.

- [ ] **Step 1:** Write the four tests. Run `scripts/verify.sh` with no emulator. Expected: layers 1-3 PASS, layer 4 SKIP.
- [ ] **Step 2:** `scripts/emulator.sh start`, `scripts/instrumented.sh`, expected `OK (1 test)`, then `scripts/emulator.sh stop`.
- [ ] **Step 3: Commit** `test: placeholder test proving each layer of the harness`

**Scaffold section done when:** `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug` passes,
`scripts/verify.sh` passes (layer 4 skipped without emulator), `apksigner verify --print-certs` succeeds on the
release APK, and the hook ran on the last commit.

---

## Section 2: Core logic (pure Kotlin, JVM tests)

Everything here lives in `net.tdobson.groqvoice.core` and is tested by layers 1 and 2. Every unit is
test-first. Run the single test class red, implement, green, then run `./gradlew testDebugUnitTest --tests 'net.tdobson.groqvoice.core.*'`,
then commit (the hook re-runs it). Paths below are relative to `app/src/main/java/net/tdobson/groqvoice/` and
`app/src/test/java/net/tdobson/groqvoice/`.

### Task 2.1: RetryPolicy and RetryAfter

**Files:** `core/retry/RetryPolicy.kt`, `core/retry/RetryAfter.kt`; tests `core/retry/RetryPolicyTest.kt`, `core/retry/RetryAfterTest.kt`

Public interface:

```kotlin
package net.tdobson.groqvoice.core.retry

/** Desktop parity with voiceTyping.js withRetry(): MAX_RETRIES=10, INITIAL_BACKOFF_MS=1000, MAX_BACKOFF_MS=60000. */
class RetryPolicy(
    val maxAttempts: Int = 10,
    val initialBackoffMs: Long = 1_000,
    val maxBackoffMs: Long = 60_000,
    val maxRetryAfterMs: Long = 60_000,
) {
    /** min(initial * 2^(failedAttempt-1), max). failedAttempt is 1-based. */
    fun backoffAfter(failedAttempt: Int): Long
    fun hasAttemptsLeft(failedAttempt: Int): Boolean            // failedAttempt < maxAttempts
    fun waitForConnectivityAfter(failedAttempt: Int): Boolean   // failedAttempt == 1
    /** Sleep before the next attempt: retry-after (capped) if given; else 0 after attempt 1 (connectivity wait replaces it); else backoffAfter. */
    fun delayAfter(failedAttempt: Int, retryAfterMs: Long?): Long
}

object RetryAfter {
    /** Parses a Retry-After header: delta-seconds (integer or decimal) or an HTTP-date. Null if absent/invalid/negative. Past dates give 0. */
    fun parseMs(header: String?, nowMs: Long): Long?
}
```

Tests to write first (`RetryPolicyTest`):

```kotlin
class RetryPolicyTest {
    private val p = RetryPolicy()
    @Test fun `ten attempts like desktop`() = assertEquals(10, p.maxAttempts)
    @Test fun `backoff doubles from one second and caps at sixty`() {
        assertEquals(listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 32_000, 60_000, 60_000, 60_000),
            (1..9).map(p::backoffAfter))
    }
    @Test fun `attempts left until the tenth failure`() {
        assertTrue(p.hasAttemptsLeft(9)); assertFalse(p.hasAttemptsLeft(10))
    }
    @Test fun `connectivity wait only after first failure`() {
        assertTrue(p.waitForConnectivityAfter(1)); assertFalse(p.waitForConnectivityAfter(2))
    }
    @Test fun `no sleep after first failure because connectivity wait replaces it`() = assertEquals(0L, p.delayAfter(1, null))
    @Test fun `later failures sleep the backoff`() = assertEquals(4_000L, p.delayAfter(3, null))
    @Test fun `retry-after wins over backoff on any attempt`() {
        assertEquals(5_000L, p.delayAfter(1, 5_000)); assertEquals(5_000L, p.delayAfter(4, 5_000))
    }
    @Test fun `retry-after is capped at sixty seconds`() = assertEquals(60_000L, p.delayAfter(2, 3_600_000))
    @Test fun `full failure sleeps 242 seconds like desktop`() =
        assertEquals(242_000L, (1..9).sumOf { p.delayAfter(it, null) })
}
```

`RetryAfterTest` cases: `"5"` gives 5000; `"2.5"` gives 2500; `" 7 "` gives 7000; `"0"` gives 0; `"-1"` gives null; `"abc"` gives null;
`null` and `""` give null; an RFC 1123 date 10 s after `nowMs` gives 10000; a date in the past gives 0.

- [ ] Red, green, commit `feat(core): RetryPolicy and Retry-After parsing with desktop parity`

### Task 2.2: TimeoutPolicy

**Files:** `core/net/TimeoutPolicy.kt`; test `core/net/TimeoutPolicyTest.kt`

```kotlin
class TimeoutPolicy(
    val connectTimeoutMs: Long = 10_000,
    val baseCallTimeoutMs: Long = 30_000,
    val uploadBytesPerSecond: Long = 20L * 1024,
    val inactivityTimeoutMs: Long = 30_000,   // OkHttp read/write timeouts (inactivity, not total)
) {
    /** 30 s + fileBytes / 20 KiB/s, negative sizes treated as 0. */
    fun callTimeoutMs(fileBytes: Long): Long
}
```

Tests: `connectTimeoutMs == 10_000`; `callTimeoutMs(0) == 30_000`; `callTimeoutMs(20_480) == 31_000`;
`callTimeoutMs(1_048_576) == 81_200`; `callTimeoutMs(-5) == 30_000`; `callTimeoutMs(10_800_000)` (60 min at
24 kbps) is `557_343` and stays under 10 minutes; `inactivityTimeoutMs == 30_000`.

- [ ] Red, green, commit `feat(core): size-scaled TimeoutPolicy`

### Task 2.3: TranscriptCleaner

**Files:** `core/text/TranscriptCleaner.kt`; test `core/text/TranscriptCleanerTest.kt`

```kotlin
object TranscriptCleaner {
    /** Exactly voiceTyping.js: trimStart(), then replace(/(\s*Thank [Yy]ou\.\s*)+$/g, ''), then trimEnd(). */
    fun clean(raw: String): String
    /** Empty or whitespace after cleaning means Whisper heard nothing. */
    fun isNothingHeard(cleaned: String): Boolean
    /** Leading space needed when the character before the cursor exists and is not whitespace. */
    fun needsLeadingSpace(charBeforeCursor: Char?): Boolean
}
```

Implementation note: `private val THANK_YOU_TAIL = Regex("""(\s*Thank [Yy]ou\.\s*)+$""")`. Kotlin `$` without
MULTILINE matches only at end of input (or before a final line terminator, which the trailing `\s*` absorbs anyway),
like JS without the `m` flag.

Parity table test (parameterised with a list of pairs, one assertion per row with the row in the message):

| raw | clean |
|---|---|
| `" Hello world."` | `"Hello world."` |
| `"Hello world. Thank you."` | `"Hello world."` |
| `"Hello. Thank you. Thank you."` | `"Hello."` |
| `"Hello. Thank You."` | `"Hello."` |
| `"Hello. thank you."` | `"Hello. thank you."` (lowercase t is not stripped on desktop either) |
| `"Thank you for coming."` | unchanged |
| `"Thank you. Hello."` | unchanged (only trailing runs) |
| `"Hello Thank you"` | unchanged (no full stop) |
| `"Thanks."` | unchanged |
| `"Hello\nThank you.\n"` | `"Hello"` |
| `" Hi.  \n"` | `"Hi."` |
| `"Thank you."` | `""` |
| `"   "` | `""` |

Also: `isNothingHeard("")` true, `isNothingHeard("Hi")` false; `needsLeadingSpace(null)` false, `' '`, `'\n'`,
`'\t'` false, `'a'`, `'.'`, `'('` true.

- [ ] Red, green, commit `feat(core): TranscriptCleaner matching desktop cleanup`

### Task 2.4: Transcription API types, FailureClassifier, GroqErrorMessage

**Files:** `core/net/TranscriptionApi.kt`, `core/net/FailureClassifier.kt`, `core/net/GroqErrorMessage.kt`;
tests `core/net/FailureClassifierTest.kt`, `core/net/GroqErrorMessageTest.kt`

```kotlin
package net.tdobson.groqvoice.core.net

data class TranscriptionRequest(
    val model: String = "whisper-large-v3-turbo",
    val language: String = "en",
    val temperature: String = "0",
    val responseFormat: String = "text",
)

sealed interface TranscriptionResult {
    data class Success(val text: String) : TranscriptionResult
    data class HttpError(val code: Int, val message: String?, val retryAfterMs: Long?) : TranscriptionResult
    data class NetworkError(val cause: java.io.IOException) : TranscriptionResult
    data class UnexpectedError(val cause: Throwable) : TranscriptionResult
}

sealed interface KeyCheckResult { data object Valid : KeyCheckResult; data object Rejected : KeyCheckResult
    data object NoConnection : KeyCheckResult; data class Failed(val code: Int) : KeyCheckResult }

interface TranscriptionApi {
    suspend fun transcribe(audio: java.io.File, apiKey: String, request: TranscriptionRequest): TranscriptionResult
}

sealed interface FailureClass {
    data class Retryable(val retryAfterMs: Long?) : FailureClass
    data object AuthRejected : FailureClass
    data class Rejected(val message: String) : FailureClass
}

object FailureClassifier { fun classify(result: TranscriptionResult): FailureClass? }  // null for Success

object GroqErrorMessage {
    /** error.message from an OpenAI-style JSON body; else the first 200 chars of the body; null if blank. */
    fun extract(body: String?): String?
}
```

Classification table (spec "Retry classification"). Each row is a test:

| input | expected |
|---|---|
| `Success("x")` | `null` |
| `NetworkError(SocketTimeoutException())` | `Retryable(null)` |
| `NetworkError(UnknownHostException())` | `Retryable(null)` |
| `NetworkError(ConnectException())` | `Retryable(null)` |
| `NetworkError(InterruptedIOException("timeout"))` (OkHttp call timeout) | `Retryable(null)` |
| `HttpError(500/502/503/504, ...)` | `Retryable(null)` |
| `HttpError(429, msg, 3000)` | `Retryable(3000)` |
| `HttpError(429, msg, null)` | `Retryable(null)` |
| `HttpError(401, ...)`, `HttpError(403, ...)` | `AuthRejected` |
| `HttpError(400, "file too short", null)` | `Rejected("file too short")` |
| `HttpError(413, null, null)` | `Rejected("HTTP 413")` |
| `HttpError(404, ...)` | `Rejected(...)` |
| `UnexpectedError(IllegalStateException("boom"))` | `Rejected("Unexpected error")` (no exception text shown to user) |

`GroqErrorMessageTest`: `{"error":{"message":"Invalid API Key","type":"invalid_request_error"}}` gives
`"Invalid API Key"`; an escaped quote inside the message is unescaped; plain text `"Bad gateway"` gives itself; a 500-char
plain body is cut to 200 chars; `null`, `""` and `"  "` give null. Implement with a small regex
(`"message"\s*:\s*"((?:[^"\\]|\\.)*)"`), because `org.json` is a stub in JVM tests.

- [ ] Red, green, commit `feat(core): transcription result types and spec error classification`

### Task 2.5: GroqClient, OkHttp await, reachability probe (layer 2, MockWebServer)

**Files:** `core/net/OkHttpAwait.kt`, `core/net/GroqClient.kt`, `core/net/GroqReachabilityProbe.kt`;
tests `core/net/GroqClientTest.kt`, `core/net/GroqReachabilityProbeTest.kt`

```kotlin
/** Suspends on Call.enqueue; cancelling the coroutine cancels the call. Body read happens off the main thread. */
suspend fun Call.awaitResponse(): Pair<Response, String>   // code + headers + body string, response closed

class GroqClient(
    private val http: OkHttpClient,                       // built by GroqClient.defaultHttpClient(timeouts)
    private val baseUrl: HttpUrl = "https://api.groq.com/openai/v1/".toHttpUrl(),
    private val timeouts: TimeoutPolicy = TimeoutPolicy(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) : TranscriptionApi {
    override suspend fun transcribe(audio: File, apiKey: String, request: TranscriptionRequest): TranscriptionResult
    suspend fun checkKey(apiKey: String): KeyCheckResult     // GET {baseUrl}models
    companion object { fun defaultHttpClient(t: TimeoutPolicy = TimeoutPolicy()): OkHttpClient }
}

class GroqReachabilityProbe(
    private val http: OkHttpClient,
    private val url: HttpUrl = "https://api.groq.com/".toHttpUrl(),
    private val timeoutMs: Long = 5_000,
) { suspend fun isReachable(): Boolean }   // HEAD; ANY HTTP response = true; IOException/timeout = false
```

Request details (desktop parity): `POST {baseUrl}audio/transcriptions`, header `Authorization: Bearer <key>`,
multipart/form-data with parts `file` (filename `audio.ogg`, `Content-Type: audio/ogg`, file bytes), `model`,
`language`, `temperature` (`"0"`), `response_format` (`"text"`). Per call:
`call.timeout().timeout(timeouts.callTimeoutMs(audio.length()), MILLISECONDS)`. Client: connect 10 s,
read and write 30 s inactivity. `Success.text` is the raw body; cleaning happens in the controller. `HttpError.message`
comes from `GroqErrorMessage.extract(body)` and `retryAfterMs` from `RetryAfter.parseMs(header("retry-after"), nowMs())`.
The API key must never appear in any returned message or exception text.

Tests to write first (`GroqClientTest`, real MockWebServer; `@Before server.start()`, `@After server.close()`;
`client = GroqClient(GroqClient.defaultHttpClient(fastTimeouts), server.url("/openai/v1/"), fastTimeouts)` where
`fastTimeouts = TimeoutPolicy(connectTimeoutMs = 1_000, baseCallTimeoutMs = 1_500, inactivityTimeoutMs = 700)`;
the audio file is a `TemporaryFolder` file with 4 KB of known bytes):

```kotlin
@Test fun `200 returns raw text`() = runTest {
    server.enqueue(MockResponse.Builder().code(200).body(" Hello there.\n").build())
    assertEquals(TranscriptionResult.Success(" Hello there.\n"), client.transcribe(audio, "test-key-123", TranscriptionRequest()))
}

@Test fun `multipart shape matches desktop request`() = runTest {
    server.enqueue(MockResponse.Builder().code(200).body("ok").build())
    client.transcribe(audio, "test-key-123", TranscriptionRequest())
    val req = server.takeRequest()
    assertEquals("POST", req.method)
    assertEquals("/openai/v1/audio/transcriptions", req.url.encodedPath)
    assertEquals("Bearer test-key-123", req.headers["Authorization"])
    val contentType = req.headers["Content-Type"]!!.toMediaType()
    assertEquals("multipart", contentType.type); assertEquals("form-data", contentType.subtype)
    val parts = readParts(req.body!!, contentType.parameter("boundary")!!)   // helper using okhttp3.MultipartReader
    assertEquals(setOf("file", "model", "language", "temperature", "response_format"), parts.keys)
    assertEquals("whisper-large-v3-turbo", parts["model"]!!.text)
    assertEquals("en", parts["language"]!!.text)
    assertEquals("0", parts["temperature"]!!.text)
    assertEquals("text", parts["response_format"]!!.text)
    assertTrue(parts["file"]!!.contentDisposition.contains("filename=\"audio.ogg\""))
    assertEquals("audio/ogg", parts["file"]!!.contentType)
    assertArrayEquals(audio.readBytes(), parts["file"]!!.bytes)
}
```

Other cases, each its own test:
- **401** with JSON `{"error":{"message":"Invalid API Key"}}` gives `HttpError(401, "Invalid API Key", null)`. No retry happens inside the client: exactly one request.
- **429** with header `retry-after: 7` gives `HttpError(429, _, 7000)`.
- **500** gives `HttpError(500, _, null)`.
- **Slow body past the call timeout**: `.bodyDelay(5, SECONDS)` gives `NetworkError`, and the call returns in under 3 s of wall time (assert with `System.nanoTime()`).
- **Slow but progressing body**: `.body("x".repeat(10)).throttleBody(1, 100, MILLISECONDS)` (1 s total, every gap under the 700 ms inactivity timeout, total under the 1.5 s call timeout) gives `Success`. This proves inactivity semantics.
- **Dropped connection**: `.onResponseStart(SocketEffect.ShutdownConnection)`, or `onRequestStart(SocketEffect.CloseSocket())` if that is unreliable, gives `NetworkError`.
- **Server gone**: `server.close()` before the call gives `NetworkError`.
- **Cancellation**: launch `transcribe` with `.headersDelay(10, SECONDS)`, cancel the job after 200 ms of real time (`withContext(Dispatchers.Default.limitedParallelism(1)) { delay(200) }`), assert `job.join()` completes within 2 s.
- **Language and model come from the request**: `TranscriptionRequest(model = "whisper-large-v3", language = "de")` reaches the parts.
- **The key never leaks**: for 401, 500, dropped connection and timeout, `result.toString()` does not contain `"test-key-123"`.
- `checkKey`: 200 gives `Valid` and is a `GET /openai/v1/models` with the bearer header. 401 gives `Rejected`. A dropped connection gives `NoConnection`. 500 gives `Failed(500)`.

`GroqReachabilityProbeTest`: a 404 HEAD answer gives `true` (method is `HEAD`); a 200 gives `true`; a dropped
connection gives `false`; `headersDelay` beyond `timeoutMs = 300` gives `false` within 2 s.

Use `kotlinx.coroutines.test.runTest`. The network is real, so wrap the real-time parts as above, or use plain
`runBlocking` for MockWebServer tests where virtual time adds nothing.

- [ ] Red, green, commit `feat(core): GroqClient multipart transcription with typed outcomes (MockWebServer tested)`

### Task 2.6: ReachabilityLoop

**Files:** `core/net/ReachabilityLoop.kt`; test `core/net/ReachabilityLoopTest.kt`

```kotlin
/** Desktop waitForConnectivity(): probe, and if unreachable wait intervalMs and probe again, forever, until cancelled. */
suspend fun awaitReachable(probe: suspend () -> Boolean, intervalMs: Long = 3_000)
```

Tests (`runTest`, virtual time): first probe true returns at time 0 after one probe; false, false, true returns
at `currentTime == 6_000` after 3 probes; always false is still suspended after `advanceTimeBy(600_000)` and
`job.cancel()` ends it (no further probes after cancel).

- [ ] Red, green, commit `feat(core): cancellable reachability loop`

### Task 2.7: MicGestureDetector

**Files:** `core/gesture/MicGestureDetector.kt`; test `core/gesture/MicGestureDetectorTest.kt`

```kotlin
data class GestureConfig(
    val holdMs: Long = 500,
    val lockSlideDp: Float = 70f,
    val cancelSlideDp: Float = 90f,
) {
    fun lockSlidePx(density: Float) = lockSlideDp * density
    fun cancelSlidePx(density: Float) = cancelSlideDp * density
}

enum class GestureEvent { Hold, Lock, Cancel, Release, TooShort }

class MicGestureDetector(private val config: GestureConfig = GestureConfig(), private val density: Float) {
    enum class State { Idle, Pressed, Locked }
    val state: State
    fun onDown(x: Float, y: Float, timeMs: Long): GestureEvent?   // Idle -> Pressed: Hold; Locked: null (start of Stop tap)
    fun onMove(x: Float, y: Float, timeMs: Long): GestureEvent?   // Pressed: Lock / Cancel / null
    fun onUp(x: Float, y: Float, timeMs: Long): GestureEvent?     // Pressed: Release / TooShort; Locked: Release (Stop tap)
    fun onCancel(timeMs: Long): GestureEvent?                     // Pressed: as onUp; Locked: null
}
```

Semantics: recording starts on `Hold` (finger down). `dy = y - downY` (negative is up) and `dx = x - downX`
(negative is left). On a move in Pressed: if `|dx| > |dy|` and `dx <= -cancelPx`, emit Cancel and go Idle.
Otherwise, if `|dy| >= |dx|` and `dy <= -lockPx`, emit Lock and go Locked. Lock does not depend on time.
Release requires `timeMs - downTime >= holdMs`.

Tests (density 1.0, so lock is 70 px and cancel is 90 px):
- `down` gives `Hold` and state Pressed.
- `down@0` then `up@499` gives `TooShort` and state Idle. `down@0` then `up@500` gives `Release`.
- `down`, `move(dy=-71)` gives `Lock` and state Locked. A later `up` gives `null` (stays Locked). Then `down` gives null, and `up` gives `Release` and state Idle.
- Lock reached within 100 ms, then the Stop tap, gives `Release` (a short locked recording is still sent; the controller's size check handles empty audio).
- `down`, `move(dx=-91)` gives `Cancel` and state Idle. A later `up` gives null.
- `move(dy=-69)` gives null. `move(dx=-89)` gives null. `move(dx=+200)` and `move(dy=+200)` give null, and an `up@600` afterwards gives Release.
- Diagonal `move(dx=-100, dy=-60)` gives Cancel (horizontal dominant). `move(dx=-60, dy=-100)` gives Lock.
- After Cancel, further moves give null (no Lock from the same gesture).
- `onCancel@600` while Pressed gives Release. `onCancel@100` gives TooShort. `onCancel` while Locked gives null and state stays Locked.
- Events while Idle (`move`, `up`, `onCancel` with no `down`) give null.
- A second `down` while Pressed gives null.
- `GestureConfig().lockSlidePx(2.75f) == 192.5f` (dp conversion in one place).

- [ ] Red, green, commit `feat(core): MicGestureDetector state machine (hold, slide-up lock, slide-left cancel)`

### Task 2.8: EnterKeyResolver and SettingsRules

**Files:** `core/keys/EnterKeyResolver.kt`, `core/settings/SettingsRules.kt`; tests `core/keys/EnterKeyResolverTest.kt`,
`core/settings/SettingsRulesTest.kt`; plus layer-3 `ime/EditorInfoConstantsTest.kt` (plain JVM; the
`EditorInfo`/`InputType` constants are compile-time ints inlined from android.jar)

```kotlin
enum class EnterAction { Go, Search, Send, Next, Done, Previous, Newline }
object EnterKeyResolver {
    // Mirrors android.view.inputmethod.EditorInfo / android.text.InputType values (asserted in ime/EditorInfoConstantsTest).
    const val IME_MASK_ACTION = 0x000000ff; const val IME_ACTION_UNSPECIFIED = 0; const val IME_ACTION_NONE = 1
    const val IME_ACTION_GO = 2; const val IME_ACTION_SEARCH = 3; const val IME_ACTION_SEND = 4
    const val IME_ACTION_NEXT = 5; const val IME_ACTION_DONE = 6; const val IME_ACTION_PREVIOUS = 7
    const val IME_FLAG_NO_ENTER_ACTION = 0x40000000; const val TYPE_TEXT_FLAG_MULTI_LINE = 0x00020000
    fun resolve(imeOptions: Int, inputType: Int): EnterAction
    fun label(action: EnterAction): String   // "Go", "Search", "Send", "Next", "Done", "Prev", "⏎"
}

object SettingsRules {
    val MODELS = listOf("whisper-large-v3-turbo", "whisper-large-v3"); const val DEFAULT_MODEL = "whisper-large-v3-turbo"
    const val DEFAULT_LANGUAGE = "en"
    fun normaliseKey(raw: String): String?        // trims all whitespace incl. newlines; blank -> null; internal whitespace -> null
    fun normaliseLanguage(raw: String): String?   // trim + lowercase; blank -> "en"; must match ^[a-z]{2,3}$ else null (invalid)
    fun modelOrDefault(stored: String?): String   // unknown/null -> DEFAULT_MODEL
}
```

Enter rules: `IME_FLAG_NO_ENTER_ACTION` set gives Newline. Action NONE or UNSPECIFIED gives Newline. A multi-line input
type with action UNSPECIFIED gives Newline. Otherwise the mapped action. Tests: every action constant, the
flag overriding SEND, multi-line text with DONE giving Done (an explicit action wins), unknown action 99 giving Newline.

SettingsRules tests: `normaliseKey("  test-key-123\n")` gives `"test-key-123"`; `""` and `"  "` give null;
`"abc def"` gives null. `normaliseLanguage("EN ")` gives `"en"`, `""` gives `"en"`, `"english"` gives null, `"de"` gives `"de"`.
`modelOrDefault("whisper-large-v3")` stays as it is; `modelOrDefault("gpt")` and `modelOrDefault(null)` give the turbo default.

- [ ] Red, green, commit `feat(core): action-aware enter key resolution and settings normalisation`

### Task 2.9: Status, Cue, StatusFormatter, FeedbackPolicy

**Files:** `core/controller/Status.kt`, `core/controller/StatusFormatter.kt`, `core/controller/FeedbackPolicy.kt`;
tests `core/controller/StatusFormatterTest.kt`, `core/controller/FeedbackPolicyTest.kt`

```kotlin
sealed interface Status {
    data object Idle : Status
    data class Recording(val startedAtMs: Long, val locked: Boolean) : Status
    data object Transcribing : Status
    data class Retrying(val attempt: Int, val maxAttempts: Int) : Status
    data object WaitingForSignal : Status
    data object TextReady : Status            // field changed: text on clipboard, ↺ inserts
    data object NothingHeard : Status
    data object HoldToTalk : Status
    data object NoApiKey : Status
    data object NoMicPermission : Status
    data object AuthRejected : Status
    data class Rejected(val message: String) : Status
    data class GaveUp(val attempts: Int) : Status
    data object Cancelled : Status
    data object RecordingFailed : Status
}
enum class Cue { RecordStart, RecordStop, Retry, Error, NothingHeard }

object StatusFormatter {
    fun text(status: Status, nowMs: Long): String
    fun unsentChip(count: Int): String?                  // null when 0
    fun showsCancel(status: Status): Boolean             // Transcribing, Retrying, WaitingForSignal
}
enum class Effect { StartTone, StopTone, RetryTone, ErrorTone, HapticConfirm, HapticRetry, HapticReject }
object FeedbackPolicy { fun effects(cue: Cue, soundOn: Boolean, hapticOn: Boolean): Set<Effect> }
```

StatusFormatter expectations (exact strings, so the view and the proof can rely on them):
- Idle: `"Hold to talk, slide up to lock"`
- Recording 12,345 ms after the start: `"Recording 0:12"`. At 3,605,000 ms: `"Recording 60:05"`. When locked: `"Recording 0:12, tap Stop to send"`
- Transcribing: `"Transcribing..."`. `Retrying(3,10)`: `"Retrying (3/10)..."`. WaitingForSignal: `"Waiting for signal..."`
- TextReady: `"Text ready, tap ↺ to insert"`. NothingHeard: `"Nothing heard"`. HoldToTalk: `"Hold to talk"`
- NoApiKey: `"Add your Groq API key in Groq Voice settings"`. NoMicPermission: `"Allow the microphone in Groq Voice settings"`
- AuthRejected: `"API key rejected, open settings"`. `Rejected("file too short")`: `"Groq: file too short"`
- `GaveUp(10)`: `"Couldn't send after 10 tries, saved as unsent"`. Cancelled: `"Cancelled, saved as unsent"`. RecordingFailed: `"Recording failed"`
- `unsentChip(0)` is null and `unsentChip(2)` is `"⟳ 2 unsent"`. `showsCancel` is true exactly for the three in-flight statuses.

FeedbackPolicy expectations: RecordStart gives `{StartTone, HapticConfirm}`, RecordStop gives `{StopTone, HapticConfirm}`,
Retry gives `{RetryTone, HapticRetry}`, Error gives `{ErrorTone, HapticReject}`, NothingHeard gives `{HapticReject}` (short error
haptic, no sound). `soundOn=false` removes every tone. `hapticOn=false` removes every haptic. Both off give an empty set.

- [ ] Red, green, commit `feat(core): status model, status text and feedback policy`

### Task 2.10: TranscriptionController (with fakes)

**Files:** `core/controller/Ports.kt`, `core/controller/TranscriptionController.kt`;
test `core/controller/TranscriptionControllerTest.kt` plus `core/controller/Fakes.kt` (test source set)

Ports (implemented by the Android layer in Section 3):

```kotlin
data class FieldIdentity(val packageName: String, val fieldId: Int, val session: Long)

interface AudioRecorder {
    /** Starts recording into [file]. Returns false if the recorder could not start. */
    fun start(file: File, onMaxDuration: () -> Unit): Boolean
    /** Stops; true if a file was finalised. Never throws. */
    fun stop(): Boolean
    /** Stops and deletes the file. Never throws. */
    fun cancel()
}
interface RecordingStore {
    fun newRecordingFile(): File            // recording/<millis>.ogg
    fun moveToPending(file: File): File     // pending/<same name>
    fun pending(): List<File>               // oldest first
    fun isValid(file: File): Boolean        // exists and length > 1024
    fun markSucceeded(file: File)           // move to history, keep last 2
    fun discard(file: File)
    var lastText: String?
}
fun interface ApiKeyProvider { fun apiKey(): String? }
fun interface RequestProvider { fun request(): TranscriptionRequest }
fun interface MicPermission { fun granted(): Boolean }
interface ConnectivityGate { suspend fun awaitOnline() }   // suspends until online; cancellable
interface DeliveryTarget {
    fun currentField(): FieldIdentity?
    fun charBeforeCursor(): Char?
    fun commit(text: String)
    fun copyToClipboard(text: String)
}
interface ControllerListener {
    fun onStatus(status: Status)
    fun onPendingCount(count: Int)
    fun onCue(cue: Cue)
}
fun interface Clock { fun nowMs(): Long }
```

Controller:

```kotlin
class TranscriptionController(
    private val scope: CoroutineScope,
    private val api: TranscriptionApi,
    private val gate: ConnectivityGate,
    private val recorder: AudioRecorder,
    private val store: RecordingStore,
    private val keys: ApiKeyProvider,
    private val requests: RequestProvider,
    private val mic: MicPermission,
    private val delivery: DeliveryTarget,
    private val listener: ControllerListener,
    private val clock: Clock,
    private val retry: RetryPolicy = RetryPolicy(),
) {
    val isRecording: Boolean
    fun startRecording(): Boolean          // GestureEvent.Hold
    fun lock()                             // GestureEvent.Lock
    fun stopAndTranscribe()                // GestureEvent.Release, Stop tap, max duration, keyboard hidden
    fun cancelRecording()                  // GestureEvent.Cancel: discard audio
    fun discardTooShort()                  // GestureEvent.TooShort: discard + HoldToTalk
    fun cancelTranscription()              // ✕ : cancel all in-flight/queued sends, audio stays pending
    fun retryPending()                     // "⟳ N unsent" chip: send every pending file, oldest first, to the current field
    fun reinsertLast()                     // ↺
    fun refreshPendingCount()
}
```

Behaviour, which the tests pin down:
1. `startRecording`: no key gives `NoApiKey` and false. No mic permission gives `NoMicPermission` and false. Otherwise `recorder.start(store.newRecordingFile())`.
   If that returns false, the status is `RecordingFailed`. On success: cue `RecordStart`, status `Recording(now, locked=false)`.
2. `stopAndTranscribe` when not recording is a no-op. Otherwise: `recorder.stop()`, cue `RecordStop`, capture `delivery.currentField()`,
   `moveToPending`, update the pending count. An invalid file is discarded with status `RecordingFailed` and no API call. Otherwise a send
   job is launched (Mutex-serialised).
3. Send loop, for attempt 1..max: status `Transcribing` (attempt 1) or `Retrying(attempt, max)`, then call the API.
   - Success: `TranscriptCleaner.clean`. Nothing heard: discard the file, cue `NothingHeard`, status `NothingHeard`,
     `lastText` unchanged. Otherwise set `lastText`, call `markSucceeded`, deliver, and update the pending count.
   - Classify the failure. `AuthRejected`: status `AuthRejected`, cue `Error`, keep the file, stop. `Rejected(m)`: status `Rejected(m)`, cue `Error`, keep, stop.
   - `Retryable(ra)`: if no attempts are left, break. Otherwise cue `Retry`. If `waitForConnectivityAfter(attempt)`, set status `WaitingForSignal`
     and call `gate.awaitOnline()`. Then `delay(retry.delayAfter(attempt, ra))`.
   - After the loop: status `GaveUp(max)`, cue `Error`, keep the file.
   - The API key is re-read per attempt. If it is null mid-way, status `NoApiKey` and keep the file.
4. Delivery: if `delivery.currentField() == captured` (non-null), `commit((if needsLeadingSpace(charBeforeCursor()) " " else "") + text)`
   and set status `Idle`. Otherwise `copyToClipboard(text)` and status `TextReady`.
5. `cancelRecording`: `recorder.cancel()`, status `Idle`, no pending file. `discardTooShort`: `recorder.cancel()`, status `HoldToTalk`.
6. `cancelTranscription`: cancel the send jobs (including one suspended in `gate.awaitOnline()` or `delay`). Status `Cancelled`. Files stay pending.
7. `retryPending`: capture `currentField()` once and enqueue every `store.pending()` file, oldest first.
8. `reinsertLast`: if `lastText` is non-null, commit it with the leading-space rule to the current field. Otherwise do nothing.
9. The scope being cancelled (service destroyed) leaves files in pending.

Fakes (`Fakes.kt`): `FakeApi(vararg scripted: TranscriptionResult)` records `calls: List<Pair<File,String>>` and call times
(`testScheduler.currentTime`) and returns results in order (the last one repeats). `FakeGate` counts `awaitOnline` calls and
can be set to suspend forever (`CompletableDeferred`). `FakeRecorder` writes N bytes to the file on `stop()`, configurable
(default 4096). `FakeStore` is backed by a real temp dir, or a `PendingStore` stand-in with in-memory lists; an in-memory implementation
with real `File` paths under `TemporaryFolder` is simplest. `FakeDelivery` has a mutable `field`, `before: Char?`,
`committed: MutableList<String>`, `clipboard: String?`. `RecordingListener` records statuses, cues and pending counts.

Tests to write first (each is its own `@Test fun ... = runTest { ... }`, controller built with `backgroundScope` or `this`):

```kotlin
@Test fun `happy path commits cleaned text with leading space into the same field`() = runTest {
    val api = FakeApi(Success(" Hello there. Thank you."))
    val delivery = FakeDelivery(field = F1, before = 'x')
    val c = controller(api = api, delivery = delivery)
    assertTrue(c.startRecording()); c.stopAndTranscribe(); advanceUntilIdle()
    assertEquals(listOf(" Hello there."), delivery.committed)
    assertEquals("Hello there.", store.lastText)
    assertEquals(1, store.history.size); assertTrue(store.pending().isEmpty())
    assertEquals(listOf(Cue.RecordStart, Cue.RecordStop), listener.cues)
    assertEquals("test-key-123", api.calls.single().second)
}
```

Full required list (names are the test names):
1. `happy path commits cleaned text with leading space into the same field` (above)
2. `no leading space after whitespace or at start of field` (before = ' ', and before = null)
3. `field changed during transcription copies to clipboard and shows TextReady` (delivery.field changed to F2 before `advanceUntilIdle`; nothing committed; clipboard == text; lastText set)
4. `field gone during transcription copies to clipboard` (field = null)
5. `same package and field id but new session counts as changed` (F1.copy(session = 2))
6. `retries IO failures then succeeds, waiting for connectivity once after first failure` (results: Network, Network, Success; 3 calls; `gate.calls == 1`; second call at t=0 after the gate, third call at t=2000; statuses contain `WaitingForSignal` and `Retrying(2,10)`, `Retrying(3,10)`; cue `Retry` twice)
7. `gives up after ten attempts keeping the recording unsent` (always Network; 10 calls; final `currentTime == 242_000`; status `GaveUp(10)`; 9 Retry cues; then 1 Error cue; pending has the file; last pending count 1)
8. `401 stops immediately without retry or connectivity wait and keeps audio` (1 call; `gate.calls == 0`; status `AuthRejected`; pending 1)
9. `403 is treated like 401`
10. `400 shows Groq message, no retry, audio kept` (status `Rejected("file too short")`)
11. `429 honours retry-after` (results: 429(retryAfter=5000), Success; gate awaited once; `advanceTimeBy(4_999)` gives 1 call; `advanceTimeBy(2)` gives 2 calls)
12. `429 without retry-after on a later attempt uses normal backoff` (Network, 429(null), Success: third call at t=2000)
13. `500 is retried`
14. `cancel during connectivity wait stops sending and keeps audio` (Network first; gate suspends forever; `runCurrent()`; status `WaitingForSignal`; `c.cancelTranscription()`; `advanceTimeBy(600_000)`; 1 call total; status `Cancelled`; pending 1; no commit)
15. `cancel during backoff sleep keeps audio`
16. `slide-left cancel discards recording and never calls the API` (pending empty, recorder.cancelled)
17. `too short tap discards and hints HoldToTalk`
18. `nothing heard deletes audio and inserts nothing` (Success("Thank you."): no commit, no clipboard, pending empty, history empty, cue NothingHeard, lastText unchanged)
19. `recording under 1 KB is discarded without calling the API` (FakeRecorder bytes = 1024)
20. `no API key refuses to record` (startRecording false, status NoApiKey, recorder never started)
21. `no mic permission refuses to record`
22. `recorder failing to start shows RecordingFailed`
23. `stop when not recording does nothing`
24. `retryPending sends unsent recordings oldest first into the current field` (two pending files made by an earlier GaveUp; then the API succeeds with "one", "two"; committed == [" one", " two"] in order, given before = 'x' each time; pending count goes 2, then 1, then 0)
25. `second recording made while first is sending is delivered after the first` (first Success after `delay(5_000)` inside FakeApi; record + stop twice; commits in order)
26. `cancelTranscription cancels queued jobs too and keeps both files`
27. `reinsertLast commits last text with leading space rule` and `reinsertLast with no last text does nothing`
28. `max duration callback stops and transcribes` (FakeRecorder invokes onMaxDuration)
29. `scope cancelled mid-request leaves audio pending` (controller on a child `Job` scope; cancel it; pending 1)
30. `lock sets Recording locked` (status `Recording(t, locked = true)`)
31. `api key removed between attempts stops with NoApiKey and keeps audio`

- [ ] Red (write all 31, watch them fail to compile and then fail), green one at a time, refactor, commit
  `feat(core): TranscriptionController orchestrating record, send with retries, clean and deliver`

### Task 2.R: Core self-check before handing over

- [ ] `scripts/verify.sh` passes (layer 4 skipped is fine). Report the test count from
  `app/build/test-results/testDebugUnitTest/*.xml` (`grep -h -o 'tests="[0-9]*"' ... | awk` sum) in the summary.
- [ ] `scripts/guards.sh` passes (core purity).
- [ ] Time the hook: `time scripts/git-hooks/pre-commit` on a warm daemon must be under 60 s. If not, investigate
  (configuration cache, `--offline`), never drop tests from it.

---

## Section 3: Android layer

Thin adapters over the core ports. Test-first wherever a JVM/Robolectric test is practical (layer 3). Keystore,
MediaRecorder and the IME are tested on the emulator (layer 4) via `scripts/instrumented.sh` only.

### Task 3.1: PendingStore (layer 3, plain JVM)

**Files:** `storage/PendingStore.kt`; test `storage/PendingStoreTest.kt`

```kotlin
class PendingStore(private val root: File, private val clock: Clock, private val minValidBytes: Long = 1024) : RecordingStore {
    // root/recording/, root/pending/, root/history/1.ogg (most recent), root/history/2.ogg, root/last_text.txt
    fun pendingCount(): Int
    fun recoverInterrupted(): Int      // move valid recording/* into pending/, delete invalid; returns moved count
    fun clearPending(): Int            // settings "Delete unsent"
}
```

Tests (`TemporaryFolder`, fixed clock):
- `newRecordingFile` is `recording/1700000000000.ogg`. A second call at the same millis gets a unique name (`..._1.ogg`).
- `moveToPending` keeps the name. `pending()` is sorted oldest first and ignores non-`.ogg` files.
- `isValid`: missing is false, 1024 bytes is false, 1025 bytes is true (spec says "> 1 KB").
- `markSucceeded` rotation: the first success is in `history/1.ogg`. The second success moves the first to `2.ogg`. The third deletes the oldest. The file is no longer in pending.
- `discard` deletes. `clearPending` deletes all and returns the count.
- `lastText` round trip, including newlines and unicode (`"Café ↺\nline 2"`). Null when missing. Setting null deletes it.
- `recoverInterrupted` moves a 4 KB leftover into pending, deletes a 10-byte leftover, and returns 1.
- The store survives a fresh instance on the same root (persistence).

- [ ] Red, green, commit `feat(storage): PendingStore with recording/pending/history dirs and last text`

### Task 3.2: AppSettings (layer 3, Robolectric)

**Files:** `settings/AppSettings.kt`; test `settings/AppSettingsTest.kt` (`@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])`)

```kotlin
class AppSettings(private val prefs: SharedPreferences) : RequestProvider {
    var model: String          // get = SettingsRules.modelOrDefault(stored)
    var language: String       // default "en"
    var soundOn: Boolean       // default true
    var hapticOn: Boolean      // default true
    override fun request(): TranscriptionRequest   // model, language, temperature "0", response_format "text"
    companion object { fun from(context: Context): AppSettings }   // prefs file "settings"
}
```

Tests: defaults; each property persists across a new instance; a garbage stored model falls back to turbo;
`request()` reflects the current model and language and fixed temperature and format; the API key is not stored here (the
prefs file has no key containing "key").

- [ ] Red, green, commit `feat(settings): AppSettings over SharedPreferences`

### Task 3.3: ApiKeyStore (layer 4, emulator)

**Files:** `security/ApiKeyStore.kt`; test `app/src/androidTest/java/net/tdobson/groqvoice/security/ApiKeyStoreTest.kt`

```kotlin
class ApiKeyStore(private val prefs: SharedPreferences, private val keyAlias: String = "groqvoice_api_key_kek") : ApiKeyProvider {
    fun save(apiKey: String)          // AES/GCM/NoPadding, 256-bit key in AndroidKeyStore (non-exportable), random IV, base64 ct+iv in prefs
    override fun apiKey(): String?    // null if absent; on any decrypt failure clears stored ciphertext and returns null
    fun clear()
    fun hasKey(): Boolean
    companion object { fun from(context: Context): ApiKeyStore }   // prefs file "secure"
}
```

Instrumented tests (`assumeTrue(EmulatorOnly.isEmulator())`, unique alias per test, alias deleted in `@After`):
round trip; overwrite returns the new value; `clear()` gives null; a new instance on the same prefs reads the same key;
the raw prefs values do not contain the plaintext; tampered ciphertext (flip a byte) gives null and `hasKey()` false;
a missing Keystore entry with ciphertext present gives null and no crash; the key entry is `AndroidKeyStore` and the
private key material is not extractable (`(ks.getKey(alias, null) as SecretKey).encoded == null`).

- [ ] Write the test, run `scripts/instrumented.sh net.tdobson.groqvoice.security.ApiKeyStoreTest` (emulator via
  `scripts/emulator.sh start`) and see it fail, implement, see it pass, commit `feat(security): Keystore-backed ApiKeyStore`

### Task 3.4: Recorder (layer 4, emulator)

**Files:** `audio/Recorder.kt`; test `app/src/androidTest/java/net/tdobson/groqvoice/audio/RecorderTest.kt`

```kotlin
class Recorder(
    private val context: Context,
    private val maxDurationMs: Int = 60 * 60 * 1000,   // spec: 60 min; tests pass a short value
) : AudioRecorder {
    // MediaRecorder(context) on API 31+, MediaRecorder() below.
    // setAudioSource(VOICE_RECOGNITION); setOutputFormat(OGG); setAudioEncoder(OPUS);
    // setAudioSamplingRate(16_000); setAudioChannels(1); setAudioEncodingBitRate(24_000);
    // setMaxDuration(maxDurationMs) + OnInfoListener(MAX_DURATION_REACHED) -> onMaxDuration()
    // stop(): RuntimeException from MediaRecorder.stop (no data yet) -> delete file, return false. Always release().
}
```

Instrumented tests (GrantPermissionRule `RECORD_AUDIO`, emulator only):
- Record 3 s and stop: true. The file starts with `OggS`, and its first page contains `OpusHead`. `MediaExtractor` track 0 has MIME
  `audio/opus`, `KEY_CHANNEL_COUNT == 1`, duration of at least 2.5 s. The OpusHead input-sample-rate field
  (uint32 LE at offset 12 of the OpusHead packet) equals 16000. If it does not, first check what the encoder really used.
  The spec requires 16 kHz, so fix the recorder settings, or (only if the platform genuinely reports a different value)
  assert on what MediaExtractor reports and explain why in the commit.
- `cancel()` deletes the file.
- `stop()` within 100 ms of `start()` returns false, leaves no file and does not throw.
- `start()` twice without stop: the second returns false and does not crash.
- The max-duration listener fires: with `Recorder(context, maxDurationMs = 1_500)`, the callback runs within 4 s.

- [ ] Red on emulator, green, commit `feat(audio): MediaRecorder Ogg/Opus 16 kHz mono recorder`

### Task 3.5: AndroidConnectivityWaiter

**Files:** `net/AndroidConnectivityWaiter.kt`; test `net/AndroidConnectivityWaiterTest.kt` (Robolectric, where practical)

```kotlin
class AndroidConnectivityWaiter(
    private val cm: ConnectivityManager,
    private val probe: GroqReachabilityProbe,
    private val intervalMs: Long = 3_000,
) : ConnectivityGate {
    override suspend fun awaitOnline() {
        awaitValidatedNetwork()   // fast path: activeNetwork has NET_CAPABILITY_VALIDATED; else registerDefaultNetworkCallback,
                                  // resume on onCapabilitiesChanged with VALIDATED; unregister in finally / invokeOnCancellation
        awaitReachable(probe::isReachable, intervalMs)
    }
}
```

Split the validated-network wait into a pure helper if Robolectric's `ShadowConnectivityManager` cannot drive
callbacks. The tests: an already validated network returns without registering a callback; no network
suspends, and a delivered VALIDATED capability resumes it; cancelling unregisters the callback (no leak). If
Robolectric cannot model `registerDefaultNetworkCallback` on SDK 35, say so in the commit and rely on the
ReachabilityLoop tests (2.6), the controller cancel tests (2.10) and the emulator network-toggle check in Section 4.

- [ ] Commit `feat(net): connectivity waiter (validated network, then Groq HEAD every 3 s)`

### Task 3.6: IME service, view and delivery

**Files:**
- `ime/VoiceKeyboardService.kt`, `ime/KeyboardViewBinder.kt`, `ime/ImeDelivery.kt`, `ime/Feedback.kt`, `util/Logx.kt`
- `res/layout/keyboard.xml`, `res/xml/method.xml`, `res/drawable/*` (mic circle, pulsing ring, key backgrounds), `res/values/strings.xml`
- Manifest additions
- Tests: `ime/ImeDeliveryTest.kt` (Robolectric), `app/src/androidTest/java/net/tdobson/groqvoice/ime/ImeServiceTest.kt`

View ids (stable; Section 4 relies on them through uiautomator `resource-id="net.tdobson.groqvoice:id/<id>"`):
`mic_button`, `mic_ring`, `progress`, `status_text`, `status_cancel` (✕), `unsent_chip`, `key_backspace`, `key_space`,
`key_enter`, `key_reinsert` (↺), `key_globe`. Each key has a `contentDescription` too.

Layout: one strip, about normal keyboard height (around 260 dp). The status line is at the top (`status_text` plus `status_cancel`, and `unsent_chip` on the right).
Below it is a row with `key_globe`, `key_reinsert`, the large round `mic_button` (about 96 dp) centred with `mic_ring` behind it, then `key_backspace`. The bottom row has
`key_space` (wide) and `key_enter`.

`ImeDelivery` (implements `DeliveryTarget`, tested with Robolectric using a fake `InputConnection`):

```kotlin
class ImeDelivery(private val service: () -> InputMethodService?, private val clipboard: ClipboardManager) : DeliveryTarget {
    var current: FieldIdentity? = null          // set by service in onStartInput / cleared in onFinishInput
    override fun currentField() = current
    override fun charBeforeCursor(): Char? = service()?.currentInputConnection?.getTextBeforeCursor(1, 0)?.firstOrNull()
    override fun commit(text: String) { service()?.currentInputConnection?.commitText(text, 1) }
    override fun copyToClipboard(text: String)  // ClipData.newPlainText("Groq Voice", text), marked sensitive=false
}
```

`VoiceKeyboardService : InputMethodService`:
- `onCreate`: build `PendingStore(filesDir/"voice", ...)` and call `recoverInterrupted()`. Build `AppSettings`, `ApiKeyStore`, `GroqClient`,
  `AndroidConnectivityWaiter`, `Recorder`, `ImeDelivery`, and the `TranscriptionController` on
  `scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`.
- `onCreateInputView`: inflate `keyboard.xml` and bind via `KeyboardViewBinder`.
- `onStartInput(info, restarting)`: `session++`, then `delivery.current = FieldIdentity(info.packageName ?: "", info.fieldId, session)`.
  `onFinishInput`: `delivery.current = null`.
- `onStartInputView`: set the enter label from `EnterKeyResolver.resolve(info.imeOptions, info.inputType)`, and refresh the pending count.
- `onFinishInputView`: if recording, `controller.stopAndTranscribe()` (spec refinement 9).
- `onDestroy`: if recording, `stopAndTranscribe()` so the file reaches pending, then `scope.cancel()`.
- Mic touch: `MicGestureDetector(GestureConfig(), resources.displayMetrics.density)`. Map `MotionEvent` DOWN/MOVE/UP/CANCEL
  to the detector, then Hold to `startRecording()`, Lock to `lock()`, Cancel to `cancelRecording()`, Release to `stopAndTranscribe()`,
  and TooShort to `discardTooShort()`.
- Backspace: on tap, `sendDownUpKeyEvents(KEYCODE_DEL)`. While held, repeat every 50 ms after a 400 ms delay (Handler).
- Space: `commitText(" ", 1)`. Enter: Newline sends `sendDownUpKeyEvents(KEYCODE_ENTER)`, otherwise `performEditorAction(actionId)`.
- ↺: `controller.reinsertLast()`. Globe tap: `if (!switchToPreviousInputMethod()) imm.showInputMethodPicker()`.
  Globe long-press: `imm.showInputMethodPicker()`.
- `status_cancel`: `controller.cancelTranscription()`. `unsent_chip`: `controller.retryPending()`. Tapping `status_text` while it shows
  NoApiKey, NoMicPermission or AuthRejected opens SettingsActivity (`FLAG_ACTIVITY_NEW_TASK`).
- `ControllerListener`: `StatusFormatter.text` goes into `status_text` (a 1 s ticker while Recording). Show `status_cancel` if `showsCancel`.
  The ring pulses while Recording (`ValueAnimator` on scale and alpha). `progress` is visible while Transcribing, Retrying or Waiting. The mic shows a Stop icon
  when `Recording(locked = true)`. `onCue` goes to `Feedback.play(FeedbackPolicy.effects(cue, settings.soundOn, settings.hapticOn))`.
- `Feedback`: tones via `ToneGenerator(AudioManager.STREAM_SYSTEM, 80)`: StartTone `TONE_PROP_BEEP`, StopTone `TONE_PROP_BEEP2`,
  RetryTone `TONE_CDMA_ALERT_CALL_GUARD` (loud, like desktop), ErrorTone `TONE_SUP_ERROR`. Haptics via
  `view.performHapticFeedback`: `CONFIRM`/`REJECT` on API 30+, with fallbacks `KEYBOARD_TAP`/`LONG_PRESS`.
- Logging: only `Logx.d { "..." }`, which is a no-op unless `BuildConfig.DEBUG`. Never log transcript text, typed text or the key in any build.

`method.xml`:

```xml
<input-method xmlns:android="http://schemas.android.com/apk/res/android"
    android:settingsActivity="net.tdobson.groqvoice.settings.SettingsActivity"
    android:supportsSwitchingToNextInputMethod="true" />
```

Manifest additions: `RECORD_AUDIO`, `INTERNET`, `ACCESS_NETWORK_STATE`, `VIBRATE`; the service:

```xml
<service android:name=".ime.VoiceKeyboardService" android:label="@string/app_name"
    android:permission="android.permission.BIND_INPUT_METHOD" android:exported="true">
    <intent-filter><action android:name="android.view.InputMethod" /></intent-filter>
    <meta-data android:name="android.view.im" android:resource="@xml/method" />
</service>
```

Layer-3 tests first (`ImeDeliveryTest`, Robolectric): `charBeforeCursor` returns the last char from a fake
`InputConnection`, and null for empty text or no connection. `commit` calls `commitText(text, 1)`. `copyToClipboard` puts the
plain text on the clipboard. `ime/EditorInfoConstantsTest` asserts each `EnterKeyResolver` constant equals the
`EditorInfo`/`InputType` constant.

Layer-4 test (`ImeServiceTest`, emulator only). Read the previous IME with `settings get secure default_input_method` through
`UiAutomation.executeShellCommand`. Run `ime enable net.tdobson.groqvoice/.ime.VoiceKeyboardService` and `ime set ...`. Launch SettingsActivity
(needs Task 3.7, so write this test in 3.7 if done in order) and click `try_it_here`. Then `UiDevice.wait(Until.hasObject(By.res("net.tdobson.groqvoice", "mic_button")), 10_000)` must be true.
Tap `key_space` twice and `key_backspace` once, and the field text must be `" "`. Long-press the mic for 300 ms (TooShort), and `status_text` must be `"Hold to talk"`.
`@After` restores the previous IME with `ime set <previous>`. Also assert that `InputMethodManager.inputMethodList` contains our component.

- [ ] Red, green, commit `feat(ime): voice keyboard service, view, delivery and feedback`

### Task 3.7: SettingsActivity with "Try it here"

**Files:** `settings/SettingsActivity.kt`, `res/layout/activity_settings.xml`, manifest launcher activity;
tests `settings/SettingsActivityTest.kt` (Robolectric, practical parts) plus `ImeServiceTest` (layer 4, above)

View ids (stable, used by Section 4): `api_key_input` (`textPassword`, masked), `save_key_button`, `test_key_button`,
`test_key_result`, `mic_permission_button`, `mic_permission_status`, `enable_keyboard_button`, `choose_keyboard_button`,
`model_spinner`, `language_input`, `sound_switch`, `haptic_switch`, `unsent_status`, `delete_unsent_button`,
`try_it_here` (multi-line `EditText`, `inputType="textMultiLine|textCapSentences"`, minLines 4, hint "Try it here").

Behaviour:
- Save: `SettingsRules.normaliseKey`. If null, show the error "Paste a valid key". Otherwise `ApiKeyStore.save` and clear the field. The field then shows the hint
  "Key saved" and never redisplays the key.
- Test key: run `GroqClient.checkKey(key)` on `lifecycleScope` with the saved key (or the typed one if not saved yet). Results in `test_key_result`:
  Valid gives `"Key works"`, Rejected gives `"Key rejected"`, NoConnection gives `"No connection"`, and `Failed(c)` gives `"Groq error $c"`.
- Mic: `registerForActivityResult(RequestPermission())`. `mic_permission_status` shows "Microphone allowed" or "Microphone not allowed". When permanently
  denied, the button opens app details settings.
- Enable keyboard: `Settings.ACTION_INPUT_METHOD_SETTINGS`. Choose keyboard: `InputMethodManager.showInputMethodPicker()`.
- Model spinner from `SettingsRules.MODELS`. Language validated on focus loss (invalid shows an error and keeps the old value). The switches
  write to `AppSettings`.
- Unsent: "Unsent recordings: N" and Delete, which runs `PendingStore.clearPending()`. The button is disabled at 0.

Robolectric tests (where practical): the activity launches and all the ids above exist. Saving a blank key shows an error and
does not call the store (inject an `ApiKeyStore`-like interface via a test seam, `SettingsActivity.keyStoreFactory`, because
Robolectric has no AndroidKeyStore). Toggling `sound_switch` persists in `AppSettings`. Invalid language input keeps "en".
"Delete" with 2 pending files leaves 0 and disables the button.

- [ ] Red, green, commit `feat(settings): settings activity with key test, permissions, shortcuts and Try it here`

### Task 3.8: Android layer verification

- [ ] `scripts/emulator.sh start`, then `scripts/verify.sh` (all layers, layer 4 ran), then `scripts/emulator.sh stop`.
- [ ] `./gradlew assembleRelease` and `apksigner verify --print-certs`. Run `unzip -p app-release.apk classes*.dex | strings | grep -c "$(printf 'gsk')_"`, which must be 0.
- [ ] Lint clean. Check `AndroidManifest` merged output (`app/build/intermediates/merged_manifests/release/...`):
  only the IME service (with BIND_INPUT_METHOD) and the launcher activity are exported, and `allowBackup="false"`.
- [ ] Commit any fixes. The summary reports the layer 1-3 test count and the layer 4 `OK (n tests)` line.

---

## Section 4: Proof on emulator (layer 5, release APK)

This section is executed by the Prove phase. It proves the signed RELEASE APK works end to end with real audio
through the emulator microphone and the real Groq API. Commands are the plan, so the proof is repeatable.

- [ ] **Step 1:** `scripts/verify.sh` with the emulator attached (all layers). Record the final line.
- [ ] **Step 2: Release APK.**

```bash
source scripts/env.sh && ./gradlew assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
"$ANDROID_HOME/build-tools/36.1.0/apksigner" verify --print-certs "$APK"
VER=$(grep -o 'versionName = "[^"]*"' app/build.gradle.kts | cut -d'"' -f2)
mkdir -p dist && cp "$APK" "dist/groq-voice-$VER.apk" && sha256sum "dist/groq-voice-$VER.apk" && stat -c %s "dist/groq-voice-$VER.apk"
git check-ignore -q dist/ && echo "dist ignored"
```

- [ ] **Step 3: Reference transcript from the host** (key never on a command line):

```bash
set -a; . /home/tdobson/dev/transcribeMe/.env; set +a        # loads GROQ_API_KEY into this shell only
curl -sS https://api.groq.com/openai/v1/audio/transcriptions \
  -H @<(printf 'Authorization: Bearer %s\n' "$GROQ_API_KEY") \
  -F file=@.local-test/sample.ogg -F model=whisper-large-v3-turbo -F language=en \
  -F temperature=0 -F response_format=text > .local-test/reference.txt
head -c 200 .local-test/reference.txt
```

- [ ] **Step 4: Route the sample into the emulator mic without touching Tim's audio devices.**

```bash
MOD=$(pactl load-module module-null-sink sink_name=groqvoice_test sink_properties=device.description=groqvoice_test)
echo "$MOD" > .local-test/null-sink-module-id
scripts/emulator.sh stop
PULSE_SOURCE=groqvoice_test.monitor EMU_EXTRA_ARGS="-allow-host-audio" scripts/emulator.sh start
```

Do not change the default sink or source. If the emulator mic stays silent, check
`/tmp/groqvoice-emulator.log`, and try `EMU_EXTRA_ARGS="-allow-host-audio -qemu -audiodev pa,id=snd0,in.name=groqvoice_test.monitor"`.

- [ ] **Step 5: Install and configure on the emulator only.**

```bash
adb -s emulator-5554 install -r "dist/groq-voice-$VER.apk"
adb -s emulator-5554 shell pm grant net.tdobson.groqvoice android.permission.RECORD_AUDIO
adb -s emulator-5554 shell ime enable net.tdobson.groqvoice/.ime.VoiceKeyboardService
adb -s emulator-5554 shell ime set net.tdobson.groqvoice/.ime.VoiceKeyboardService
adb -s emulator-5554 shell am start -n net.tdobson.groqvoice/.settings.SettingsActivity
```

Enter the key without exposing it on the host command line. Tap `api_key_input` (bounds from
`adb -s emulator-5554 exec-out uiautomator dump /dev/tty`), then feed the command to the device shell via stdin:
`printf 'input text %s\n' "$GROQ_API_KEY" | adb -s emulator-5554 shell`. Then tap `save_key_button` and
`test_key_button`, and poll the uiautomator dump until `test_key_result` reads `Key works`.

- [ ] **Step 6: Hold-to-talk proof.** Tap `try_it_here` so the keyboard shows. Read the `mic_button` bounds from the dump and take the
  centre X,Y. Then start both at once:
  `paplay --device=groqvoice_test .local-test/sample.ogg &` and
  `adb -s emulator-5554 shell input swipe X Y X Y 29000` (a 29 s press, longer than the 27 s clip).
  Poll the dump for up to 90 s until `try_it_here` has text. Save screenshots with
  `adb -s emulator-5554 exec-out screencap -p > .local-test/proof/hold.png`.
- [ ] **Step 7: Compare** the inserted text with `.local-test/reference.txt`: lowercase both and strip punctuation, then the word overlap
  should be at least 70 %. Report the first 200 chars of each.
- [ ] **Step 8: Lock proof.** Clear the field. Start `paplay` and lock with a swipe from the mic centre upward past
  the lock threshold (`input swipe X Y X $((Y-300)) 400`). Once the clip ends (about 28 s), tap `mic_button` (Stop).
  Expect new text in the field. Take a screenshot.
- [ ] **Step 9 (bad-network smoke, optional and not blocking):** put the emulator in airplane mode
  (`adb -s emulator-5554 shell cmd connectivity airplane-mode enable`), record a short clip, and check that `status_text` shows
  `Waiting for signal...`. Then disable airplane mode and the text should arrive. Also: ✕ during the wait leaves `⟳ 1 unsent`.
- [ ] **Step 10: Clean up.** `pactl unload-module "$(cat .local-test/null-sink-module-id)"`, then `scripts/emulator.sh stop`,
  then `unset GROQ_API_KEY`. Nothing from `.local-test/` or `dist/` is committed.

If real mic audio cannot reach the emulator after a genuine attempt, the fallback is a debug-build-only injection
path (a debug source set `AudioRecorder` that copies a file instead of recording). It is never in release, and the
proof must be reported as `fallback-injection`, not as a pass.

---

## Self-review against the spec

| Spec item | Where |
|---|---|
| Mic gestures (hold 500 ms, slide-up lock, slide-left cancel, too-short hint, dp thresholds in one place) | 2.7, 3.6, proof 4.6/4.8 |
| Keys: backspace with repeat, space, action-aware enter, ↺, globe tap/long-press | 2.8, 3.6 |
| Status line texts and ⟳ N unsent chip, ✕ cancel | 2.9, 2.10, 3.6 |
| Feedback: haptic and sound on start/stop, retry, error; toggles | 2.9, 3.6, 3.2 |
| Settings: key paste masked, Test key (models endpoint), mic permission, enable/picker, model, language, toggles, Try it here | 2.5 checkKey, 3.7 |
| 16 kHz mono Opus/Ogg, 60 min cap | 3.4 |
| Model, language, temperature 0, response_format text | 2.4, 2.5 multipart test |
| 10 attempts, 1 s doubling capped 60 s, connectivity wait after first failure, retry sound each retry | 2.1, 2.10 tests 6-7 |
| Retry classification table (IO, 5xx, 429 retry-after, 401/403, other 4xx, empty transcript) | 2.4, 2.10 tests 8-13, 18 |
| Connectivity: validated network, then HEAD every 3 s with 5 s timeout, cancellable | 2.5 probe, 2.6, 3.5, 2.10 test 14 |
| Timeouts: connect 10 s, call 30 s + bytes / 20 KB/s, inactivity read/write | 2.2, 2.5 slow-body tests |
| Cleanup parity (trimStart, Thank you. regex), leading space | 2.3 |
| Data flow: field identity (package, field id, session), clipboard fallback, last text, history of 2, unsent kept | 2.10 tests 3-5, 3.1, 3.6 |
| Lifecycle: service-owned scope, destroyed mid-request keeps pending | 2.10 test 29, 3.6 onDestroy |
| Security: key never bundled, Keystore AES-GCM, allowBackup false, HTTPS bearer only, private storage, no text logs in release | 0.3, 1.2, 3.3, 3.6 Logx, guards, 3.8 |
| Build: release signing outside repo, R8 on, `adb install -r` | 1.2, Section 4 |
| Out of scope (LLM mode, QWERTY, live chunking) not blocked: `TranscriptionApi` and the post-clean step in the controller are where an LLM pass would slot in | 2.4, 2.10 |
