# Groq Voice: Android voice-typing keyboard

An Android keyboard (input method) for Tim's phones. You hold the mic button, talk, let go, and the speech
is transcribed by Groq Whisper and typed into whatever text box has focus. It's a port of the desktop
tool `~/dev/transcribeMe/voiceTyping.js` (Win+backtick in awesome's `rc.lua`). The recording format,
Groq request settings, retry numbers and "Thank you." cleanup match that file. Sideloaded APK only, no Play Store.

- App name **Groq Voice**, package `net.tdobson.groqvoice`, minSdk 29 (Android 10), compile/target 36.
- Kotlin + Android Views (no Compose). OkHttp 5.4, coroutines. AGP 9.5.1, Kotlin 2.4.21, Gradle 9.9.1 wrapper.
- Design: `docs/superpowers/specs/2026-11-16-groq-voice-keyboard-design.md`.
  Implementation plan (more detail, plus 14 numbered "spec refinements"): `docs/superpowers/plans/2026-11-17-groq-voice-keyboard.md`.
- v1 was built on 2026-11-17 by the workflow `.claude/workflows/build-groq-voice.js`
  (plan, scaffold, TDD build, two reviewers, then proof on the emulator).

## Using it on a phone

1. Install: `adb -s <serial> install -r dist/groq-voice-<version>.apk`, or copy the APK over and open it
   (needs "install unknown apps").
2. Open the **Groq Voice** app. Paste the Groq API key, tap **Save**, then **Test key** ("Key works").
   Grant the microphone. Use the button there to turn the keyboard on in system settings.
3. In any text box, switch to Groq Voice with the nav-bar keyboard icon. Long-press 🌐 for the picker; tap 🌐 to
   go back to the previous keyboard (Gboard).

Keys: big mic, ↺ (re-insert last text), 🌐, backspace (repeats when held), space, enter (shows
Send/Go/Search when the app asks for that).

Mic gestures:
- **Hold** (500 ms or more), then release: send.
- **Slide up**: lock recording on. The button becomes Stop; tap it to send.
- **Slide left**: cancel.
- **Short tap**: shows a "hold to talk" hint.

If sending fails, the recording is kept and a "⟳ N unsent" chip appears (tap it to retry). If you've moved to
another field by the time text comes back, the text goes to the clipboard and ↺ instead of into the box.

The settings screen also has the model (`whisper-large-v3-turbo` by default, or `whisper-large-v3`), language
(default `en`), sound/vibration toggles, an unsent-recordings count, and a **Try it here** box for testing.

## Code layout (`app/src/main/java/net/tdobson/groqvoice/`)

- `core/`: **pure Kotlin, no android imports** (a guard enforces this). All decision logic lives here and is
  JVM-tested.
  - `retry/`: RetryPolicy (10 attempts, 1 s doubling to 60 s, wait for connectivity after the first
    failure) and RetryAfter.
  - `net/`: GroqClient (multipart POST to `/openai/v1/audio/transcriptions`), TimeoutPolicy (connect 10 s,
    call 30 s + bytes/20 KB/s), FailureClassifier (5xx/429/IO retry, 401/403/4xx stop), reachability probe and loop.
  - `text/TranscriptCleaner`: trimStart, strips trailing "Thank you." runs, adds a leading space when needed.
  - `gesture/MicGestureDetector`: a touch state machine with thresholds in one place.
  - `controller/TranscriptionController`: record, send with retries, clean, deliver. Talks to the platform
    only through interfaces in `Ports.kt`.
  - `keys/`, `settings/`.
- `audio/Recorder`: MediaRecorder, Ogg/Opus, 16 kHz mono, 24 kbps, 60 min cap.
- `security/ApiKeyStore`: AES-256-GCM with a non-exportable Android Keystore key; only the ciphertext is in prefs.
- `storage/PendingStore`: `recording/`, `pending/` (unsent), `history/` (last 2 successful) and `last_text.txt`.
- `net/AndroidConnectivityWaiter`: waits for a VALIDATED network, then sends a HEAD to api.groq.com every 3 s.
- `ime/VoiceKeyboardService` (the InputMethodService), `KeyboardViewBinder`, `ImeDelivery`, `Feedback`.
- `settings/SettingsActivity` is the launcher activity.
- `util/Logx`: the only allowed logger. Release builds strip verbose/debug/info so no text reaches logcat.

`allowBackup=false`. The API key is never in BuildConfig, resources or git.

## Building and testing

Always `source scripts/env.sh` first. It sets JAVA_HOME to a JDK that has `javac`:
`/usr/lib/jvm/java-21-openjdk-amd64` is a JRE only, so it falls back to `temurin-21-jdk-amd64`. It also sets
`ANDROID_SERIAL=emulator-5554` as a safety net.

| Command | What it does |
|---|---|
| `scripts/verify.sh` | Every layer, cheapest first, stops at the first failure: guards, compile+lint, core JVM tests, all JVM tests, signed release + apksigner, instrumented tests (only if the emulator is up) |
| `scripts/emulator.sh start/stop/status` | Headless `pixel_api35` emulator, always `emulator-5554` |
| `scripts/instrumented.sh [Class]` | The **only** way to run the on-device tests (installs on emulator-5554 only) |
| `scripts/guards.sh [--staged]` | Checks for a key-shaped string in tracked files, android imports in `core/`, raw `android.util.Log`, and keys in BuildConfig |
| `scripts/install-hooks.sh` | Sets `core.hooksPath=scripts/git-hooks`. The pre-commit hook runs the guards and the core JVM tests |
| `./gradlew assembleRelease` | Signed release APK in `app/build/outputs/apk/release/`. Copy it to `dist/groq-voice-<ver>.apk` (gitignored) |

Test layers (shift-left: most coverage is in the fast ones):
1. Core JVM.
2. GroqClient against OkHttp MockWebServer.
3. PendingStore and AppSettings (JVM/Robolectric).
4. Instrumented on the emulator: Keystore round-trip, Recorder output, IME service.
5. Release-APK proof on the emulator with real audio and real Groq (plan Section 4).

At v1 there were 322 JVM tests and 26 instrumented tests. **Gradle caches test results**, so a 4-second
`verify.sh` didn't actually re-run them. Use `./gradlew testDebugUnitTest --rerun` to force a run.

**Never** run `./gradlew connectedDebugAndroidTest`, `adb kill-server`/`start-server`, or adb without `-s`.
Tim's Messenger MCP phone shares the adb server, and Gradle's connected tasks install on every attached device.

### Releasing an update

Bump `versionCode` (it must go up) and `versionName` in `app/build.gradle.kts`, run `scripts/verify.sh`, then
`assembleRelease`, copy to `dist/`, and `adb -s <serial> install -r`. Updates must be signed with the
same key. The signing config comes from `GROQVOICE_STORE_FILE/STORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD` in
`~/.gradle/gradle.properties`. The keystore is `~/.android-keystores/groq-voice-release.jks`, and the cert
SHA-256 starts `dd86842c`. Both live outside the repo. If they're lost, phones need an uninstall and reinstall.

## Proving the APK on the emulator with real audio

The full recipe is in plan Section 4 under "What actually worked". The short version, because none of it is obvious:
- `-no-window` uses `qemu-system-x86_64-headless`, which has **no PulseAudio**. Run the windowed emulator on a
  private `Xvfb :87` instead. This qemu is 2.12 based, so it has no `-audiodev`.
- After every boot, `adb -s emulator-5554 emu avd hostmicon`.
- Audio route: a null sink `groqvoice_test`, then `module-remap-source groqvoice_mic` over its monitor, plus a null sink
  `groqvoice_out`. Start the emulator with `PULSE_SOURCE=groqvoice_mic PULSE_SINK=groqvoice_out -allow-host-audio`.
  Never change Tim's default sink or source, and unload the modules by id afterwards.
- `paplay` can't open the Ogg sample. Convert it to WAV with ffmpeg first. Don't set `PULSE_LATENCY_MSEC`.
- **Don't run `uiautomator dump` or `screencap` while audio is playing.** It stalls the emulator mic
  (logcat floods with "inserting 16000 us of silence") until the emulator restarts.
- Hold gesture: `adb -s emulator-5554 shell input swipe X Y X Y 29000`. Lock: `input swipe X Y X Y-300 400`.
- Enter the key without putting it on the host command line:
  `printf 'input text %s\n' "$GROQ_API_KEY" | adb -s emulator-5554 shell`.
- After a cold boot on a busy host, a SystemUI "isn't responding" dialog can make instrumented tests fail.
  Tap Wait and re-run.

Local-only test material is in `.local-test/` (gitignored): `sample.ogg` (27 s of Tim speaking, copied from
`/tmp/voicetyping-1.ogg`), `reference.txt`, and `proof/` screenshots and logs. The test key is read from
`~/dev/transcribeMe/.env` (`GROQ_API_KEY`) at runtime only.

## Known gaps and open decisions (as of v1)

- Very slow uplink: every attempt can hit the call timeout and re-upload the whole file, up to 10 times.
- The reachability probe never backs off from 3 s, and keeps going while the keyboard is hidden until ✕ or destroy.
- An input restart on the same field changes the field identity, so in-flight text goes to clipboard + ↺
  (plan refinement 14). This fails safe on purpose; whether to change it is Tim's call.
- A silent recording can come back as "you" from Whisper, and it gets inserted. Same as the desktop tool.
- No bad-network test has run on a real or emulated dropped connection (plan Section 4 Step 9 is optional and was skipped).
- Not built yet: LLM mode (desktop `--llm`), a full QWERTY layout, live chunked transcription.
