package net.tdobson.groqvoice.security

import net.tdobson.groqvoice.core.controller.ApiKeyProvider

/** What the settings screen needs from key storage; [ApiKeyStore] in the app, a fake in Robolectric tests. */
interface KeyStorage : ApiKeyProvider {
    fun save(apiKey: String)
    fun clear()
    fun hasKey(): Boolean
}
