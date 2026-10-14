package me.rerere.rikkahub.data.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Who the app believes it is signed in as.
 *
 * A phone is a hostile place for a single "current account" variable: two
 * sign-ins can overlap, a sign-out can land between them, and the network
 * answers in whatever order it likes. These tests pin the rule that the
 * *last* answer must not decide the account — the last *decision* must.
 */
class AccountSessionTest {

    /** A backend whose answers are released by the test, so overlap is deterministic. */
    private class GatedBackend : IdentityBackend {
        private val answers = mutableMapOf<String, CompletableDeferred<Any>>()
        val signedOut = mutableListOf<String>()

        private fun gate(name: String): CompletableDeferred<Any> =
            answers.getOrPut(name) { CompletableDeferred() }

        fun release(name: String, signedIn: SignedIn) {
            gate(name).complete(signedIn)
        }

        fun refuse(name: String, refused: IdentityRefused) {
            gate(name).complete(refused)
        }

        /** The answer released for [name], re-throwing a refusal the test scripted. */
        private suspend fun answer(name: String): Any {
            val released = gate(name).await()
            if (released is IdentityRefused) throw released
            return released
        }

        override suspend fun signIn(name: String, password: String): SignedIn = answer(name) as SignedIn

        override suspend fun register(inviteCode: String, name: String, password: String): SignedIn =
            answer(name) as SignedIn

        override suspend fun createInvite(token: String): String = "INVITE"

        override suspend fun session(token: String): IdentityUser =
            (answer(token.removePrefix("token-")) as SignedIn).user

        override suspend fun signOut(token: String) {
            signedOut += token
        }
    }

    private class RecordingStore(initial: String? = null) : CredentialStore {
        val history = mutableListOf<String?>()
        private var token: String? = initial

        override suspend fun save(token: String) {
            this.token = token
            history += token
        }

        override suspend fun load(): String? = token

        override suspend fun clear() {
            token = null
            history += null
        }
    }

    private fun user(shortId: Int, name: String, isAdmin: Boolean = false) =
        IdentityUser(shortId = shortId, name = name, isAdmin = isAdmin)

    @Test
    fun `a sign-in records the account and its credential`() = runTest {
        val backend = GatedBackend()
        val store = RecordingStore()
        val session = AccountSession(backend, store, onAccountChanged = {})

        backend.release("first", SignedIn("token-first", user(2, "first")))
        val signedIn = session.signIn("first", "pw")

        assertEquals(2, signedIn.shortId)
        assertEquals("token-first", session.token)
        assertEquals(2, session.user!!.shortId)
        assertEquals("token-first", store.load())
        assertTrue(session.isSignedIn)
    }

    @Test
    fun `an answer that lands after the account changed is not believed`() = runTest {
        val backend = GatedBackend()
        val store = RecordingStore()
        val session = AccountSession(backend, store, onAccountChanged = {})

        // The first sign-in is in flight and will not be answered until later.
        val late = async { runCatching { session.signIn("first", "pw") } }
        runCurrent()

        backend.release("second", SignedIn("token-second", user(3, "second")))
        session.signIn("second", "pw")
        assertEquals("token-second", session.token)

        // Now the stale answer arrives.
        backend.release("first", SignedIn("token-first", user(2, "first")))
        late.await()

        assertEquals("a late answer must not take over the account", "token-second", session.token)
        assertEquals(3, session.user!!.shortId)
        assertEquals("token-second", store.load())
    }

    @Test
    fun `signing out while a sign-in is in flight leaves nobody signed in`() = runTest {
        val backend = GatedBackend()
        val store = RecordingStore()
        val session = AccountSession(backend, store, onAccountChanged = {})

        backend.release("first", SignedIn("token-first", user(2, "first")))
        session.signIn("first", "pw")
        assertEquals("token-first", store.load())

        val late = async { runCatching { session.signIn("second", "pw") } }
        runCurrent()

        session.signOut()
        assertNull(session.token)
        assertNull(session.user)
        assertNull(store.load())

        backend.release("second", SignedIn("token-second", user(3, "second")))
        late.await()

        assertNull("a late answer must not sign anybody back in", session.token)
        assertNull(store.load())
        assertEquals("the revoked credential must be released", listOf("token-first"), backend.signedOut)
    }

    @Test
    fun `a refused sign-in leaves the current account untouched`() = runTest {
        val backend = GatedBackend()
        val store = RecordingStore()
        val session = AccountSession(backend, store, onAccountChanged = {})

        backend.release("first", SignedIn("token-first", user(2, "first")))
        session.signIn("first", "pw")

        backend.refuse("second", IdentityRefused(409, "identity/name-taken", "taken"))
        try {
            session.signIn("second", "pw")
            fail("a refused sign-in must not report success")
        } catch (refused: IdentityRefused) {
            assertEquals("identity/name-taken", refused.code)
        }

        assertEquals("a failed attempt must not disturb the live account", "token-first", session.token)
        assertEquals(2, session.user!!.shortId)
    }

    @Test
    fun `changing the account tells observers to drop what belonged to the old one`() = runTest {
        val backend = GatedBackend()
        val store = RecordingStore()
        var changes = 0
        val session = AccountSession(backend, store, onAccountChanged = { changes += 1 })

        backend.release("first", SignedIn("token-first", user(2, "first")))
        session.signIn("first", "pw")
        assertEquals(1, changes)

        backend.release("second", SignedIn("token-second", user(3, "second")))
        session.signIn("second", "pw")
        assertEquals("switching accounts is a change too", 2, changes)
        assertEquals(listOf("token-first", "token-second"), store.history)

        session.signOut()
        assertEquals(3, changes)
        assertEquals(listOf("token-first", "token-second", null), store.history)
    }

    @Test
    fun `a stored credential is re-established on start-up`() = runTest {
        val backend = GatedBackend()
        val store = RecordingStore("token-first")
        val session = AccountSession(backend, store, onAccountChanged = {})

        backend.release("first", SignedIn("token-first", user(2, "first")))
        val restored = session.restore()

        assertEquals(2, restored!!.shortId)
        assertEquals("token-first", session.token)
    }

    @Test
    fun `a rejected stored credential is dropped rather than kept`() = runTest {
        val backend = GatedBackend()
        val store = RecordingStore("token-stale")
        val session = AccountSession(backend, store, onAccountChanged = {})

        backend.refuse("stale", IdentityRefused(401, "identity/unauthorized", "gone"))
        assertNull(session.restore())

        assertNull("a revoked credential must not linger on the device", store.load())
        assertNull(session.token)
    }
}
