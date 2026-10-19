package me.rerere.rikkahub.ui.identity

import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import androidx.navigation3.runtime.serialization.NavKeySerializer
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.remote.AccountSession
import me.rerere.rikkahub.data.remote.CredentialStore
import me.rerere.rikkahub.data.remote.IdentityBackend
import me.rerere.rikkahub.data.remote.IdentityRefused
import me.rerere.rikkahub.data.remote.IdentityUser
import me.rerere.rikkahub.data.remote.SignedIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where a cold start lands once the session has said who is signing in.
 *
 * The stack is seeded before [AccountSession.restore] can have answered, so it
 * starts on sign-in; a returning user whose credential is still good must not be
 * left there. These run the real [restoreIdentitySession] the activity calls,
 * against a real session whose backend answers only when the test releases it.
 */
class IdentityNavigationTest {

    @Test
    fun `sessions destination survives the navigation library back stack save and restore`() {
        val serializer = NavBackStackSerializer(NavKeySerializer<NavKey>())
        val original = NavBackStack<NavKey>(Screen.Home, Screen.Sessions)
        val saved = Json.encodeToString(serializer, original)
        val restored = Json.decodeFromString(serializer, saved)
        assertEquals(listOf(Screen.Home, Screen.Sessions), restored.toList())
    }

    /** A credential the device already holds, or none at all. */
    private class StoredCredential(private val token: String?) : CredentialStore {
        var saved: String? = null
            private set
        var cleared = false
            private set

        override suspend fun load(): String? = token

        override suspend fun save(token: String) {
            saved = token
        }

        override suspend fun clear() {
            cleared = true
        }
    }

    /** A backend whose session answer the test releases, or that fails as scripted. */
    private class ScriptedBackend(
        private val session: suspend () -> IdentityUser,
        private val signIn: suspend () -> SignedIn = { error("sign-in is not part of this scenario") },
    ) : IdentityBackend {
        override suspend fun signIn(name: String, password: String): SignedIn = signIn()

        override suspend fun register(inviteCode: String, name: String, password: String): SignedIn =
            error("not used")

        override suspend fun createInvite(token: String): String = error("not used")

        override suspend fun session(token: String): IdentityUser = session()

        override suspend fun signOut(token: String) = Unit
    }

    /** The stack a cold start seeds, before the session has answered. */
    private fun seededStack(): MutableList<NavKey> = mutableListOf(Screen.SignIn)

    @Test
    fun `a remembered credential replaces the seeded sign-in stack once it is accepted`() = runTest {
        val answer = CompletableDeferred<IdentityUser>()
        val store = StoredCredential("token-remembered")
        val session = AccountSession(
            backend = ScriptedBackend(session = { answer.await() }),
            store = store,
            onAccountChanged = {},
        )
        val stack = seededStack()

        val startup = launch { restoreIdentitySession(session, stack) }
        runCurrent()

        assertEquals("nothing is known before the session answers", listOf(Screen.SignIn), stack)
        assertNull("no credential may be written while restoring", store.saved)

        answer.complete(IdentityUser(shortId = 2, name = "second"))
        startup.join()

        assertEquals(listOf(Screen.Home), stack)
    }

    @Test
    fun `no remembered credential leaves the stack on sign-in`() = runTest {
        val session = AccountSession(
            backend = ScriptedBackend(session = { error("the backend is never asked without a credential") }),
            store = StoredCredential(null),
            onAccountChanged = {},
        )
        val stack = seededStack()

        restoreIdentitySession(session, stack)

        assertEquals(listOf(Screen.SignIn), stack)
    }

    @Test
    fun `a credential the backend rejects leaves the stack on sign-in`() = runTest {
        val store = StoredCredential("token-dead")
        val session = AccountSession(
            backend = ScriptedBackend(session = { throw IdentityRefused(401, "identity/unauthorized", "gone") }),
            store = store,
            onAccountChanged = {},
        )
        val stack = seededStack()

        restoreIdentitySession(session, stack)

        assertEquals(listOf(Screen.SignIn), stack)
        assertEquals("a refused credential must not survive the restart", true, store.cleared)
    }

    @Test
    fun `a restore that answers after a newer sign-in does not override it`() = runTest {
        val restoreAnswer = CompletableDeferred<IdentityUser>()
        val newer = IdentityUser(shortId = 7, name = "newer")
        val session = AccountSession(
            backend = ScriptedBackend(
                session = { restoreAnswer.await() },
                signIn = { SignedIn("token-newer", newer) },
            ),
            store = StoredCredential("token-old"),
            onAccountChanged = {},
        )
        val stack = seededStack()

        val startup = launch { restoreIdentitySession(session, stack) }
        runCurrent()

        session.signIn("newer", "pw")
        restoreAnswer.complete(IdentityUser(shortId = 1, name = "old"))
        startup.join()

        assertEquals("the late restore must not take over the account", newer, session.user)
        assertEquals(listOf(Screen.Home), stack)
    }
}
