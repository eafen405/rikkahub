package me.rerere.rikkahub.data.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteReceiveStateTest {
    private class Sessions : SessionBackend {
        var gate: CompletableDeferred<Unit>? = null
        val authorized = mutableListOf<Pair<String, String>>()
        override suspend fun list(token: String): OwnedSessions {
            gate?.let { withContext(NonCancellable) { it.await() } }
            return OwnedSessions(listOf(OwnedSession("$token-main", primary = true), OwnedSession("$token-other")), "$token-main")
        }
        override suspend fun read(token: String, sessionId: String): OwnedSession {
            authorized += token to sessionId
            if (!sessionId.startsWith(token)) throw IdentityRefused(403, "session/forbidden", "not yours")
            return OwnedSession(sessionId)
        }
        override suspend fun select(token: String, sessionId: String) = read(token, sessionId)
        override suspend fun create(token: String) = OwnedSession("$token-created")
        override suspend fun primary(token: String) = OwnedSession("$token-main", primary = true)
    }
    private class Store : ChatSyncStore {
        val records = mutableMapOf<ChatSyncKey, ChatSyncRecord>()
        override suspend fun load(key: ChatSyncKey) = records[key] ?: ChatSyncRecord(0, true, notificationThrough = 0)
        override suspend fun save(key: ChatSyncKey, record: ChatSyncRecord) { records[key] = record }
    }
    private class Chats : RemoteChatBackend {
        val opens = mutableListOf<Pair<String, String>>()
        val closed = mutableListOf<String>()
        val updates = mutableMapOf<String, MutableSharedFlow<RemoteChatProjection>>()
        override fun observe(token: String, sessionId: String) = updates.getOrPut(sessionId) { MutableSharedFlow() }
            .onStart { opens += token to sessionId }.onCompletion { closed += sessionId }
        override suspend fun checkpoint(token: String, sessionId: String) = 100L
        override suspend fun page(token: String, sessionId: String, throughSeq: Long, beforeSeq: Long) = error("not used")
        override suspend fun read(token: String, sessionId: String) = error("not used")
        override suspend fun send(token: String, sessionId: String, requestId: String, text: String, uploadIds: List<String>) = error("not used")
        override suspend fun receipt(token: String, sessionId: String, requestId: String): ChatReceipt? = null
        override suspend fun stop(token: String, sessionId: String) = error("receive teardown must not stop backend")
    }
    @Test fun `account receiver observes every listed session and selected page shares its runtime`() = runTest {
        val backend = Chats()
        val state = RemoteReceiveState(Sessions(), backgroundScope) { RemoteChatState(backend, backgroundScope, Store(), "https://fairy") }
        state.bind("alice", 1); runCurrent()
        assertEquals(setOf("alice-main", "alice-other"), backend.opens.map { it.second }.toSet())
        val main = state.stateForSession("alice-main")
        val other = state.stateForSession("alice-other")
        assertSame(main, state.selectedChat.value)
        state.select("alice-other"); runCurrent()
        assertSame(other, state.selectedChat.value)
        assertEquals(2, backend.opens.size)
        state.stopObservation(); runCurrent()
        assertEquals(setOf("alice-main", "alice-other"), backend.closed.toSet())
        state.resumeObservation(); runCurrent()
        assertSame(other, state.selectedChat.value)
        assertEquals(4, backend.opens.size)
    }
    @Test fun `switching accounts retires old observers and ignores late list results`() = runTest {
        val sessions = Sessions().apply { gate = CompletableDeferred() }
        val backend = Chats()
        val state = RemoteReceiveState(sessions, backgroundScope) { RemoteChatState(backend, backgroundScope, Store(), "https://fairy") }
        state.bind("alice", 1); runCurrent()
        state.bind("bob", 2); runCurrent()
        sessions.gate!!.complete(Unit); runCurrent()
        assertEquals(listOf("bob-main", "bob-other"), state.sessions.value.sessions.map { it.id })
        assertNull(state.stateForSession("alice-main"))
        assertTrue(backend.opens.all { it.first == "bob" })
        state.bind(null, null); runCurrent()
        assertNull(state.selectedChat.value)
        assertTrue(state.sessions.value.sessions.isEmpty())
    }
    @Test fun `notification waits for initial list and authenticates target before selecting it`() = runTest {
        val sessions = Sessions().apply { gate = CompletableDeferred() }
        val backend = Chats()
        val state = RemoteReceiveState(sessions, backgroundScope) { RemoteChatState(backend, backgroundScope, Store(), "https://fairy") }
        state.bind("alice", 1); runCurrent()
        val open = async { state.openNotification("alice-other") }; runCurrent()
        assertFalse(open.isCompleted)
        sessions.gate!!.complete(Unit); runCurrent()
        assertTrue(open.await())
        assertEquals("alice-other", state.sessions.value.selectedId)
        assertSame(state.stateForSession("alice-other"), state.selectedChat.value)
        assertFalse(state.openNotification("bob-private"))
        assertEquals("alice-other", state.sessions.value.selectedId)
        assertEquals(listOf("alice" to "alice-other", "alice" to "bob-private"), sessions.authorized)
    }    @Test fun `one sessions long task does not block another sessions notification and each uses its own position`() = runTest {
        val store = Store()
        val backend = Chats()
        val alerts = mutableListOf<ChatSyncKey>()
        val platform = object : RemoteNotificationPlatform {
            override fun isForeground() = false
            override fun publish(key: ChatSyncKey, preview: String) { alerts += key }
        }
        val receiver = RemoteReceiveState(Sessions(), backgroundScope) {
            RemoteChatState(backend, backgroundScope, store, "https://fairy", notifications = platform)
        }
        receiver.bind("alice", 1); runCurrent()
        backend.updates.getValue("alice-main").emit(RemoteNotificationStateTest.delivery(1).copy(sessionId = "alice-main",
            activity = RemoteActivity(2, listOf(2), "alice-main/1", 1, 1))); runCurrent()
        backend.updates.getValue("alice-other").emit(RemoteNotificationStateTest.delivery(2).copy(sessionId = "alice-other")); runCurrent()
        assertEquals(listOf("alice-other"), alerts.map { it.sessionId })
        backend.updates.getValue("alice-main").emit(RemoteNotificationStateTest.delivery(3, 2).copy(sessionId = "alice-main", throughSeq = 1,
            activity = RemoteActivity(2, listOf(2, 4), "alice-main/1", 1, 3, delivery = RemoteActivityDelivery("alice-main/1", 3)))); runCurrent()
        assertEquals(listOf("alice-other", "alice-main"), alerts.map { it.sessionId })
        backend.updates.getValue("alice-main").emit(RemoteNotificationStateTest.delivery(4, 3).copy(sessionId = "alice-main", throughSeq = 1,
            activity = RemoteActivity(1, episodeId = "alice-main/1", throughSeq = 4,
                completed = RemoteCompletedActivity("alice-main/1", 1, 4, 3), delivery = RemoteActivityDelivery("alice-main/1", 3)))); runCurrent()
        assertEquals(2, alerts.size)
        assertEquals(2, alerts.toSet().size)
    }

}
