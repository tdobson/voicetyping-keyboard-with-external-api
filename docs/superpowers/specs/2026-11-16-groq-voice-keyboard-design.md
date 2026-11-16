# Groq voice keyboard for Android: design

Date: 2026-11-16
Status: approved in brainstorming, awaiting spec review

## Goal

An Android keyboard (input method) that does what `~/dev/transcribeMe/voiceTyping.js`
does on the desktop: record speech, send it to Groq Whisper, and type the result
into whatever text field has focus. It replaces Gboard's voice typing on a handful
of Tim's phones. Distributed as a sideloaded APK, never via the Play Store.

## Scope

In v1:

- Mic button with Messenger-style gestures (hold, slide-up lock, slide-left cancel).
- Essential keys: backspace, space, enter (action-aware), re-insert last text, switch keyboard.
- Groq transcription done directly from the phone with the user's own API key.
- Retry, connectivity waiting and unsent-recording recovery tuned for mobile networks.
- Settings activity: API key, mic permission, keyboard enable shortcut, model, language, feedback toggles.

Out of v1 (design must not block adding later):

- LLM mode (desktop's `--llm`, llama-3.3-70b with the humanizer prompt).
- Full QWERTY layout.
- Chunked/live transcription while locked.

## Platform

- Kotlin, Android Views (no Compose), Gradle with the Android Gradle Plugin.
- `minSdk 29` (Android 10), `targetSdk`/`compileSdk 36`.
- Dependencies kept small: OkHttp, kotlinx-coroutines, AndroidX core/appcompat. No Firebase, no analytics.
- Local SDK at `~/Android/Sdk` (symlink to `~/Videos/sys/android`); emulator AVD `pixel_api35`.

## Desktop behaviour to match

Taken from `voiceTyping.js`:

| Setting | Desktop value | Android |
|---|---|---|
| Audio format | 16 kHz mono Opus (ffmpeg) | 16 kHz mono Opus in Ogg (`MediaRecorder` `OGG`/`OPUS`, API 29+) |
| Model | `whisper-large-v3-turbo` | Same default; `whisper-large-v3` selectable |
| Language | `en` | `en` default, configurable |
| Temperature | 0.0 | 0.0 |
| Response format | `text` | `text` |
| Max recording | 60 min | 60 min |
| Retries | 10 attempts | 10 attempts |
| Backoff | 1 s doubling, capped at 60 s | Same |
| On first failure | Wait for connectivity indefinitely (HEAD api.groq.com every 3 s) | Wait for connectivity (see below), cancellable |
| Per-call timeout | 30 s | 30 s base plus size-scaled allowance (see below) |
| Retry feedback | Loud retry sound each retry | Retry sound + haptic each retry, status text "Retrying (n/10)" |
| Cleanup | `trimStart`; strip trailing `Thank you.` runs | Same regex |
| Re-paste last | `last_voice_typing_text` | "↺" key re-inserts last text |
| Audio history | Last 2 successful recordings kept | Same, in app-private storage |

## User interface

### Keyboard view

One strip, roughly normal keyboard height:

- Centre: large round mic button.
- Backspace (auto-repeat on hold), space, enter.
- Enter reflects `EditorInfo.imeOptions` (Send / Go / Search / Done / newline).
- "↺" re-inserts the last transcribed text.
- Globe key: `switchToPreviousInputMethod()` on tap; system IME picker on long-press.
- Status line above the mic: idle hint, "Recording 0:12", "Transcribing...", "Retrying (3/10)...",
  "Waiting for signal...", errors.
- "⟳ N unsent" chip when unsent recordings exist.

### Mic gestures

| Gesture | Result |
|---|---|
| Press and hold (≥ 500 ms) then release | Stop and transcribe |
| Press, slide up past threshold | Lock recording on; button becomes Stop; tap Stop to transcribe |
| Press, slide left past threshold | Cancel; discard audio |
| Tap shorter than 500 ms | No recording kept; show "Hold to talk" hint |

Thresholds are in dp and live in one place in `MicGestureDetector`.

### Feedback

Haptic plus short sound on record start and stop; pulsing red ring while recording; spinner while
transcribing; distinct error sound and haptic on failure. Sounds and haptics can each be turned off
in settings.

### Settings activity (launcher icon)

- API key field (paste), masked, with "Test key" (calls Groq's models list endpoint).
- Microphone permission request (an IME cannot request runtime permissions itself).
- Button to the system "manage keyboards" screen, and to the IME picker.
- Model picker, language field, sound toggle, haptic toggle.

## Architecture

Small units, each testable alone:

| Unit | Responsibility | Android-free? |
|---|---|---|
| `RetryPolicy` | Attempt count, backoff schedule, which errors retry | Yes |
| `TimeoutPolicy` | Connect and call timeouts from file size | Yes |
| `TranscriptCleaner` | Trim, strip `Thank you.` hallucinations, decide leading space | Yes |
| `GroqClient` | Multipart POST to `https://api.groq.com/openai/v1/audio/transcriptions`; maps HTTP results to typed outcomes | Yes (OkHttp only) |
| `ConnectivityWaiter` | Suspends until network is validated and Groq is reachable | No |
| `Recorder` | Start/stop/cancel `MediaRecorder` to a file | No |
| `ApiKeyStore` | Encrypt/decrypt the key with an Android Keystore AES-GCM key; ciphertext in SharedPreferences | No |
| `PendingStore` | Unsent recordings on disk, last text, audio history rotation | Mostly |
| `MicGestureDetector` | Touch events to `Hold`, `Lock`, `Cancel`, `Release`, `TooShort` | Yes (pure state machine fed with coordinates/times) |
| `TranscriptionController` | Orchestrates record → send with retries → clean → deliver | Yes (depends on interfaces) |
| `VoiceKeyboardService` | `InputMethodService`; owns the view, wires units, commits text | No |
| `SettingsActivity` | Settings UI | No |

`TranscriptionController` depends on interfaces for the client, connectivity waiter, clock and
stores, so its whole retry and error flow is unit-tested without a device.

## Data flow

1. Gesture starts recording: `Recorder` writes `pending/<timestamp>.ogg`.
2. Release or Stop: recorder stops; file is validated (exists, > 1 KB).
3. Controller captures the target field identity (`EditorInfo` package name + field id + a session counter
   bumped on every `onStartInput`).
4. Controller sends via `GroqClient` under `RetryPolicy` and `TimeoutPolicy`.
5. Success: text goes through `TranscriptCleaner`; stored as last text; audio moved to history.
6. Delivery: if the same field is still active, `commitText` (with a leading space if the character
   before the cursor is not whitespace). If the field changed, copy to clipboard and show
   "Text ready, tap ↺ to insert".
7. Failure after all retries, or non-retryable error: audio stays in `pending/`; chip shows count.

## Network and error handling

### Retry classification

| Outcome | Action |
|---|---|
| IOException, timeout, DNS failure | Retry |
| HTTP 5xx | Retry |
| HTTP 429 | Retry; wait `retry-after` header if present, else the normal backoff |
| HTTP 401 / 403 | Stop; "API key rejected, open settings"; keep audio |
| HTTP 400 / 413 / other 4xx | Stop; show Groq's error message; keep audio |
| Empty transcript or only `Thank you.` | Treat as "nothing heard"; short error haptic; insert nothing; delete audio |

### Waiting for connectivity

After the first failed attempt (matching desktop), `ConnectivityWaiter`:

1. Uses `ConnectivityManager.registerDefaultNetworkCallback` and waits for a network with
   `NET_CAPABILITY_VALIDATED`.
2. Then confirms reachability with a HEAD to `api.groq.com` (5 s timeout), repeating every 3 s if it fails.
3. Is cancellable from the keyboard (✕ on the status line); cancel leaves the recording in `pending/`.

### Timeouts

- Connect timeout: 10 s.
- Call timeout: `30 s + fileBytes / 20 KB per second`.
- OkHttp write/read timeouts are inactivity timeouts, so a slow but progressing upload is not cut off.

### Lifecycle

Work runs in a coroutine scope owned by the IME service. If the service is destroyed mid-request,
the recording is already in `pending/` and can be retried from the chip. No background service or
WorkManager in v1.

## Security

- The API key is entered by the user in the settings activity; it is never bundled in the APK.
- Stored as AES-GCM ciphertext; the key-encryption key lives in Android Keystore and is non-exportable.
- App has `android:allowBackup="false"` so the ciphertext and recordings are not cloud-backed-up.
- The key is sent only as the `Authorization: Bearer` header to `api.groq.com` over HTTPS.
- Recordings and history live in app-private storage only.
- The keyboard does not log typed or transcribed text to logcat in release builds.

## Testing

- JVM unit tests: `RetryPolicy`, `TimeoutPolicy`, `TranscriptCleaner`, `MicGestureDetector`,
  `TranscriptionController` (with fakes for client, waiter, clock, stores).
- `GroqClient` against OkHttp `MockWebServer`: 200 text, 401, 429 with `retry-after`, 500, slow body,
  dropped connection.
- Emulator (`pixel_api35`): enable the IME, record via the emulator's virtual mic, insert into a
  test field; use emulator network controls (`network speed`, `network delay`, airplane mode) to
  exercise waiting and retry.
- Real phones: install the release APK with `adb install -r`.

## Build and distribution

- Release builds signed with a local keystore kept outside the repo (path and passwords in
  `~/.gradle/gradle.properties`, never committed).
- R8 minification on release.
- Install with `adb install -r app-release.apk`. Updates keep the same signing key.
