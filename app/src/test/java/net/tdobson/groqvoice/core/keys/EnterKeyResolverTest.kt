package net.tdobson.groqvoice.core.keys

import net.tdobson.groqvoice.core.keys.EnterKeyResolver.IME_ACTION_DONE
import net.tdobson.groqvoice.core.keys.EnterKeyResolver.IME_ACTION_GO
import net.tdobson.groqvoice.core.keys.EnterKeyResolver.IME_ACTION_NEXT
import net.tdobson.groqvoice.core.keys.EnterKeyResolver.IME_ACTION_NONE
import net.tdobson.groqvoice.core.keys.EnterKeyResolver.IME_ACTION_PREVIOUS
import net.tdobson.groqvoice.core.keys.EnterKeyResolver.IME_ACTION_SEARCH
import net.tdobson.groqvoice.core.keys.EnterKeyResolver.IME_ACTION_SEND
import net.tdobson.groqvoice.core.keys.EnterKeyResolver.IME_ACTION_UNSPECIFIED
import net.tdobson.groqvoice.core.keys.EnterKeyResolver.IME_FLAG_NO_ENTER_ACTION
import net.tdobson.groqvoice.core.keys.EnterKeyResolver.TYPE_TEXT_FLAG_MULTI_LINE
import org.junit.Assert.assertEquals
import org.junit.Test

class EnterKeyResolverTest {
    private val text = 0x00000001 // TYPE_CLASS_TEXT
    private fun resolve(options: Int, type: Int = text) = EnterKeyResolver.resolve(options, type)

    @Test fun `each action constant maps to its action`() {
        val expected = mapOf(
            IME_ACTION_GO to EnterAction.Go,
            IME_ACTION_SEARCH to EnterAction.Search,
            IME_ACTION_SEND to EnterAction.Send,
            IME_ACTION_NEXT to EnterAction.Next,
            IME_ACTION_DONE to EnterAction.Done,
            IME_ACTION_PREVIOUS to EnterAction.Previous,
            IME_ACTION_NONE to EnterAction.Newline,
            IME_ACTION_UNSPECIFIED to EnterAction.Newline,
        )
        for ((action, result) in expected) assertEquals("action $action", result, resolve(action))
    }

    @Test fun `other ime option flags do not hide the action`() =
        assertEquals(EnterAction.Send, resolve(IME_ACTION_SEND or 0x02000000)) // IME_FLAG_NO_FULLSCREEN

    @Test fun `no-enter-action flag overrides send`() =
        assertEquals(EnterAction.Newline, resolve(IME_ACTION_SEND or IME_FLAG_NO_ENTER_ACTION))

    @Test fun `multi-line text with no action is a newline`() =
        assertEquals(EnterAction.Newline, resolve(IME_ACTION_UNSPECIFIED, text or TYPE_TEXT_FLAG_MULTI_LINE))

    @Test fun `explicit action wins over multi-line`() =
        assertEquals(EnterAction.Done, resolve(IME_ACTION_DONE, text or TYPE_TEXT_FLAG_MULTI_LINE))

    @Test fun `unknown action is a newline`() = assertEquals(EnterAction.Newline, resolve(99))

    @Test fun `labels`() {
        assertEquals(
            listOf("Go", "Search", "Send", "Next", "Done", "Prev", "⏎"),
            listOf(EnterAction.Go, EnterAction.Search, EnterAction.Send, EnterAction.Next, EnterAction.Done,
                EnterAction.Previous, EnterAction.Newline).map(EnterKeyResolver::label),
        )
    }
}
