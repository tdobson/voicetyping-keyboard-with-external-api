package net.tdobson.groqvoice.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.InvalidAlgorithmParameterException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Groq API key, encrypted with AES-256-GCM. The key-encryption key lives in Android Keystore and never
 * leaves it; only the ciphertext and IV (base64) are kept in SharedPreferences. Never logs anything.
 */
class ApiKeyStore(
    private val prefs: SharedPreferences,
    private val keyAlias: String = DEFAULT_ALIAS,
    /** Looks up the key-encryption key; a test seam for Keystore failures. */
    private val loadKey: (alias: String) -> SecretKey? = ::androidKeystoreKey,
) : KeyStorage {

    @Synchronized
    override fun save(apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, loadKey(keyAlias) ?: generateKey())
        val ct = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        prefs.edit(commit = true) {
            putString(CT, Base64.encodeToString(ct, Base64.NO_WRAP))
            putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        }
    }

    /**
     * The key, or null if none is stored or it cannot be read right now. Only failures that mean the key is
     * unrecoverable (no Keystore entry, a missing or malformed IV, bad base64, a failed GCM tag, a permanently
     * invalidated key) clear the stored ciphertext. Anything else (keystore2 busy right after boot or an OTA, a
     * provider error) gives null but keeps it, so the next call can succeed.
     */
    @Synchronized
    override fun apiKey(): String? {
        val ctB64 = prefs.getString(CT, null) ?: return null
        val ivB64 = prefs.getString(IV, null) ?: return unrecoverable()
        val key = try {
            loadKey(keyAlias)
        } catch (_: Exception) {
            return null
        } ?: return unrecoverable()
        return try {
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val ct = Base64.decode(ctB64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            if (isUnrecoverable(e)) unrecoverable() else null
        }
    }

    /** Tampered data, a wiped Keystore (e.g. after a restore) or a missing IV: forget the ciphertext. */
    private fun unrecoverable(): String? {
        clear()
        return null
    }

    private fun isUnrecoverable(e: Exception) =
        e is AEADBadTagException ||
            e is KeyPermanentlyInvalidatedException ||
            e is InvalidAlgorithmParameterException ||
            e is IllegalArgumentException // bad base64 or an empty IV

    @Synchronized
    override fun clear() = prefs.edit(commit = true) { remove(CT); remove(IV) }

    override fun hasKey(): Boolean = apiKey() != null

    private fun generateKey(): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS = "groqvoice_api_key_kek"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val CT = "api_key_ct"
        private const val IV = "api_key_iv"

        private fun androidKeystoreKey(alias: String): SecretKey? =
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.getKey(alias, null) as? SecretKey

        fun from(context: Context): ApiKeyStore =
            ApiKeyStore(context.applicationContext.getSharedPreferences("secure", Context.MODE_PRIVATE))
    }
}
