package net.tdobson.groqvoice.ime

import android.content.ClipboardManager
import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import net.tdobson.groqvoice.core.controller.FieldIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImeDeliveryTest {
    private lateinit var context: Context
    private lateinit var clipboard: ClipboardManager
    private lateinit var field: EditText
    private var connection: InputConnection? = null
    private lateinit var delivery: ImeDelivery

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clipboard = context.getSystemService(ClipboardManager::class.java)
        field = EditText(context)
        connection = field.onCreateInputConnection(EditorInfo())
        delivery = ImeDelivery({ connection }, clipboard)
    }

    private fun type(text: String) {
        field.setText(text)
        field.setSelection(text.length)
    }

    @Test fun `charBeforeCursor is the last character before the cursor`() {
        type("hello")
        assertEquals('o', delivery.charBeforeCursor())
        field.setSelection(2)
        assertEquals('e', delivery.charBeforeCursor())
    }

    @Test fun `charBeforeCursor is null in an empty field`() {
        type("")
        assertNull(delivery.charBeforeCursor())
    }

    @Test fun `charBeforeCursor is null without a connection`() {
        connection = null
        assertNull(delivery.charBeforeCursor())
    }

    @Test fun `commit inserts the text at the cursor`() {
        type("say")
        delivery.commit(" hello there")
        assertEquals("say hello there", field.text.toString())
        assertEquals(field.text.length, field.selectionStart)
    }

    @Test fun `commit without a connection does nothing and does not crash`() {
        connection = null
        delivery.commit("lost")
    }

    @Test fun `copyToClipboard puts the plain text on the clipboard`() {
        delivery.copyToClipboard("Café ↺ text")
        val clip = clipboard.primaryClip!!
        assertEquals("Café ↺ text", clip.getItemAt(0).text.toString())
        assertEquals("Groq Voice", clip.description.label.toString())
    }

    @Test fun `currentField is whatever the service set`() {
        assertNull(delivery.currentField())
        val f = FieldIdentity("com.example", 3, 9)
        delivery.current = f
        assertEquals(f, delivery.currentField())
        delivery.current = null
        assertNull(delivery.currentField())
    }
}
