export const meta = {
  name: 'build-groq-voice',
  description: 'Plan, build, test, review and prove the Groq Voice Android keyboard APK from the approved spec',
  whenToUse: 'Scheduled 5am build of ~/dev/android-groq-keyboard from docs/superpowers/specs/2026-11-16-groq-voice-keyboard-design.md',
  phases: [
    { title: 'Plan', detail: 'implementation plan from the spec' },
    { title: 'Scaffold', detail: 'Gradle project, signing, test harness, verify.sh' },
    { title: 'Build', detail: 'core logic test-first, then Android layer' },
    { title: 'Review', detail: 'spec/correctness and security/network reviewers, then fixes' },
    { title: 'Prove', detail: 'signed release APK proven on emulator with real audio and real Groq' },
    { title: 'Phone', detail: 'install release APK on the Messenger MCP phone under its device lock' },
  ],
}

const REPO = '/home/tdobson/dev/android-groq-keyboard'
const SPEC = `${REPO}/docs/superpowers/specs/2026-11-16-groq-voice-keyboard-design.md`

const CONTEXT = `
PROJECT CONTEXT (applies to every step)
- Repo: ${REPO}. Work on git branch feat/v1-keyboard (create it from main if it does not exist; never push, there is no remote). Commit after each coherent piece of work with a clear message.
- Approved design spec: ${SPEC}. Read it first. It is the source of truth. The plan (docs/superpowers/plans/) refines it once written.
- Reference implementation being ported: /home/tdobson/dev/transcribeMe/voiceTyping.js (Groq Whisper desktop voice typing). Match its retry, cleanup and request settings as the spec says.
- App name "Groq Voice", applicationId and namespace net.tdobson.groqvoice. Kotlin, Android Views (no Compose), minSdk 29, compileSdk and targetSdk 36.
- Spec addition agreed with Tim: the settings activity has a "Try it here" multi-line EditText so the keyboard can be tried (and tested) without another app.
- Toolchain: JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64, ANDROID_HOME=/home/tdobson/Android/Sdk (platform android-36, build-tools 36.1.0, emulator, cmdline-tools/latest). Install extra SDK packages only with $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager. There is no system gradle: use the Gradle wrapper (./gradlew). Use current stable AGP, Kotlin and Gradle versions that support compileSdk 36; check maven.google.com / services.gradle.org rather than guessing.
- Emulator: AVD pixel_api35 (Android 15, x86_64, KVM available). Always address it as "adb -s emulator-5554". NEVER run "adb kill-server", "adb start-server", "adb connect/disconnect", or any adb command without "-s <serial>": Tim's Messenger MCP phone shares this adb server.
- Groq API key: GROQ_API_KEY in /home/tdobson/dev/transcribeMe/.env. Read it at runtime only. Never print it, never write it into any tracked file, BuildConfig, resource, log or commit. Gitignored locations only (.local-test/ is gitignored). Before every commit run: git diff --cached | grep -c gsk_ must print 0.
- Sample recording of Tim speaking: ${REPO}/.local-test/sample.ogg (27 s, 16 kHz mono Opus). Gitignored, never commit it.
- Release signing: keystore at /home/tdobson/.android-keystores/groq-voice-release.jks (create with keytool if missing, RSA 4096, 30 year validity, random strong passwords). Store path, alias and passwords in /home/tdobson/.gradle/gradle.properties as GROQVOICE_STORE_FILE, GROQVOICE_STORE_PASSWORD, GROQVOICE_KEY_ALIAS, GROQVOICE_KEY_PASSWORD. Never in the repo. If these already exist, reuse them, never regenerate (updates must keep the same key).
- Testing is shift-left: write the test first for every decision-making unit (superpowers:test-driven-development), keep the fast JVM layer large and the slow device layer thin. scripts/verify.sh runs the layers cheapest first and fails fast. Never weaken, skip or delete a test to get green; fix the code.
- Be honest in your returned summary: say exactly what passed, what failed and what was not attempted.
`

const STEP = {
  type: 'object',
  properties: {
    ok: { type: 'boolean', description: 'true only if this step fully succeeded and its verification command passed' },
    summary: { type: 'string' },
    verification: { type: 'string', description: 'the exact commands run to verify and their pass/fail result' },
    problems: { type: 'array', items: { type: 'string' } },
  },
  required: ['ok', 'summary', 'verification', 'problems'],
}

const FINDINGS = {
  type: 'object',
  properties: {
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          severity: { type: 'string', enum: ['high', 'medium', 'low'] },
          file: { type: 'string' },
          issue: { type: 'string' },
          evidence: { type: 'string' },
          fix: { type: 'string' },
        },
        required: ['severity', 'file', 'issue', 'evidence', 'fix'],
      },
    },
  },
  required: ['findings'],
}

const PROOF = {
  type: 'object',
  properties: {
    ok: { type: 'boolean', description: 'true only if the signed release APK was installed on the emulator and real transcribed speech appeared in the text box via the mic gesture' },
    apkPath: { type: 'string' },
    sha256: { type: 'string' },
    sizeBytes: { type: 'number' },
    versionName: { type: 'string' },
    signatureVerified: { type: 'boolean' },
    proofLevel: { type: 'string', enum: ['real-mic-audio', 'fallback-injection', 'install-only', 'none'] },
    insertedText: { type: 'string', description: 'first ~200 chars of text found in the Try it here box' },
    referenceText: { type: 'string', description: 'first ~200 chars of the host-side Groq transcription of sample.ogg' },
    verifyShResult: { type: 'string' },
    evidence: { type: 'string', description: 'screenshot paths, logcat excerpts, commands run' },
    problems: { type: 'array', items: { type: 'string' } },
  },
  required: ['ok', 'apkPath', 'signatureVerified', 'proofLevel', 'verifyShResult', 'evidence', 'problems'],
}

const results = {}

// ---------- Plan ----------
phase('Plan')
results.plan = await agent(`${CONTEXT}
TASK: Write the implementation plan for the spec using the superpowers:writing-plans skill. Save it to ${REPO}/docs/superpowers/plans/2026-11-17-groq-voice-keyboard.md and commit it on feat/v1-keyboard.
The plan is executed by later automated steps in this order, so structure it with these exact sections: (1) Scaffold, (2) Core logic (pure Kotlin, JVM tests), (3) Android layer, (4) Proof on emulator. For each unit in the spec give file paths, public interface, and the tests to write first.
The test strategy must be explicit and shift-left:
- Layer 1 JVM unit tests (bulk of coverage): RetryPolicy, TimeoutPolicy, TranscriptCleaner, MicGestureDetector state machine, error classification, TranscriptionController with fakes (including field-changed delivery, cancel, unsent recording kept, 401 stops immediately, 429 honours retry-after, connectivity wait cancel).
- Layer 2 GroqClient against OkHttp MockWebServer (200 text, 401, 429 retry-after, 500, slow body, dropped connection, multipart shape: file, model, language, temperature, response_format).
- Layer 3 Robolectric or JVM tests for PendingStore and settings logic where practical.
- Layer 4 instrumented tests on the emulator (ApiKeyStore real Keystore round-trip, Recorder produces a valid Ogg, IME service starts).
- Layer 5 release APK proof on the emulator (see Prove phase).
- scripts/verify.sh: lint/compile, layer 1-3, then layer 4 only if an emulator is attached; fail fast; and a tracked git pre-commit hook (installed via a scripts/install-hooks.sh) that runs layer 1-2 in under a minute.
Do not write production code in this step.`, { label: 'write-plan', phase: 'Plan', schema: STEP })
log(`Plan: ${results.plan?.ok ? 'written' : 'PROBLEM'} - ${results.plan?.summary ?? 'no result'}`)

// ---------- Scaffold ----------
phase('Scaffold')
results.scaffold = await agent(`${CONTEXT}
TASK: Execute the Scaffold section of the plan in docs/superpowers/plans/.
- Bootstrap the Gradle wrapper (download a Gradle distribution into /tmp/gradle-bootstrap, verify its sha256 from services.gradle.org, run "gradle wrapper"), then use only ./gradlew. Commit gradlew, gradlew.bat and gradle/wrapper/*.
- Single :app module, version catalog (gradle/libs.versions.toml), local.properties with sdk.dir (gitignored).
- Release signing config reading GROQVOICE_* Gradle properties (create the keystore and properties as described in context if missing). Release: R8 minify on, allowBackup false. Debug build stays unsigned-by-release-key.
- Test harness wired: JUnit, kotlinx-coroutines-test, OkHttp MockWebServer, Robolectric if the plan uses it, AndroidX test runner for instrumented tests.
- scripts/verify.sh and scripts/install-hooks.sh plus the tracked hook, per the plan. Run install-hooks.sh.
- A placeholder test in each layer so the harness is proven.
VERIFY before returning: ./gradlew assembleDebug assembleRelease testDebugUnitTest lint all pass, scripts/verify.sh passes (no emulator attached is fine), and apksigner verify --print-certs on the release APK succeeds.`, { label: 'scaffold', phase: 'Scaffold', schema: STEP })
log(`Scaffold: ${results.scaffold?.ok ? 'ok' : 'PROBLEM'} - ${results.scaffold?.summary ?? 'no result'}`)

// ---------- Build ----------
phase('Build')
results.core = await agent(`${CONTEXT}
TASK: Execute the Core logic section of the plan, strictly test-first (red, green, refactor, commit) using superpowers:test-driven-development.
Units: RetryPolicy (10 attempts, 1 s doubling backoff capped at 60 s, wait-for-connectivity after first failure), TimeoutPolicy (connect 10 s, call 30 s + bytes/20 KB per s), TranscriptCleaner (trimStart, strip trailing "Thank you." runs exactly like voiceTyping.js, leading-space decision from the character before the cursor), error classification table from the spec, MicGestureDetector (500 ms hold threshold, slide-up lock, slide-left cancel, dp thresholds in one place, pure state machine fed coordinates and timestamps), GroqClient (OkHttp multipart to https://api.groq.com/openai/v1/audio/transcriptions, model/language/temperature 0/response_format text, typed outcomes, retry-after parsing), and TranscriptionController depending only on interfaces (client, connectivity waiter, clock, stores, delivery target).
These must be Android-free (plain Kotlin) so they run as fast JVM tests.
VERIFY before returning: scripts/verify.sh passes and the unit test count is reported in your summary.`, { label: 'core-logic', phase: 'Build', schema: STEP })
log(`Core: ${results.core?.ok ? 'ok' : 'PROBLEM'} - ${results.core?.summary ?? 'no result'}`)

results.android = await agent(`${CONTEXT}
TASK: Execute the Android layer section of the plan, test-first where a test is practical.
Units: Recorder (MediaRecorder OGG/OPUS 16 kHz mono, 60 min cap, cancel deletes file), ApiKeyStore (Android Keystore AES-GCM, ciphertext in SharedPreferences), PendingStore (pending/ dir, last text, 2-file history rotation), ConnectivityWaiter (default network callback waiting for VALIDATED, then HEAD api.groq.com every 3 s, cancellable), VoiceKeyboardService (InputMethodService, view per spec: mic, backspace with repeat, space, action-aware enter, re-insert last text, globe switch / long-press picker, status line, unsent chip, haptics and sounds with toggles, field-identity check before commitText, clipboard fallback), SettingsActivity (API key paste + Test key, mic permission request, enable-keyboard and picker shortcuts, model, language, sound and haptic toggles, and the "Try it here" EditText), manifest (BIND_INPUT_METHOD service, method.xml, RECORD_AUDIO, INTERNET, ACCESS_NETWORK_STATE, VIBRATE).
Release builds must not log transcribed or typed text.
Instrumented tests (layer 4) for ApiKeyStore round-trip, Recorder output validity and IME service start. Run them on the emulator: start it headless with
  $ANDROID_HOME/emulator/emulator -avd pixel_api35 -no-window -no-snapshot-save -no-boot-anim -gpu swiftshader_indirect &
wait for "adb -s emulator-5554 shell getprop sys.boot_completed" to print 1, run ./gradlew connectedDebugAndroidTest, then shut it down with "adb -s emulator-5554 emu kill".
VERIFY before returning: scripts/verify.sh passes with the emulator attached (all layers), and assembleRelease produces a signed APK.`, { label: 'android-layer', phase: 'Build', schema: STEP })
log(`Android layer: ${results.android?.ok ? 'ok' : 'PROBLEM'} - ${results.android?.summary ?? 'no result'}`)

// ---------- Review ----------
// Barrier is deliberate: the fixer needs both reviewers' findings together to dedupe and avoid conflicting edits.
phase('Review')
const REVIEW_BASE = `${CONTEXT}
TASK: Review the code on branch feat/v1-keyboard (git diff main...feat/v1-keyboard) against the spec. Read the code itself, do not trust commit messages. Only report findings you can point to concrete evidence for (file, code, and the scenario that breaks). Do not edit files.`
const reviews = await parallel([
  () => agent(`${REVIEW_BASE}
LENS: spec fidelity and correctness. Gestures, retry and timeout numbers vs voiceTyping.js, cleanup regex parity, delivery to the right field, unsent recordings never lost, IME lifecycle (onStartInput/onFinishInput/onDestroy) and coroutine cancellation, test gaps where a decision-making path has no test.`, { label: 'review:correctness', phase: 'Review', schema: FINDINGS }),
  () => agent(`${REVIEW_BASE}
LENS: security and bad-network behaviour. API key never logged, never in BuildConfig/resources/commits (grep history for gsk_), Keystore usage, allowBackup, exported components, text not logged in release, behaviour on flaky mobile data (timeouts, retry storms, waiting forever, battery), 429/401 handling, permission flow.`, { label: 'review:security-network', phase: 'Review', schema: FINDINGS }),
])
const findings = reviews.filter(Boolean).flatMap(r => r.findings)
const actionable = findings.filter(f => f.severity !== 'low')
log(`Review: ${findings.length} findings, ${actionable.length} high/medium`)
if (actionable.length) {
  results.fixes = await agent(`${CONTEXT}
TASK: Address these review findings. For each: verify it is real by reading the code; if real, write a failing test first where practical, fix, commit. If you judge a finding wrong, say why in problems. Low-severity items are listed for information; fix them only if trivial.
FINDINGS:
${JSON.stringify(findings, null, 2)}
VERIFY before returning: scripts/verify.sh passes (start the emulator as in the plan for the instrumented layer, then shut it down with "adb -s emulator-5554 emu kill").`, { label: 'fix-review', phase: 'Review', schema: STEP })
  log(`Fixes: ${results.fixes?.ok ? 'ok' : 'PROBLEM'} - ${results.fixes?.summary ?? 'no result'}`)
} else {
  log('Review: nothing high/medium to fix')
}

// ---------- Prove ----------
phase('Prove')
const PROVE_PROMPT = (attempt) => `${CONTEXT}
TASK: Prove that the signed RELEASE APK works, end to end, on the emulator. The deliverable of this whole build is a working APK. Attempt ${attempt}.
1. Run scripts/verify.sh (all layers, with emulator). Record the result.
2. ./gradlew assembleRelease. Run "$ANDROID_HOME/build-tools/36.1.0/apksigner verify --print-certs" on it. Copy it to ${REPO}/dist/groq-voice-<versionName>.apk (make sure dist/ is gitignored). Record sha256 and size.
3. Reference transcript: send .local-test/sample.ogg to Groq from the host with curl (same model/language/temperature/response_format as the app, key from the .env, never echoed).
4. Route the sample into the emulator mic without touching Tim's real audio devices:
   pactl load-module module-null-sink sink_name=groqvoice_test sink_properties=device.description=groqvoice_test  (note the returned module id)
   Start the emulator with PULSE_SOURCE=groqvoice_test.monitor in its environment and -allow-host-audio, headless as in the plan (fresh wipe not required). Do not change the default sink or source.
5. adb -s emulator-5554 install -r the release APK. Grant RECORD_AUDIO with pm grant. Enable and select the IME with "ime enable" and "ime set" (emulator only). Store the API key in the app WITHOUT putting it on a command line visible in logs if you can (for example: open SettingsActivity, focus the key field, use "input text" from a script that reads the .env; then press Test key and confirm success on screen via uiautomator dump).
6. Open SettingsActivity, tap into "Try it here" so the keyboard shows. Find the mic button bounds via uiautomator dump. Then simultaneously: start "paplay --device=groqvoice_test .local-test/sample.ogg" and hold the mic with "adb -s emulator-5554 shell input swipe X Y X Y 29000" (a long press for the clip length). After release, poll uiautomator dump for up to 90 s until the Try it here box contains text.
7. Compare inserted text with the reference transcript (they should share most words; Whisper can differ slightly). Take screenshots (adb -s emulator-5554 exec-out screencap -p) into .local-test/proof/ as evidence.
8. Also prove the hands-free path: slide-up lock gesture (input swipe from mic upward past the lock threshold, then tap Stop when the clip ends) inserts text again.
9. Clean up: unload the null-sink module by its id, "adb -s emulator-5554 emu kill".
If real mic audio cannot reach the emulator after genuinely trying (check emulator logs, try -qemu -audiodev pa options if needed), fall back to a debug-build-only injection path, prove that, still install-check the release APK, set proofLevel accordingly and explain in problems. If the APK does not work, debug it (superpowers:systematic-debugging), fix it with a test, commit, and redo the proof. ok=true only for proofLevel real-mic-audio with matching text.`
results.proof = await agent(PROVE_PROMPT(1), { label: 'prove-release-apk', phase: 'Prove', schema: PROOF })
if (!results.proof?.ok) {
  log(`Proof attempt 1 failed: ${(results.proof?.problems ?? ['no result']).join('; ')}`)
  results.proof2 = await agent(`${PROVE_PROMPT(2)}
A previous attempt failed with these problems; diagnose root causes before retrying:
${JSON.stringify(results.proof ?? 'agent returned nothing', null, 2)}`, { label: 'prove-release-apk-retry', phase: 'Prove', schema: PROOF })
}
const proof = results.proof2 ?? results.proof
log(`Proof: ok=${proof?.ok} level=${proof?.proofLevel} apk=${proof?.apkPath}`)

// ---------- Phone ----------
phase('Phone')
if (proof?.apkPath && proof?.signatureVerified) {
  results.phone = await agent(`${CONTEXT}
TASK: Install the proven release APK ${proof.apkPath} on Tim's Messenger MCP phone. Tim approved installing on this phone. This phone is shared with the fb-messenger MCP server, so be careful:
- Serial: read /home/tdobson/dev/fb-messenger-mcp/.active_serial. Check "adb devices" lists it as "device". If it is not connected, do NOT run adb connect; report skipped.
- Device lock: read /home/tdobson/dev/fb-messenger-mcp/src/utils/async-lock.ts and acquire /tmp/fb-messenger-device.lock using the SAME protocol (JSON {pid, createdAt} written to a temp file then hard-linked into place, pid of a live holder process you own, createdAt refreshed via temp-file-and-rename every 30 s while held). If the lock is held, wait and poll for up to 15 minutes, then report skipped. Never delete, overwrite or bypass a lock you do not own. Release it (only if its pid is yours) as soon as you finish, also on failure (use a trap).
- While holding it, run only: adb -s <serial> install -r <apk>, then adb -s <serial> shell pm list packages net.tdobson.groqvoice and dumpsys package net.tdobson.groqvoice | grep versionName. Nothing else.
- Do NOT enable or select the keyboard, do NOT change IME settings (the MCP depends on its current keyboard), do NOT launch any app, do NOT touch Messenger or send anything.`, { label: 'install-phone', phase: 'Phone', schema: STEP })
  log(`Phone: ${results.phone?.ok ? 'installed' : 'not installed'} - ${results.phone?.summary ?? 'no result'}`)
} else {
  log('Phone: skipped, no verified release APK')
}

return {
  apk: proof?.apkPath ?? null,
  proven: proof?.ok ?? false,
  proof,
  steps: Object.fromEntries(Object.entries(results).map(([k, v]) => [k, v ? { ok: v.ok, summary: v.summary, problems: v.problems } : null])),
  reviewFindings: findings,
}
