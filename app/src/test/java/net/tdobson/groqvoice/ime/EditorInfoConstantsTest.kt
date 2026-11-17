package net.tdobson.groqvoice.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import net.tdobson.groqvoice.core.keys.EnterKeyResolver
import org.junit.Assert.assertEquals
import org.junit.Test

/** The pure-Kotlin EnterKeyResolver mirrors Android's constants; these are compile-time ints inlined from android.jar. */
class EditorInfoConstantsTest {
    @Test fun `resolver constants equal the platform constants`() {
        assertEquals(EditorInfo.IME_MASK_ACTION, EnterKeyResolver.IME_MASK_ACTION)
        assertEquals(EditorInfo.IME_ACTION_UNSPECIFIED, EnterKeyResolver.IME_ACTION_UNSPECIFIED)
        assertEquals(EditorInfo.IME_ACTION_NONE, EnterKeyResolver.IME_ACTION_NONE)
        assertEquals(EditorInfo.IME_ACTION_GO, EnterKeyResolver.IME_ACTION_GO)
        assertEquals(EditorInfo.IME_ACTION_SEARCH, EnterKeyResolver.IME_ACTION_SEARCH)
        assertEquals(EditorInfo.IME_ACTION_SEND, EnterKeyResolver.IME_ACTION_SEND)
        assertEquals(EditorInfo.IME_ACTION_NEXT, EnterKeyResolver.IME_ACTION_NEXT)
        assertEquals(EditorInfo.IME_ACTION_DONE, EnterKeyResolver.IME_ACTION_DONE)
        assertEquals(EditorInfo.IME_ACTION_PREVIOUS, EnterKeyResolver.IME_ACTION_PREVIOUS)
        assertEquals(EditorInfo.IME_FLAG_NO_ENTER_ACTION, EnterKeyResolver.IME_FLAG_NO_ENTER_ACTION)
        assertEquals(InputType.TYPE_TEXT_FLAG_MULTI_LINE, EnterKeyResolver.TYPE_TEXT_FLAG_MULTI_LINE)
    }
}
