package net.tdobson.groqvoice.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.view.inputmethod.InputConnection
import net.tdobson.groqvoice.core.controller.DeliveryTarget
import net.tdobson.groqvoice.core.controller.FieldIdentity

/**
 * Where transcriptions go. [current] is the focused field's identity, set by the service in onStartInput and
 * cleared in onFinishInput; the controller compares it with the identity captured at stop time before it
 * commits, and falls back to the clipboard if the field changed.
 */
class ImeDelivery(
    private val connection: () -> InputConnection?,
    private val clipboard: ClipboardManager,
) : DeliveryTarget {
    var current: FieldIdentity? = null

    override fun currentField(): FieldIdentity? = current

    override fun charBeforeCursor(): Char? = connection()?.getTextBeforeCursor(1, 0)?.firstOrNull()

    override fun commit(text: String) {
        connection()?.commitText(text, 1)
    }

    override fun copyToClipboard(text: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
    }

    companion object {
        const val CLIP_LABEL = "Groq Voice"
    }
}
