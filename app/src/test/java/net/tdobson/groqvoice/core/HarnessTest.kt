package net.tdobson.groqvoice.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Layer 1 placeholder: proves plain JVM tests and coroutine virtual time work. */
@OptIn(ExperimentalCoroutinesApi::class)
class HarnessTest {
    @Test
    fun virtualTimeSkipsDelays() = runTest {
        delay(60_000)
        assertEquals(60_000L, currentTime)
    }
}
