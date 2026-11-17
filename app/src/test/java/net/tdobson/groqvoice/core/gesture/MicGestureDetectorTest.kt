package net.tdobson.groqvoice.core.gesture

import net.tdobson.groqvoice.core.gesture.GestureEvent.Cancel
import net.tdobson.groqvoice.core.gesture.GestureEvent.Hold
import net.tdobson.groqvoice.core.gesture.GestureEvent.Lock
import net.tdobson.groqvoice.core.gesture.GestureEvent.Release
import net.tdobson.groqvoice.core.gesture.GestureEvent.TooShort
import net.tdobson.groqvoice.core.gesture.MicGestureDetector.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Density 1.0, so lock is 70 px up and cancel is 90 px left. The finger goes down at (100, 100). */
class MicGestureDetectorTest {
    private val d = MicGestureDetector(GestureConfig(), density = 1f)

    private fun down(t: Long = 0) = d.onDown(X, Y, t)
    private fun move(dx: Float = 0f, dy: Float = 0f, t: Long = 50) = d.onMove(X + dx, Y + dy, t)
    private fun up(t: Long = 600, dx: Float = 0f, dy: Float = 0f) = d.onUp(X + dx, Y + dy, t)

    @Test fun `down starts recording`() {
        assertEquals(Hold, down())
        assertEquals(State.Pressed, d.state)
    }

    @Test fun `release before 500 ms is too short`() {
        down(0)
        assertEquals(TooShort, up(499))
        assertEquals(State.Idle, d.state)
    }

    @Test fun `release at 500 ms sends`() {
        down(0)
        assertEquals(Release, up(500))
        assertEquals(State.Idle, d.state)
    }

    @Test fun `slide up locks and the stop tap releases`() {
        down()
        assertEquals(Lock, move(dy = -71f))
        assertEquals(State.Locked, d.state)
        assertNull(up(700, dy = -71f))
        assertEquals(State.Locked, d.state)
        assertNull(d.onDown(X, Y, 5_000))
        assertEquals(Release, d.onUp(X, Y, 5_100))
        assertEquals(State.Idle, d.state)
    }

    @Test fun `a quick lock still sends on the stop tap`() {
        down(0)
        assertEquals(Lock, move(dy = -80f, t = 100))
        assertNull(up(150, dy = -80f))
        assertNull(d.onDown(X, Y, 200))
        assertEquals(Release, d.onUp(X, Y, 250))
    }

    @Test fun `slide left cancels and the later lift does nothing`() {
        down()
        assertEquals(Cancel, move(dx = -91f))
        assertEquals(State.Idle, d.state)
        assertNull(up(900, dx = -91f))
    }

    @Test fun `small slides do nothing`() {
        down()
        assertNull(move(dy = -69f))
        assertNull(move(dx = -89f))
        assertEquals(State.Pressed, d.state)
    }

    @Test fun `slides right or down do nothing and release still sends`() {
        down(0)
        assertNull(move(dx = 200f))
        assertNull(move(dy = 200f))
        assertEquals(Release, up(600, dy = 200f))
    }

    @Test fun `horizontal-dominant diagonal cancels`() {
        down()
        assertEquals(Cancel, move(dx = -100f, dy = -60f))
    }

    @Test fun `vertical-dominant diagonal locks`() {
        down()
        assertEquals(Lock, move(dx = -60f, dy = -100f))
    }

    @Test fun `after cancel further moves never lock`() {
        down()
        move(dx = -95f)
        assertNull(move(dx = 0f, dy = -200f, t = 80))
        assertEquals(State.Idle, d.state)
    }

    @Test fun `system cancel after hold time sends`() {
        down(0)
        assertEquals(Release, d.onCancel(600))
        assertEquals(State.Idle, d.state)
    }

    @Test fun `system cancel before hold time is too short`() {
        down(0)
        assertEquals(TooShort, d.onCancel(100))
    }

    @Test fun `system cancel while locked keeps recording`() {
        down()
        move(dy = -90f)
        assertNull(d.onCancel(800))
        assertEquals(State.Locked, d.state)
    }

    @Test fun `system cancel during the stop tap keeps recording and a new tap still stops`() {
        down()
        move(dy = -90f)
        up(700, dy = -90f)
        assertNull(d.onDown(X, Y, 1_000))
        assertNull(d.onCancel(1_050))
        assertEquals(State.Locked, d.state)
        assertNull(d.onUp(X, Y, 1_100)) // a stray up without a fresh down does not stop
        assertNull(d.onDown(X, Y, 2_000))
        assertEquals(Release, d.onUp(X, Y, 2_050))
    }

    @Test fun `events while idle are ignored`() {
        assertNull(move())
        assertNull(up())
        assertNull(d.onCancel(10))
        assertEquals(State.Idle, d.state)
    }

    @Test fun `second down while pressed is ignored`() {
        down(0)
        assertNull(d.onDown(X, Y, 100))
        assertEquals(Release, up(600)) // the original down time still counts
    }

    @Test fun `thresholds scale with density`() {
        val dense = MicGestureDetector(GestureConfig(), density = 2.75f)
        dense.onDown(X, Y, 0)
        assertNull(dense.onMove(X, Y - 190f, 10))
        assertEquals(Lock, dense.onMove(X, Y - 193f, 20))
    }

    @Test fun `dp conversion lives in the config`() {
        assertEquals(192.5f, GestureConfig().lockSlidePx(2.75f), 0.0001f)
        assertEquals(247.5f, GestureConfig().cancelSlidePx(2.75f), 0.0001f)
    }

    @Test fun `defaults match the spec`() {
        val c = GestureConfig()
        assertEquals(500L, c.holdMs)
        assertEquals(70f, c.lockSlideDp)
        assertEquals(90f, c.cancelSlideDp)
    }

    @Test fun `reset forgets a locked recording so the next press starts a new one`() {
        down(); move(dy = -71f); up(700)
        d.reset()
        assertEquals(State.Idle, d.state)
        assertEquals(Hold, d.onDown(X, Y, 5_000))
    }

    @Test fun `reset while held makes the lift do nothing`() {
        down(0)
        d.reset()
        assertNull(up(900))
        assertEquals(State.Idle, d.state)
    }

    @Test fun `adopting a recording it did not start makes the next tap Stop`() {
        d.adoptRecording()
        assertEquals(State.Locked, d.state)
        assertNull(d.onDown(X, Y, 0))
        assertEquals(Release, d.onUp(X, Y, 80))
        assertEquals(State.Idle, d.state)
    }

    @Test fun `adopting while pressed or locked changes nothing`() {
        down(0)
        d.adoptRecording()
        assertEquals(State.Pressed, d.state)
        assertEquals(Release, up(600))
        down(1_000); move(dy = -71f, t = 1_050)
        d.onDown(X, Y, 2_000) // the Stop tap has started
        d.adoptRecording()
        assertEquals(Release, d.onUp(X, Y, 2_080))
    }

    private companion object { const val X = 100f; const val Y = 100f }
}
