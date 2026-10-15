package me.rerere.rikkahub.ui.identity

import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.remote.AccountSession
import me.rerere.rikkahub.data.remote.CredentialStore
import me.rerere.rikkahub.data.remote.IdentityBackend
import me.rerere.rikkahub.data.remote.IdentityRefused
import me.rerere.rikkahub.data.remote.IdentityUnreachable
import me.rerere.rikkahub.data.remote.IdentityUser
import me.rerere.rikkahub.data.remote.SignedIn
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a cold start lands, once the session has said who is signing in.
 *
 * The defect these pin: the navigation stack used to be seeded with sign-in
 * before [AccountSession.restore] had answered. A returning user who held a
 * valid credential was therefore pointed at the sign-in screen *as* the session
 * established them — and sign-in is refused for a signed-in identity, so the
 * app stranded them on the not-ready page with no way forward.
 *
 * The landing is a decision about a settled session, so these run the real
 * [AccountSession.restore] the activity runs, and then the same function
 * [me.rerere.rikkahub.RouteActivity] calls to seed the stack.
 */
class IdentityNavigationTest {

    /** A credential the device already holds, or none at all. */
    private class StoredCredential(private val token: String?) : CredentialStore {
        var cleared = false
            private set

        override suspend fun load(): String? = token

        override suspend fun save(token: String) = error("a cold start never writes a credential")

        override suspend fun clear() {
            cleared = true
        }
    }

    /** Answers the way a real backend would for this scenario. */
    private class ScriptedBackend(private val answer: suspend () -> IdentityUser) : IdentityBackend {
        override suspend fun signIn(name: String, password: String): SignedIn = error("not used")

        override suspend fun register(inviteCode: String, name: String, password: String): SignedIn =
            error("not used")

        override suspend fun createInvite(token: String): String = error("not used")

        override suspend fun session(token: String): IdentityUser = answer()

        override suspend fun signOut(token: String) = Unit
    }

    private fun landingAfterRestore(backend: IdentityBackend, store: CredentialStore): Screen =
        kotlinx.coroutines.runBlocking {
            val session = AccountSession(backend, store, onAccountChanged = {})
            session.restore()
            identityStartScreen(IdentityScope(signedIn = session.user != null, isAdmin = session.user?.isAdmin == true))
        }

    @Test
    fun `a saved credential the backend still accepts lands on the account`() = runTest {
        val restored = IdentityUser(shortId = 2, name = "second")

        val start = landingAfterRestore(
            backend = ScriptedBackend { restored },
            store = StoredCredential("token-second"),
        )

        assertEquals(Screen.Account, start)
    }

    @Test
    fun `no saved credential lands on sign-in`() = runTest {
        val store = StoredCredential(null)
        val backend = ScriptedBackend { error("the backend is never asked without a credential") }

        val start = landingAfterRestore(backend, store)

        assertEquals(Screen.SignIn, start)
        assertEquals("nothing was stored, so nothing may be cleared", false, store.cleared)
    }

    @Test
    fun `a credential the backend rejects lands on sign-in, not on the account`() = runTest {
        val store = StoredCredential("token-dead")

        val start = landingAfterRestore(
            backend = ScriptedBackend { throw IdentityRefused(401, "identity/unauthorized", "gone") },
            store = store,
        )

        assertEquals(Screen.SignIn, start)
        assertEquals("a refused credential must not survive the restart", true, store.cleared)
    }

    @Test
    fun `a backend that cannot be reached lands on sign-in and keeps the credential`() = runTest {
        val store = StoredCredential("token-offline")

        val start = landingAfterRestore(
            backend = ScriptedBackend {
                throw IdentityUnreachable(IllegalStateException("no network"))
            },
            store = store,
        )

        assertEquals(Screen.SignIn, start)
        assertEquals("being offline is not evidence the account is gone", false, store.cleared)
    }
}
