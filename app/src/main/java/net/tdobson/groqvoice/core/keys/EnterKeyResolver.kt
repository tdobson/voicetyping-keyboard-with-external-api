package net.tdobson.groqvoice.core.keys

enum class EnterAction { Go, Search, Send, Next, Done, Previous, Newline }

/** Decides what the enter key does from EditorInfo.imeOptions, without depending on Android. */
object EnterKeyResolver {
    // Mirrors android.view.inputmethod.EditorInfo / android.text.InputType values (asserted in ime/EditorInfoConstantsTest).
    const val IME_MASK_ACTION = 0x000000ff
    const val IME_ACTION_UNSPECIFIED = 0
    const val IME_ACTION_NONE = 1
    const val IME_ACTION_GO = 2
    const val IME_ACTION_SEARCH = 3
    const val IME_ACTION_SEND = 4
    const val IME_ACTION_NEXT = 5
    const val IME_ACTION_DONE = 6
    const val IME_ACTION_PREVIOUS = 7
    const val IME_FLAG_NO_ENTER_ACTION = 0x40000000
    const val TYPE_TEXT_FLAG_MULTI_LINE = 0x00020000

    /**
     * The no-enter-action flag or a missing action gives a newline; an explicit action wins, even in a multi-line
     * field (so a multi-line field without an action is a newline). [inputType] is accepted so callers hand over
     * the whole EditorInfo state; no current rule needs more than imeOptions.
     */
    @Suppress("UNUSED_PARAMETER")
    fun resolve(imeOptions: Int, inputType: Int): EnterAction {
        if (imeOptions and IME_FLAG_NO_ENTER_ACTION != 0) return EnterAction.Newline
        return when (imeOptions and IME_MASK_ACTION) {
            IME_ACTION_GO -> EnterAction.Go
            IME_ACTION_SEARCH -> EnterAction.Search
            IME_ACTION_SEND -> EnterAction.Send
            IME_ACTION_NEXT -> EnterAction.Next
            IME_ACTION_DONE -> EnterAction.Done
            IME_ACTION_PREVIOUS -> EnterAction.Previous
            else -> EnterAction.Newline
        }
    }

    fun label(action: EnterAction): String = when (action) {
        EnterAction.Go -> "Go"
        EnterAction.Search -> "Search"
        EnterAction.Send -> "Send"
        EnterAction.Next -> "Next"
        EnterAction.Done -> "Done"
        EnterAction.Previous -> "Prev"
        EnterAction.Newline -> "⏎"
    }
}
