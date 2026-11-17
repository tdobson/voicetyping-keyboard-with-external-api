package net.tdobson.groqvoice.ime

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.net.ConnectivityManager
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.tdobson.groqvoice.R
import net.tdobson.groqvoice.audio.Recorder
import net.tdobson.groqvoice.core.controller.ApiKeyProvider
import net.tdobson.groqvoice.core.controller.AudioRecorder
import net.tdobson.groqvoice.core.controller.ConnectivityGate
import net.tdobson.groqvoice.core.controller.ControllerListener
import net.tdobson.groqvoice.core.controller.Cue
import net.tdobson.groqvoice.core.controller.FeedbackPolicy
import net.tdobson.groqvoice.core.controller.FieldIdentity
import net.tdobson.groqvoice.core.controller.MicPermission
import net.tdobson.groqvoice.core.controller.Status
import net.tdobson.groqvoice.core.controller.TranscriptionController
import net.tdobson.groqvoice.core.gesture.GestureEvent
import net.tdobson.groqvoice.core.keys.EnterAction
import net.tdobson.groqvoice.core.keys.EnterKeyResolver
import net.tdobson.groqvoice.core.net.GroqClient
import net.tdobson.groqvoice.core.net.GroqReachabilityProbe
import net.tdobson.groqvoice.core.net.TranscriptionApi
import net.tdobson.groqvoice.net.AndroidConnectivityWaiter
import net.tdobson.groqvoice.security.ApiKeyStore
import net.tdobson.groqvoice.settings.AppSettings
import net.tdobson.groqvoice.settings.SettingsActivity
import net.tdobson.groqvoice.storage.AppStorage
import net.tdobson.groqvoice.util.Logx

/**
 * The keyboard. Owns the view and a main-thread coroutine scope, and wires the Android adapters into the
 * TranscriptionController. Text is only ever committed by the controller after its field-identity check.
 */
open class VoiceKeyboardService : InputMethodService(), KeyboardActions, ControllerListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var settings: AppSettings
    private lateinit var delivery: ImeDelivery
    private lateinit var controller: TranscriptionController
    private lateinit var feedback: Feedback

    private var binder: KeyboardViewBinder? = null
    private var session = 0L
    private var lastStatus: Status = Status.Idle
    private var lastPending = 0

    override fun onCreate() {
        super.onCreate()
        val store = AppStorage.pendingStore(this)
        val recovered = store.recoverInterrupted()
        if (recovered > 0) Logx.d { "recovered $recovered interrupted recording(s)" }
        settings = AppSettings.from(this)
        delivery = ImeDelivery({ currentInputConnection }, getSystemService(ClipboardManager::class.java))
        feedback = Feedback(this)
        val ports = createPorts()
        controller = TranscriptionController(
            scope = scope,
            api = ports.api,
            gate = ports.gate,
            recorder = ports.recorder,
            store = store,
            keys = ports.keys,
            requests = settings,
            mic = ports.mic,
            delivery = delivery,
            listener = this,
            clock = { SystemClock.elapsedRealtime() },
        )
        lastPending = store.pendingCount()
    }

    /** The parts that need a microphone, the Keystore or the network. */
    internal class Ports(
        val api: TranscriptionApi,
        val gate: ConnectivityGate,
        val recorder: AudioRecorder,
        val keys: ApiKeyProvider,
        val mic: MicPermission,
    )

    /** Test seam: a Robolectric subclass swaps in fakes. */
    @VisibleForTesting
    internal open fun createPorts(): Ports {
        val http = GroqClient.defaultHttpClient()
        return Ports(
            api = GroqClient(http),
            gate = AndroidConnectivityWaiter(getSystemService(ConnectivityManager::class.java), GroqReachabilityProbe(http)),
            recorder = Recorder(this),
            keys = ApiKeyStore.from(this),
            mic = MicPermission { checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
        )
    }

    /** The field a transcription stopped now would be delivered to. */
    @VisibleForTesting
    internal fun currentField(): FieldIdentity? = delivery.currentField()

    @SuppressLint("InflateParams") // the IME window supplies the parent; there is none to inflate against
    override fun onCreateInputView(): View {
        binder?.release()
        val view = layoutInflater.inflate(R.layout.keyboard, null)
        binder = KeyboardViewBinder(view, this, clock = SystemClock::elapsedRealtime).also {
            it.showStatus(lastStatus)
            it.showPendingCount(lastPending)
        }
        return view
    }

    /** Our strip is short; never take over the screen with the extract view in landscape. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        session++
        delivery.current = FieldIdentity(attribute?.packageName ?: "", attribute?.fieldId ?: 0, session)
    }

    override fun onFinishInput() {
        delivery.current = null
        super.onFinishInput()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        binder?.setEnterLabel(EnterKeyResolver.label(enterAction(info)))
        controller.refreshPendingCount()
        // Hints about the last gesture are stale in a new field; "Text ready", errors and work in flight are not.
        if (!restarting && lastStatus in TRANSIENT_HINTS) onStatus(Status.Idle)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // Keyboard hidden while recording: keep the speech and send it; the text goes to the clipboard plus ↺,
        // never typed into a field the user can no longer see the keyboard for (plan refinement 9).
        if (controller.isRecording) controller.stopAndTranscribe(intoField = false)
        binder?.resetGesture()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        // The scope dies next, so this only moves the audio to pending/ for the chip.
        if (controller.isRecording) controller.stopAndTranscribe(intoField = false)
        scope.cancel()
        binder?.release()
        binder = null
        feedback.release()
        super.onDestroy()
    }

    // KeyboardActions

    override fun onMicGesture(event: GestureEvent) {
        when (event) {
            GestureEvent.Hold -> if (!controller.startRecording()) binder?.resetGesture()
            GestureEvent.Lock -> controller.lock()
            GestureEvent.Cancel -> controller.cancelRecording()
            GestureEvent.Release -> controller.stopAndTranscribe()
            GestureEvent.TooShort -> controller.discardTooShort()
        }
    }

    override fun onBackspace() {
        keyTap()
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    override fun onSpace() {
        keyTap()
        currentInputConnection?.commitText(" ", 1)
    }

    override fun onEnter() {
        keyTap()
        val info = currentInputEditorInfo
        if (enterAction(info) == EnterAction.Newline) {
            // Like AOSP LatinIME: commit "\n" to text fields; raw key events only for TYPE_NULL fields (terminals).
            if (info == null || info.inputType == InputType.TYPE_NULL) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            } else {
                currentInputConnection?.commitText("\n", 1)
            }
        } else {
            currentInputConnection?.performEditorAction(info.imeOptions and EditorInfo.IME_MASK_ACTION)
        }
    }

    override fun onReinsert() {
        keyTap()
        controller.reinsertLast()
    }

    override fun onGlobeTap() {
        if (!switchToPreviousInputMethod()) showPicker()
    }

    override fun onGlobeLongPress() = showPicker()

    override fun onCancelTranscription() = controller.cancelTranscription()

    override fun onRetryPending() = controller.retryPending()

    override fun onStatusTap(status: Status) {
        if (status == Status.NoApiKey || status == Status.NoMicPermission || status == Status.AuthRejected) {
            openSettings()
        }
    }

    // ControllerListener

    override fun onStatus(status: Status) {
        lastStatus = status
        binder?.showStatus(status)
    }

    override fun onPendingCount(count: Int) {
        lastPending = count
        binder?.showPendingCount(count)
    }

    override fun onCue(cue: Cue) {
        feedback.play(FeedbackPolicy.effects(cue, settings.soundOn, settings.hapticOn), binder?.root)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun keyTap() {
        if (settings.hapticOn) feedback.keyTap(binder?.root)
    }

    private fun showPicker() {
        getSystemService(InputMethodManager::class.java).showInputMethodPicker()
    }

    private fun enterAction(info: EditorInfo?): EnterAction =
        if (info == null) EnterAction.Newline else EnterKeyResolver.resolve(info.imeOptions, info.inputType)

    private companion object {
        val TRANSIENT_HINTS = setOf(Status.HoldToTalk, Status.NothingHeard, Status.Cancelled)
    }
}
