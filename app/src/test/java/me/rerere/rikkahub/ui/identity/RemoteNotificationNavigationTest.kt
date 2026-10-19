package me.rerere.rikkahub.ui.identity

import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.remote.*
import org.junit.Assert.*
import org.junit.Test

/** Public restore/navigation seam with account/network boundaries controlled by the test. */
class RemoteNotificationNavigationTest {
    private class Credentials : CredentialStore {
        var token: String? = "alice"
        override suspend fun load() = token
        override suspend fun save(token: String) { this.token = token }
        override suspend fun clear() { token = null }
    }
    private class Identity : IdentityBackend {
        override suspend fun session(token: String) = IdentityUser(2, "Alice")
        override suspend fun signIn(name: String, password: String) = SignedIn(name, IdentityUser(if (name == "alice") 2 else 3, name))
        override suspend fun register(inviteCode: String, name: String, password: String) = error("unused")
        override suspend fun signOut(token: String) = Unit
        override suspend fun createInvite(token: String) = error("unused")
    }

    @Test fun `cold restore settles before a qualified notification opens its owned session`() = runTest {
        val account = AccountSession(Identity(), Credentials())
        val stack = mutableListOf<NavKey>(Screen.SignIn)
        val target = RemoteNotificationTarget("https://fairy", 2, "s2")
        var opened: String? = null
        assertFalse(openRemoteNotification(target, "https://fairy", account) { opened = it; true })
        restoreIdentitySession(account, stack)
        assertEquals(listOf(Screen.Home), stack)
        assertTrue(openRemoteNotification(target, "https://fairy", account) { opened = it; true })
        assertEquals("s2", opened)
    }

    @Test fun `another account or deployment never initiates a session query`() = runTest {
        val account = AccountSession(Identity(), Credentials()); account.restore()
        var queried = false
        assertFalse(openRemoteNotification(RemoteNotificationTarget("https://other", 2, "s"), "https://fairy", account) { queried = true; true })
        assertFalse(openRemoteNotification(RemoteNotificationTarget("https://fairy", 3, "s"), "https://fairy", account) { queried = true; true })
        assertFalse(queried)
    }

    @Test fun `ownership refusal cannot navigate and late old account answer cannot navigate`() = runTest {
        val account = AccountSession(Identity(), Credentials()); account.restore()
        val target = RemoteNotificationTarget("https://fairy", 2, "s")
        assertFalse(openRemoteNotification(target, "https://fairy", account) { false })
        val gate = CompletableDeferred<Boolean>()
        val pending = async { openRemoteNotification(target, "https://fairy", account) { gate.await() } }
        runCurrent()
        account.signIn("bob", "pw")
        gate.complete(true)
        assertFalse(pending.await())
        assertEquals(3, account.user!!.shortId)
    }
}
