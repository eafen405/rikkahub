package me.rerere.rikkahub.service

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.remote.AccountSession
import me.rerere.rikkahub.data.remote.ChatHistoryPage
import me.rerere.rikkahub.data.remote.ChatSyncKey
import me.rerere.rikkahub.data.remote.ChatSyncRecord
import me.rerere.rikkahub.data.remote.ChatSyncStore
import me.rerere.rikkahub.data.remote.IdentityBackend
import me.rerere.rikkahub.data.remote.IdentityUser
import me.rerere.rikkahub.data.remote.InMemoryCredentialStore
import me.rerere.rikkahub.data.remote.OwnedSession
import me.rerere.rikkahub.data.remote.OwnedSessions
import me.rerere.rikkahub.data.remote.RemoteChatBackend
import me.rerere.rikkahub.data.remote.RemoteChatProjection
import me.rerere.rikkahub.data.remote.RemoteChatState
import me.rerere.rikkahub.data.remote.RemoteReceivePlatform
import me.rerere.rikkahub.data.remote.RemoteReceiveState
import me.rerere.rikkahub.data.remote.SessionBackend
import me.rerere.rikkahub.data.remote.SignedIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reception ownership: the app-wide foreground answer decides whether the account
 * is observed and whether the reception service is asked for.
 *
 * The foreground fact has one owner — the platform object publication also reads
 * — so these tests drive it directly. A second copy inside the controller would
 * be a decision made from a stale answer, which is exactly what they pin down.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteReceiveControllerTest {

    /** The platform double: the one foreground answer, the receive switch, and what publication would do. */
    private class Platform(foreground: Boolean) : RemoteReceivePlatform {
        private val mutableEnabled = MutableStateFlow(false)
        override val enabled: StateFlow<Boolean> = mutableEnabled
        private var foreground = foreground
        override fun isForeground() = foreground
        override fun setEnabled(value: Boolean) {
            mutableEnabled.value = value
        }

        fun appForeground(value: Boolean) {
            foreground = value
        }
    }

    /** Records what Android was asked to do about the service. */
    private class Host : RemoteReceiveServiceHost {
        var starts = 0
        var stops = 0
        var refusal: RuntimeException? = null
        override fun start() {
            refusal?.let { throw it }
            starts++
        }

        override fun stop() {
            stops++
        }
    }

    /** A process lifecycle the test moves, standing in for ProcessLifecycleOwner. */
    private class Screen : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry

        fun start() = registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        fun stop() = registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    private class Identity : IdentityBackend {
        override suspend fun signIn(name: String, password: String) = SignedIn("token-$name", IdentityUser(1, name))
        override suspend fun register(inviteCode: String, name: String, password: String) = error("not used")
        override suspend fun createInvite(token: String) = error("not used")
        override suspend fun session(token: String) = IdentityUser(1, "alice")
        override suspend fun signOut(token: String) = Unit
    }

    private class Sessions : SessionBackend {
        override suspend fun list(token: String) =
            OwnedSessions(listOf(OwnedSession("$token-main", primary = true)), "$token-main")
        override suspend fun create(token: String) = error("not used")
        override suspend fun select(token: String, sessionId: String) = error("not used")
        override suspend fun primary(token: String) = error("not used")
        override suspend fun read(token: String, sessionId: String) = error("not used")
    }

    private class Store : ChatSyncStore {
        private val records = mutableMapOf<ChatSyncKey, ChatSyncRecord>()
        override suspend fun load(key: ChatSyncKey) = records[key] ?: ChatSyncRecord(0, true, notificationThrough = 0)
        override suspend fun save(key: ChatSyncKey, record: ChatSyncRecord) {
            records[key] = record
        }
    }

    /** Opens and closes observers, which is what "the account is observed" means here. */
    private class Chats : RemoteChatBackend {
        val opened = mutableListOf<Pair<String, String>>()
        val closed = mutableListOf<String>()
        private val updates = mutableMapOf<String, MutableSharedFlow<RemoteChatProjection>>()
        override fun observe(token: String, sessionId: String) = updates.getOrPut(sessionId) { MutableSharedFlow() }
            .onStart { opened += token to sessionId }
            .onCompletion { closed += sessionId }
        override suspend fun checkpoint(token: String, sessionId: String) = 100L
        override suspend fun page(token: String, sessionId: String, throughSeq: Long, beforeSeq: Long): ChatHistoryPage = error("not used")
        override suspend fun read(token: String, sessionId: String) = error("not used")
        override suspend fun send(token: String, sessionId: String, requestId: String, text: String, uploadIds: List<String>) = error("not used")
        override suspend fun receipt(token: String, sessionId: String, requestId: String) = null
        override suspend fun stop(token: String, sessionId: String) = error("not used")
    }

    private class Harness(scope: CoroutineScope, foreground: Boolean, enabled: Boolean = false) {
        val platform = Platform(foreground).apply { setEnabled(enabled) }
        val host = Host()
        val chats = Chats()
        val screens = Screen()
        val receiver = RemoteReceiveState(Sessions(), scope, {
            RemoteChatState(chats, scope, Store(), "https://fairy")
        })
        // The same wiring IdentityModule gives the app: the identity publisher binds reception.
        val account = AccountSession(Identity(), InMemoryCredentialStore(), onAccountChanged = receiver::bindIdentity)
        val controller = RemoteReceiveController(account, receiver, platform, host, screens.lifecycle, scope)
    }

    @Test
    fun `a decision taken between lifecycle callbacks follows the platform, not a remembered copy`() = runTest {
        val harness = Harness(backgroundScope, foreground = true, enabled = true)
        harness.account.signIn("alice", "pw")
        runCurrent()
        assertEquals(listOf("token-alice" to "token-alice-main"), harness.chats.opened)
        harness.controller.serviceStarted()
        runCurrent()
        assertTrue(harness.controller.status.value.running)

        // The platform already answers "background"; the process lifecycle has not yet
        // dispatched its stop to this controller. The service ending here decides the
        // account's observation, and only the platform's own answer can be believed.
        harness.platform.appForeground(false)
        harness.controller.serviceStopped("系统已停止后台接收")
        runCurrent()

        assertEquals(listOf("token-alice-main"), harness.chats.closed)
    }

    @Test
    fun `observation runs while the app is in front or its service is`() = runTest {
        val harness = Harness(backgroundScope, foreground = false, enabled = true)
        runCurrent()
        harness.platform.appForeground(true)
        harness.screens.start()
        runCurrent()
        harness.account.signIn("alice", "pw")
        runCurrent()
        assertEquals(1, harness.host.starts)
        assertEquals(listOf("token-alice" to "token-alice-main"), harness.chats.opened)

        harness.controller.serviceStarted()
        runCurrent()
        harness.platform.appForeground(false)
        harness.screens.stop()
        runCurrent()
        assertEquals(emptyList<String>(), harness.chats.closed)

        harness.controller.serviceStopped("系统已停止后台接收")
        runCurrent()
        assertEquals(listOf("token-alice-main"), harness.chats.closed)

        harness.platform.appForeground(true)
        harness.screens.start()
        runCurrent()
        assertEquals(2, harness.chats.opened.size)
        assertEquals(2, harness.host.starts)
    }

    @Test
    fun `reception starts with the account and stops when it is gone`() = runTest {
        val harness = Harness(backgroundScope, foreground = true)
        runCurrent()
        assertEquals(0, harness.host.starts)

        harness.controller.setEnabled(true)
        runCurrent()
        assertEquals(0, harness.host.starts)
        assertTrue(harness.platform.enabled.value)
        assertEquals(emptyList<Pair<String, String>>(), harness.chats.opened)

        harness.account.signIn("alice", "pw")
        runCurrent()
        assertEquals(1, harness.host.starts)
        assertEquals(listOf("token-alice" to "token-alice-main"), harness.chats.opened)

        // Signing out ends reception even though the receive switch stays on.
        val stopsBeforeSignOut = harness.host.stops
        harness.account.signOut()
        runCurrent()
        assertEquals(stopsBeforeSignOut + 1, harness.host.stops)
        assertEquals(listOf("token-alice-main"), harness.chats.closed)
        assertFalse(harness.controller.status.value.running)
    }

    @Test
    fun `a refused service start is reported and leaves reception stopped`() = runTest {
        val harness = Harness(backgroundScope, foreground = true, enabled = true)
        harness.host.refusal = RuntimeException("restricted")
        harness.account.signIn("alice", "pw")
        runCurrent()

        assertFalse(harness.controller.status.value.running)
        assertEquals("系统拒绝后台接收：restricted", harness.controller.status.value.message)
    }

    /**
     * The account is the only thing that decides whose list reception holds. An identity is
     * published before any collector can observe it, so a page recomposing after sign-in must
     * never be handed the previous account's sessions while a collector is still queued.
     */
    @Test
    fun `an account change binds reception as it is published, before any collector runs`() = runTest {
        val harness = Harness(backgroundScope, foreground = true, enabled = true)
        runCurrent()
        harness.account.signIn("alice", "pw")
        runCurrent()
        assertEquals(listOf("token-alice-main"), harness.receiver.owned.snapshot.value.sessions.map { it.id })

        // No dispatcher turn between publication and this read: the app already acts as bob.
        harness.account.signIn("bob", "pw")
        assertEquals("token-bob", harness.account.token)
        assertTrue(
            "the previous account's list must not survive the new account's publication",
            harness.receiver.owned.snapshot.value.sessions.isEmpty(),
        )
        assertNull(harness.receiver.stateForSession("token-alice-main"))

        runCurrent()
        assertEquals(listOf("token-bob-main"), harness.receiver.owned.snapshot.value.sessions.map { it.id })
        assertEquals(listOf("token-bob" to "token-bob-main"), harness.chats.opened.drop(1))
    }

    /** Sign-out is published the same way, so the list is gone before the lock is released. */
    @Test
    fun `signing out retires the account's list in the same turn`() = runTest {
        val harness = Harness(backgroundScope, foreground = true, enabled = true)
        harness.account.signIn("alice", "pw")
        runCurrent()
        assertEquals(listOf("token-alice-main"), harness.receiver.owned.snapshot.value.sessions.map { it.id })

        harness.account.signOut()
        assertNull(harness.account.token)
        assertTrue(
            "a signed-out app must not keep showing the last account's list",
            harness.receiver.owned.snapshot.value.sessions.isEmpty(),
        )
        assertNull(harness.receiver.selectedChat.value)
    }
}
