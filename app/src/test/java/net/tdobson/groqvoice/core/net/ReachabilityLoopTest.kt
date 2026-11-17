package net.tdobson.groqvoice.core.net

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReachabilityLoopTest {
    @Test fun `reachable at once returns after one probe`() = runTest {
        var probes = 0
        awaitReachable({ probes++; true })
        assertEquals(1, probes)
        assertEquals(0L, currentTime)
    }

    @Test fun `probes every three seconds until reachable`() = runTest {
        val answers = ArrayDeque(listOf(false, false, true))
        var probes = 0
        awaitReachable({ probes++; answers.removeFirst() })
        assertEquals(3, probes)
        assertEquals(6_000L, currentTime)
    }

    @Test fun `the gate before each probe runs before every probe`() = runTest {
        val log = mutableListOf<String>()
        val answers = ArrayDeque(listOf(false, true))
        awaitReachable({ log += "probe@$currentTime"; answers.removeFirst() }, 3_000) { log += "gate@$currentTime" }
        assertEquals(listOf("gate@0", "probe@0", "gate@3000", "probe@3000"), log)
    }

    @Test fun `never reachable keeps waiting until cancelled`() = runTest {
        var probes = 0
        val job = launch { awaitReachable({ probes++; false }) }
        advanceTimeBy(600_000)
        runCurrent()
        assertTrue(job.isActive)
        val before = probes
        assertEquals(201, before) // t = 0, 3 s, ... 600 s
        job.cancel()
        advanceTimeBy(60_000)
        assertTrue(job.isCancelled)
        assertEquals(before, probes)
    }
}
