package me.rerere.rikkahub.data.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Keeps the session credential sealed under a key that never leaves the device.
 *
 * The key lives in the AndroidKeyStore, so the credential is bound to this
 * installation: a copy of the preferences file is worthless on another device
 * or after the key is gone, and nothing here needs a password to be typed
 * again. The preferences file itself holds only what [SealedSecret] produced —
 * a nonce and a ciphertext — never the credential.
 *
 * Re-sealing uses a fresh nonce each time, so the stored text changes whenever
 * the credential does and leaks nothing by staying identical when it does not.
 */
class AndroidKeystoreCredentialStore(
    context: Context,
    private val alias: String = DEFAULT_ALIAS,
    preferencesName: String = PREFERENCES_NAME,
) : CredentialStore {

    private val preferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override suspend fun save(token: String) {
        withContext(Dispatchers.IO) {
            val sealed = SealedSecret.seal(key(), token)
            // commit() rather than apply(): the caller is told the credential is
            // stored, and it must already be on disk when it is told.
            check(preferences.edit().putString(CREDENTIAL_KEY, sealed).commit()) {
                "the session credential could not be written"
            }
        }
    }

    override suspend fun load(): String? = withContext(Dispatchers.IO) {
        val stored = preferences.getString(CREDENTIAL_KEY, null) ?: return@withContext null
        try {
            SealedSecret.open(key(), stored)
        } catch (unreadable: GeneralSecurityException) {
            // A value this key cannot open is worthless — either the key was
            // rotated or the file was touched. Dropping it is the only honest
            // outcome; keeping it would fail identically on every later read.
            discard()
        } catch (unreadable: IllegalArgumentException) {
            discard()
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) { discard() }
    }

    private fun discard(): String? {
        preferences.edit().remove(CREDENTIAL_KEY).commit()
        return null
    }

    /** The installation's sealing key, created on first use. */
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // The credential is presented on the user's behalf when the app
                // is used, not only while they are looking at it; requiring
                // authentication per use would make the app unusable after a
                // reboot and is not what protects this value — the key store is.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "ourfairy.identity.credential"
        const val PREFERENCES_NAME = "ourfairy.identity"
        const val CREDENTIAL_KEY = "identity.credential"
    }
}
