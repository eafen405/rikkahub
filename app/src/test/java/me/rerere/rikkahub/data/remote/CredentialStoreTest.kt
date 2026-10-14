package me.rerere.rikkahub.data.remote

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The envelope a session credential is stored in.
 *
 * The sealed value is what actually reaches the disk, so these tests assert two
 * properties that must hold no matter which key store produced the key: the
 * plaintext must not be recoverable from the stored text without the key, and
 * a value the app did not write must not open.
 *
 * The AES-GCM itself is the JVM's own, so this runs as an ordinary unit test;
 * only the AndroidKeyStore-backed key is platform work.
 */
class CredentialStoreTest {

    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `a sealed secret opens back to its plaintext`() {
        val sealed = SealedSecret.seal(key, "token-phaethon")

        assertEquals("token-phaethon", SealedSecret.open(key, sealed))
    }

    @Test
    fun `the stored text never contains the credential`() {
        val sealed = SealedSecret.seal(key, "token-phaethon")

        assertFalse("the credential must not be readable from the stored value", sealed.contains("token-phaethon"))
        val decoded = String(Base64.getDecoder().decode(sealed.substringAfter(':')), Charsets.ISO_8859_1)
        assertFalse(decoded.contains("token-phaethon"))
    }

    @Test
    fun `sealing the same credential twice produces different text`() {
        val first = SealedSecret.seal(key, "token-phaethon")
        val second = SealedSecret.seal(key, "token-phaethon")

        assertNotEquals("a fixed IV would leak that the credential never changed", first, second)
        assertEquals(SealedSecret.open(key, first), SealedSecret.open(key, second))
    }

    @Test
    fun `a tampered secret does not open`() {
        val sealed = SealedSecret.seal(key, "token-phaethon")
        val (iv, body) = sealed.split(':', limit = 2)
        val bytes = Base64.getDecoder().decode(body)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()

        try {
            SealedSecret.open(key, "$iv:${Base64.getEncoder().encodeToString(bytes)}")
            org.junit.Assert.fail("a modified ciphertext must not authenticate")
        } catch (rejected: AEADBadTagException) {
            assertTrue(rejected.message != null)
        }
    }

    @Test
    fun `a secret sealed under another key does not open`() {
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val sealed = SealedSecret.seal(key, "token-phaethon")

        try {
            SealedSecret.open(other, sealed)
            org.junit.Assert.fail("another key must not open this secret")
        } catch (rejected: AEADBadTagException) {
            assertTrue(rejected.message != null)
        }
    }

    @Test
    fun `the in-memory store returns what it saved and forgets on clear`() = runTest {
        val store = InMemoryCredentialStore()

        assertNull(store.load())
        store.save("token-phaethon")
        assertEquals("token-phaethon", store.load())
        store.clear()
        assertNull(store.load())
    }
}
