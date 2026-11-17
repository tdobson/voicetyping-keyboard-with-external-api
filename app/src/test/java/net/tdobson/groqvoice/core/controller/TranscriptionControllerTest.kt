package net.tdobson.groqvoice.core.controller

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.tdobson.groqvoice.core.net.TranscriptionRequest
import net.tdobson.groqvoice.core.net.TranscriptionResult
import net.tdobson.groqvoice.core.net.TranscriptionResult.HttpError
import net.tdobson.groqvoice.core.net.TranscriptionResult.NetworkError
import net.tdobson.groqvoice.core.net.TranscriptionResult.Success
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionControllerTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var store: FakeStore
    private val gate = FakeGate()
    private val recorder = FakeRecorder()
    private val listener = RecordingListener()
    private var apiKey: String? = KEY
    private var micGranted = true
    private var request = TranscriptionRequest()

    @Before fun setUp() {
        store = FakeStore(tmp.newFolder("store"))
    }

    /**
     * The test dispatcher with a detached Job: runTest's advanceUntilIdle runs it (it skips backgroundScope work),
     * and a job left waiting on purpose cannot hang the end of the test.
     */
    private fun TestScope.serviceScope() = CoroutineScope(coroutineContext + Job())

    private fun TestScope.controller(
        api: FakeApi,
        delivery: FakeDelivery = FakeDelivery(),
        scope: CoroutineScope = serviceScope(),
    ) = TranscriptionController(
        scope = scope,
        api = api,
        gate = gate,
        recorder = recorder,
        store = store,
        keys = { apiKey },
        requests = { request },
        mic = { micGranted },
        delivery = delivery,
        listener = listener,
        clock = { testScheduler.currentTime },
    )

    private fun TestScope.api(vararg results: TranscriptionResult) = FakeApi(testScheduler, *results)

    private fun TranscriptionController.recordAndStop() {
        assertTrue(startRecording())
        stopAndTranscribe()
    }

    private val network get() = NetworkError(IOException("offline"))

    // 1
    @Test fun `happy path commits cleaned text with leading space into the same field`() = runTest {
        val api = api(Success(" Hello there. Thank you."))
        val delivery = FakeDelivery(field = F1, before = 'x')
        val c = controller(api = api, delivery = delivery)
        assertTrue(c.startRecording())
        c.stopAndTranscribe()
        advanceUntilIdle()
        assertEquals(listOf(" Hello there."), delivery.committed)
        assertEquals("Hello there.", store.lastText)
        assertEquals(1, store.history.size)
        assertTrue(store.pending().isEmpty())
        assertEquals(listOf(Cue.RecordStart, Cue.RecordStop), listener.cues)
        assertEquals(KEY, api.calls.single().second)
        assertEquals(Status.Idle, listener.statuses.last())
        assertEquals(0, listener.pendingCounts.last())
    }

    @Test fun `the request settings are sent with the recording`() = runTest {
        request = TranscriptionRequest(model = "whisper-large-v3", language = "de")
        val api = api(Success("Hallo"))
        controller(api).recordAndStop()
        advanceUntilIdle()
        assertEquals(request, api.requests.single())
        assertTrue(api.fileExistedAtCall.single())
    }

    // 2
    @Test fun `no leading space after whitespace or at start of field`() = runTest {
        val delivery = FakeDelivery(before = ' ')
        val c = controller(api(Success("one")), delivery)
        c.recordAndStop()
        advanceUntilIdle()
        delivery.before = null
        c.recordAndStop()
        advanceUntilIdle()
        assertEquals(listOf("one", "one"), delivery.committed)
    }

    // 3
    @Test fun `field changed during transcription copies to clipboard and shows TextReady`() = runTest {
        val delivery = FakeDelivery(field = F1)
        controller(api(Success("Hello")), delivery).recordAndStop()
        delivery.field = F2
        advanceUntilIdle()
        assertTrue(delivery.committed.isEmpty())
        assertEquals("Hello", delivery.clipboard)
        assertEquals("Hello", store.lastText)
        assertEquals(Status.TextReady, listener.statuses.last())
    }

    // 4
    @Test fun `field gone during transcription copies to clipboard`() = runTest {
        val delivery = FakeDelivery(field = F1)
        controller(api(Success("Hello")), delivery).recordAndStop()
        delivery.field = null
        advanceUntilIdle()
        assertTrue(delivery.committed.isEmpty())
        assertEquals("Hello", delivery.clipboard)
        assertEquals(Status.TextReady, listener.statuses.last())
    }

    @Test fun `no field when recording stopped copies to clipboard even if a field appears later`() = runTest {
        val delivery = FakeDelivery(field = null)
        controller(api(Success("Hello")), delivery).recordAndStop()
        delivery.field = F1
        advanceUntilIdle()
        assertTrue(delivery.committed.isEmpty())
        assertEquals("Hello", delivery.clipboard)
    }

    // 5
    @Test fun `same package and field id but new session counts as changed`() = runTest {
        val delivery = FakeDelivery(field = F1)
        controller(api(Success("Hello")), delivery).recordAndStop()
        delivery.field = F1.copy(session = 2)
        advanceUntilIdle()
        assertTrue(delivery.committed.isEmpty())
        assertEquals("Hello", delivery.clipboard)
    }

    // 6
    @Test fun `retries IO failures then succeeds, waiting for connectivity once after first failure`() = runTest {
        val api = api(network, network, Success("Hi"))
        val delivery = FakeDelivery()
        controller(api, delivery).recordAndStop()
        advanceUntilIdle()
        assertEquals(3, api.calls.size)
        assertEquals(1, gate.calls)
        assertEquals(listOf(0L, 0L, 2_000L), api.callTimes)
        assertTrue(listener.statuses.contains(Status.WaitingForSignal))
        assertTrue(listener.statuses.contains(Status.Retrying(2, 10)))
        assertTrue(listener.statuses.contains(Status.Retrying(3, 10)))
        assertEquals(2, listener.cues.count { it == Cue.Retry })
        assertEquals(listOf(" Hi"), delivery.committed)
    }

    // 7
    @Test fun `gives up after ten attempts keeping the recording unsent`() = runTest {
        val api = api(network)
        val delivery = FakeDelivery()
        controller(api, delivery).recordAndStop()
        advanceUntilIdle()
        assertEquals(10, api.calls.size)
        assertEquals(242_000L, currentTime)
        assertEquals(Status.GaveUp(10), listener.statuses.last())
        assertEquals(9, listener.cues.count { it == Cue.Retry })
        assertEquals(Cue.Error, listener.cues.last())
        assertEquals(1, listener.cues.count { it == Cue.Error })
        assertEquals(1, store.pending().size)
        assertEquals(1, listener.pendingCounts.last())
        assertTrue(delivery.committed.isEmpty())
        assertNull(store.lastText)
    }

    // 8
    @Test fun `401 stops immediately without retry or connectivity wait and keeps audio`() = runTest {
        val api = api(HttpError(401, "Invalid API Key", null))
        controller(api).recordAndStop()
        advanceUntilIdle()
        assertEquals(1, api.calls.size)
        assertEquals(0, gate.calls)
        assertEquals(Status.AuthRejected, listener.statuses.last())
        assertEquals(Cue.Error, listener.cues.last())
        assertEquals(1, store.pending().size)
    }

    // 9
    @Test fun `403 is treated like 401`() = runTest {
        val api = api(HttpError(403, null, null))
        controller(api).recordAndStop()
        advanceUntilIdle()
        assertEquals(1, api.calls.size)
        assertEquals(0, gate.calls)
        assertEquals(Status.AuthRejected, listener.statuses.last())
        assertEquals(1, store.pending().size)
    }

    // 10
    @Test fun `400 shows Groq message, no retry, audio kept`() = runTest {
        val api = api(HttpError(400, "file too short", null))
        controller(api).recordAndStop()
        advanceUntilIdle()
        assertEquals(1, api.calls.size)
        assertEquals(Status.Rejected("file too short"), listener.statuses.last())
        assertEquals(Cue.Error, listener.cues.last())
        assertEquals(1, store.pending().size)
    }

    // 11
    @Test fun `429 honours retry-after`() = runTest {
        val api = api(HttpError(429, "Rate limit", 5_000), Success("ok"))
        controller(api).recordAndStop()
        advanceTimeBy(4_999)
        assertEquals(1, api.calls.size)
        assertEquals(1, gate.calls)
        advanceTimeBy(2)
        assertEquals(2, api.calls.size)
    }

    // 12
    @Test fun `429 without retry-after on a later attempt uses normal backoff`() = runTest {
        val api = api(network, HttpError(429, null, null), Success("ok"))
        controller(api).recordAndStop()
        advanceUntilIdle()
        assertEquals(listOf(0L, 0L, 2_000L), api.callTimes)
    }

    // 13
    @Test fun `500 is retried`() = runTest {
        val api = api(HttpError(500, "oops", null), Success("ok"))
        val delivery = FakeDelivery()
        controller(api, delivery).recordAndStop()
        advanceUntilIdle()
        assertEquals(2, api.calls.size)
        assertEquals(listOf(" ok"), delivery.committed)
    }

    // 14
    @Test fun `cancel during connectivity wait stops sending and keeps audio`() = runTest {
        val api = api(network, Success("late"))
        gate.blocked = true
        val delivery = FakeDelivery()
        val c = controller(api, delivery)
        c.recordAndStop()
        runCurrent()
        assertEquals(Status.WaitingForSignal, listener.statuses.last())
        c.cancelTranscription()
        advanceTimeBy(600_000)
        assertEquals(1, api.calls.size)
        assertEquals(Status.Cancelled, listener.statuses.last())
        assertEquals(1, store.pending().size)
        assertEquals(1, listener.pendingCounts.last())
        assertTrue(delivery.committed.isEmpty())
    }

    // 15
    @Test fun `cancel during backoff sleep keeps audio`() = runTest {
        val api = api(network, network, Success("late"))
        val delivery = FakeDelivery()
        val c = controller(api, delivery)
        c.recordAndStop()
        advanceTimeBy(1_000) // second attempt failed at t=0, now sleeping 2 s
        assertEquals(2, api.calls.size)
        c.cancelTranscription()
        advanceUntilIdle()
        assertEquals(2, api.calls.size)
        assertEquals(Status.Cancelled, listener.statuses.last())
        assertEquals(1, store.pending().size)
        assertTrue(delivery.committed.isEmpty())
    }

    // 16
    @Test fun `slide-left cancel discards recording and never calls the API`() = runTest {
        val api = api(Success("never"))
        val c = controller(api)
        assertTrue(c.startRecording())
        val file = recorder.file!!
        c.cancelRecording()
        advanceUntilIdle()
        assertTrue(recorder.cancelled)
        assertFalse(file.exists())
        assertTrue(store.pending().isEmpty())
        assertTrue(store.recordingFiles().isEmpty())
        assertTrue(api.calls.isEmpty())
        assertFalse(c.isRecording)
        assertEquals(Status.Idle, listener.statuses.last())
    }

    // 17
    @Test fun `too short tap discards and hints HoldToTalk`() = runTest {
        val api = api(Success("never"))
        val c = controller(api)
        assertTrue(c.startRecording())
        c.discardTooShort()
        advanceUntilIdle()
        assertTrue(recorder.cancelled)
        assertTrue(store.pending().isEmpty())
        assertTrue(api.calls.isEmpty())
        assertFalse(c.isRecording)
        assertEquals(Status.HoldToTalk, listener.statuses.last())
    }

    // 18
    @Test fun `nothing heard deletes audio and inserts nothing`() = runTest {
        store.lastText = "previous"
        val delivery = FakeDelivery()
        controller(api(Success("Thank you.")), delivery).recordAndStop()
        advanceUntilIdle()
        assertTrue(delivery.committed.isEmpty())
        assertNull(delivery.clipboard)
        assertTrue(store.pending().isEmpty())
        assertTrue(store.history.isEmpty())
        assertEquals(Cue.NothingHeard, listener.cues.last())
        assertEquals(Status.NothingHeard, listener.statuses.last())
        assertEquals("previous", store.lastText)
        assertEquals(0, listener.pendingCounts.last())
    }

    // 19
    @Test fun `recording under 1 KB is discarded without calling the API`() = runTest {
        recorder.bytesOnStop = 1024
        val api = api(Success("never"))
        controller(api).recordAndStop()
        advanceUntilIdle()
        assertTrue(api.calls.isEmpty())
        assertTrue(store.pending().isEmpty())
        assertTrue(store.recordingFiles().isEmpty())
        assertEquals(Status.RecordingFailed, listener.statuses.last())
    }

    // 20
    @Test fun `no API key refuses to record`() = runTest {
        apiKey = null
        val c = controller(api(Success("never")))
        assertFalse(c.startRecording())
        assertEquals(Status.NoApiKey, listener.statuses.last())
        assertEquals(0, recorder.started)
        assertFalse(c.isRecording)
    }

    // 21
    @Test fun `no mic permission refuses to record`() = runTest {
        micGranted = false
        val c = controller(api(Success("never")))
        assertFalse(c.startRecording())
        assertEquals(Status.NoMicPermission, listener.statuses.last())
        assertEquals(0, recorder.started)
    }

    // 22
    @Test fun `recorder failing to start shows RecordingFailed`() = runTest {
        recorder.failStart = true
        val c = controller(api(Success("never")))
        assertFalse(c.startRecording())
        assertEquals(Status.RecordingFailed, listener.statuses.last())
        assertFalse(c.isRecording)
        assertTrue(listener.cues.isEmpty())
    }

    @Test fun `recording starts with a cue and the recording status`() = runTest {
        advanceTimeBy(1_234)
        val c = controller(api(Success("x")))
        assertTrue(c.startRecording())
        assertTrue(c.isRecording)
        assertEquals(Status.Recording(1_234, locked = false), listener.statuses.last())
        assertEquals(listOf(Cue.RecordStart), listener.cues)
    }

    @Test fun `start while already recording is ignored`() = runTest {
        val c = controller(api(Success("x")))
        assertTrue(c.startRecording())
        assertFalse(c.startRecording())
        assertEquals(1, recorder.started)
        assertTrue(c.isRecording)
    }

    // 23
    @Test fun `stop when not recording does nothing`() = runTest {
        val api = api(Success("never"))
        val c = controller(api)
        c.stopAndTranscribe()
        advanceUntilIdle()
        assertTrue(api.calls.isEmpty())
        assertTrue(listener.statuses.isEmpty())
        assertTrue(listener.cues.isEmpty())
        assertEquals(0, recorder.stopped)
    }

    // 24
    @Test fun `retryPending sends unsent recordings oldest first into the current field`() = runTest {
        val first = store.seedPending()
        val second = store.seedPending()
        val api = api(Success("one"), Success("two"))
        val delivery = FakeDelivery(before = 'x')
        val c = controller(api, delivery)
        c.refreshPendingCount()
        c.retryPending()
        advanceUntilIdle()
        assertEquals(listOf(first.name, second.name), api.calls.map { it.first.name })
        assertEquals(listOf(" one", " two"), delivery.committed)
        assertEquals(listOf(2, 1, 0), listener.pendingCounts.takeLast(3))
        assertTrue(store.pending().isEmpty())
    }

    @Test fun `retryPending after giving up sends the same recording again`() = runTest {
        val api = api(network)
        val delivery = FakeDelivery()
        val c = controller(api, delivery)
        c.recordAndStop()
        advanceUntilIdle()
        assertEquals(Status.GaveUp(10), listener.statuses.last())
        api.then(Success("finally"))
        c.retryPending()
        advanceUntilIdle()
        assertEquals(listOf(" finally"), delivery.committed)
        assertTrue(store.pending().isEmpty())
    }

    @Test fun `retryPending does not queue a recording that is already being sent`() = runTest {
        val api = api(network, Success("once"))
        gate.blocked = true
        val delivery = FakeDelivery()
        val c = controller(api, delivery)
        c.recordAndStop()
        runCurrent()
        assertEquals(Status.WaitingForSignal, listener.statuses.last())
        c.retryPending()
        advanceUntilIdle()
        assertEquals(1, api.calls.size) // the original job is still waiting; no duplicate send was queued
        c.cancelTranscription()
        c.retryPending()
        advanceUntilIdle()
        assertEquals(listOf(" once"), delivery.committed)
    }

    // 25
    @Test fun `second recording made while first is sending is delivered after the first`() = runTest {
        val api = api(Success("first"), Success("second"))
        api.delays[0] = 5_000
        val delivery = FakeDelivery()
        val c = controller(api, delivery)
        c.recordAndStop()
        advanceTimeBy(1_000)
        c.recordAndStop()
        advanceUntilIdle()
        assertEquals(listOf(" first", " second"), delivery.committed)
        assertEquals(listOf(0L, 5_000L), api.callTimes)
    }

    @Test fun `earlier job's progress does not overwrite the recording status`() = runTest {
        val api = api(network, Success("one"))
        gate.blocked = true
        val delivery = FakeDelivery()
        val c = controller(api, delivery)
        c.recordAndStop()
        runCurrent()
        assertEquals(Status.WaitingForSignal, listener.statuses.last())
        assertTrue(c.startRecording())
        val cuesBefore = listener.cues.size
        gate.release()
        advanceUntilIdle()
        assertEquals(listOf(" one"), delivery.committed) // the earlier job still delivers
        assertTrue(listener.statuses.last() is Status.Recording)
        assertEquals(cuesBefore, listener.cues.size)
    }

    @Test fun `no retry or error sounds are played into a recording in progress`() = runTest {
        val api = api(network, HttpError(401, "nope", null))
        val c = controller(api)
        c.recordAndStop()
        assertTrue(c.startRecording())
        val cuesBefore = listener.cues.size
        advanceUntilIdle()
        assertEquals(2, api.calls.size)
        assertEquals(cuesBefore, listener.cues.size)
        assertTrue(listener.statuses.last() is Status.Recording)
    }

    @Test fun `stopping shows the earlier job's progress until the queue moves on`() = runTest {
        val api = api(network, Success("one"), Success("two"))
        gate.blocked = true
        val c = controller(api)
        c.recordAndStop()
        runCurrent()
        assertTrue(c.startRecording())
        c.stopAndTranscribe()
        assertEquals(Status.WaitingForSignal, listener.statuses.last())
    }

    @Test fun `stopping with nothing else in flight shows Transcribing at once`() = runTest {
        val c = controller(api(Success("x")))
        assertTrue(c.startRecording())
        c.stopAndTranscribe()
        assertEquals(Status.Transcribing, listener.statuses.last())
        assertEquals(Cue.RecordStop, listener.cues.last())
        assertEquals(1, listener.pendingCounts.last())
    }

    // 26
    @Test fun `cancelTranscription cancels queued jobs too and keeps both files`() = runTest {
        val api = api(network, Success("late"))
        gate.blocked = true
        val delivery = FakeDelivery()
        val c = controller(api, delivery)
        c.recordAndStop()
        runCurrent()
        c.recordAndStop()
        runCurrent()
        c.cancelTranscription()
        advanceUntilIdle()
        assertEquals(1, api.calls.size)
        assertEquals(2, store.pending().size)
        assertEquals(Status.Cancelled, listener.statuses.last())
        assertEquals(2, listener.pendingCounts.last())
        assertTrue(delivery.committed.isEmpty())
    }

    @Test fun `cancelTranscription with nothing in flight changes nothing`() = runTest {
        val c = controller(api(Success("x")))
        c.cancelTranscription()
        assertTrue(listener.statuses.isEmpty())
    }

    // 27
    @Test fun `reinsertLast commits last text with leading space rule`() = runTest {
        store.lastText = "Hi"
        val delivery = FakeDelivery(before = 'x')
        val c = controller(api(Success("x")), delivery)
        c.reinsertLast()
        delivery.before = '\n'
        c.reinsertLast()
        assertEquals(listOf(" Hi", "Hi"), delivery.committed)
    }

    @Test fun `reinsertLast with no last text does nothing`() = runTest {
        val delivery = FakeDelivery()
        controller(api(Success("x")), delivery).reinsertLast()
        assertTrue(delivery.committed.isEmpty())
    }

    // 28
    @Test fun `max duration callback stops and transcribes`() = runTest {
        val api = api(Success("long talk"))
        val delivery = FakeDelivery()
        val c = controller(api, delivery)
        assertTrue(c.startRecording())
        recorder.hitMaxDuration()
        advanceUntilIdle()
        assertFalse(c.isRecording)
        assertEquals(1, recorder.stopped)
        assertEquals(listOf(" long talk"), delivery.committed)
    }

    @Test fun `a late max duration callback does not stop the next recording`() = runTest {
        val api = api(Success("a"), Success("b"))
        val c = controller(api)
        assertTrue(c.startRecording())
        recorder.hitMaxDuration() // callback from the first recording
        c.stopAndTranscribe()     // user stopped first
        assertTrue(c.startRecording())
        advanceUntilIdle()
        assertTrue(c.isRecording)
        assertEquals(1, recorder.stopped)
    }

    // 29
    @Test fun `scope cancelled mid-request leaves audio pending`() = runTest {
        val api = api(Success("late"))
        api.delays[0] = 10_000
        val delivery = FakeDelivery()
        val serviceScope = serviceScope()
        val c = controller(api, delivery, serviceScope)
        c.recordAndStop()
        advanceTimeBy(1_000)
        assertEquals(1, api.calls.size)
        serviceScope.coroutineContext.job.cancel()
        advanceUntilIdle()
        assertEquals(1, store.pending().size)
        assertTrue(delivery.committed.isEmpty())
    }

    // 30
    @Test fun `lock sets Recording locked`() = runTest {
        advanceTimeBy(500)
        val c = controller(api(Success("x")))
        assertTrue(c.startRecording())
        advanceTimeBy(300)
        c.lock()
        assertEquals(Status.Recording(500, locked = true), listener.statuses.last())
    }

    @Test fun `lock when not recording does nothing`() = runTest {
        controller(api(Success("x"))).lock()
        assertTrue(listener.statuses.isEmpty())
    }

    // 31
    @Test fun `api key removed between attempts stops with NoApiKey and keeps audio`() = runTest {
        val api = api(network, network, Success("never"))
        val c = controller(api)
        c.recordAndStop()
        runCurrent() // attempts 1 and 2 at t=0 (the connectivity wait replaces the first sleep), then a 2 s sleep
        assertEquals(2, api.calls.size)
        apiKey = null
        advanceUntilIdle()
        assertEquals(2, api.calls.size)
        assertEquals(Status.NoApiKey, listener.statuses.last())
        assertEquals(Cue.Error, listener.cues.last())
        assertEquals(1, store.pending().size)
    }

    @Test fun `the api key is read again for each attempt`() = runTest {
        val api = api(network, network, Success("ok"))
        val c = controller(api)
        c.recordAndStop()
        runCurrent()
        apiKey = "test-key-456"
        advanceUntilIdle()
        assertEquals(listOf(KEY, KEY, "test-key-456"), api.calls.map { it.second })
    }

    @Test fun `a recording stopped because the keyboard was hidden goes to the clipboard, not the field`() = runTest {
        val api = api(Success(" Hello there."))
        val delivery = FakeDelivery(field = F1)
        val c = controller(api, delivery)
        assertTrue(c.startRecording())
        c.stopAndTranscribe(intoField = false)
        advanceUntilIdle()
        assertTrue(delivery.committed.isEmpty())
        assertEquals("Hello there.", delivery.clipboard)
        assertEquals("Hello there.", store.lastText)
        assertEquals(Status.TextReady, listener.statuses.last())
    }

    @Test fun `429 asking to wait longer than the cap stops at once and keeps the audio`() = runTest {
        val api = api(HttpError(429, "Rate limit reached", 3_600_000), Success("ok"))
        controller(api).recordAndStop()
        advanceUntilIdle()
        assertEquals(1, api.calls.size)
        assertEquals(0, gate.calls)
        assertEquals(Status.RateLimited(3_600_000), listener.statuses.last())
        assertFalse(Cue.Retry in listener.cues)
        assertEquals(Cue.Error, listener.cues.last())
        assertEquals(1, store.pending().size)
    }

    @Test fun `429 asking to wait exactly the cap still waits and retries`() = runTest {
        val api = api(HttpError(429, null, 60_000), Success("ok"))
        controller(api).recordAndStop()
        advanceUntilIdle()
        assertEquals(listOf(0L, 60_000L), api.callTimes)
    }

    private companion object { const val KEY = "test-key-123" }
}
