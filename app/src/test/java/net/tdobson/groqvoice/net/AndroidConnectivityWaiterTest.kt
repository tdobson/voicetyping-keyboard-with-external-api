package net.tdobson.groqvoice.net

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain-JVM tests of the waiter logic with a fake network source. */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidConnectivityWaiterTest {
    private class FakeSource(var validated: Boolean) : ValidatedNetworkSource {
        var registered = 0
        var unregistered = 0
        private var listener: (() -> Unit)? = null
        override fun isValidatedNow() = validated
        override fun register(onValidated: () -> Unit): () -> Unit {
            registered++
            listener = onValidated
            return { unregistered++; listener = null }
        }
        fun deliverValidated() { validated = true; listener?.invoke() }
        val listening get() = listener != null
    }

    @Test fun `already validated returns without registering a callback`() = runTest {
        val src = FakeSource(validated = true)
        awaitValidatedNetwork(src)
        assertEquals(0, src.registered)
    }

    @Test fun `no network suspends until VALIDATED is delivered, then unregisters`() = runTest {
        val src = FakeSource(validated = false)
        var done = false
        launch { awaitValidatedNetwork(src); done = true }
        runCurrent()
        assertFalse(done)
        assertTrue(src.listening)
        src.deliverValidated()
        runCurrent()
        assertTrue(done)
        assertEquals(1, src.unregistered)
    }

    @Test fun `cancelling while waiting unregisters the callback`() = runTest {
        val src = FakeSource(validated = false)
        val job = launch { awaitValidatedNetwork(src) }
        runCurrent()
        job.cancel()
        runCurrent()
        assertEquals(1, src.registered)
        assertEquals(1, src.unregistered)
    }

    @Test fun `a second delivery after resuming is harmless`() = runTest {
        val src = FakeSource(validated = false)
        var listener: (() -> Unit)? = null
        val wrapping = object : ValidatedNetworkSource {
            override fun isValidatedNow() = false
            override fun register(onValidated: () -> Unit): () -> Unit { listener = onValidated; return src.register(onValidated) }
        }
        launch { awaitValidatedNetwork(wrapping) }
        runCurrent()
        listener!!.invoke()
        listener!!.invoke()
        runCurrent()
        assertEquals(1, src.unregistered)
    }

    @Test fun `validated delivered during register resumes and unregisters exactly once`() = runTest {
        var unregistered = 0
        val src = object : ValidatedNetworkSource {
            override fun isValidatedNow() = false
            override fun register(onValidated: () -> Unit): () -> Unit { onValidated(); return { unregistered++ } }
        }
        var done = false
        launch { awaitValidatedNetwork(src); done = true }
        runCurrent()
        assertTrue(done)
        assertEquals(1, unregistered)
    }

    @Test fun `after a validated network it probes Groq every 3 s until reachable`() = runTest {
        val src = FakeSource(validated = true)
        val probeTimes = mutableListOf<Long>()
        val answers = ArrayDeque(listOf(false, false, true))
        val waiter = AndroidConnectivityWaiter(src, reachable = { probeTimes += testScheduler.currentTime; answers.removeFirst() })
        var done = false
        launch { waiter.awaitOnline(); done = true }
        advanceTimeBy(5_999); runCurrent()
        assertFalse(done)
        advanceTimeBy(2); runCurrent()
        assertTrue(done)
        assertEquals(listOf(0L, 3_000L, 6_000L), probeTimes)
    }

    @Test fun `waits for the network before probing`() = runTest {
        val src = FakeSource(validated = false)
        var probes = 0
        val waiter = AndroidConnectivityWaiter(src, reachable = { probes++; true })
        var done = false
        launch { waiter.awaitOnline(); done = true }
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, probes)
        src.deliverValidated(); runCurrent()
        assertEquals(1, probes)
        assertTrue(done)
    }

    @Test fun `cancel during the probe loop stops probing`() = runTest {
        val src = FakeSource(validated = true)
        var probes = 0
        val waiter = AndroidConnectivityWaiter(src, reachable = { probes++; false })
        val job = launch { waiter.awaitOnline() }
        advanceTimeBy(7_000); runCurrent()
        job.cancel()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(3, probes)
    }
}
