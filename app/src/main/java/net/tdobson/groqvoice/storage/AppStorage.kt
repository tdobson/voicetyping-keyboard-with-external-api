package net.tdobson.groqvoice.storage

import android.content.Context
import java.io.File

/** Where recordings live (app-private, not backed up). Shared by the keyboard and the settings screen. */
object AppStorage {
    fun voiceDir(context: Context): File = File(context.filesDir, "voice")

    fun pendingStore(context: Context): PendingStore =
        PendingStore(voiceDir(context), { System.currentTimeMillis() })
}
