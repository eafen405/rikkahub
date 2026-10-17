package me.rerere.rikkahub.data.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/**
 * Presentation and notification answer "is this snapshot established?" with one value.
 * Either both may state the activity, or neither may: an incomplete observation is unknown,
 * never silence, and never a sound the panel does not back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteActivityTrustTest {
    private val key = ChatSyncKey("https://fairy", 1, "s")
    private class Store : ChatSyncStore {
        private var record = ChatSyncRecord(0, true, notificationThrough = 0)
        var saveGate: CompletableDeferred<Unit>? = null
        override suspend fun load(key: ChatSyncKey) = record
        override suspend fun save(key: ChatSyncKey, record: ChatSyncRecord) {
            saveGate?.let { withContext(NonCancellable) { it.await() } }
            this.record = record
        }
    }
    private class Platform : RemoteNotificationPlatform {
        val alerts = mutableListOf<Pair<ChatSyncKey, String>>()
        var before: (() -> Unit)? = null
        override fun isForeground() = false
        override fun publish(key: ChatSyncKey, preview: String) {
            before?.invoke()
            alerts += key to preview
        }
    }
    /** The account's one session, so reception binds it and owns when the reminder appears. */
    private class Sessions : SessionBackend {
        override suspend fun list(token: String) = OwnedSessions(listOf(OwnedSession("s", primary = true)), "s")
        override suspend fun read(token: String, sessionId: String) = OwnedSession(sessionId)
        override suspend fun select(token: String, sessionId: String) = read(token, sessionId)
        override suspend fun create(token: String) = error("not used")
        override suspend fun primary(token: String) = OwnedSession("s", primary = true)
    }
    private class Backend : RemoteChatBackend {
        val updates = MutableSharedFlow<RemoteChatProjection>(extraBufferCapacity = 10)
        var acknowledged = 100L
        var checkpointGate: CompletableDeferred<Unit>? = null
        override fun observe(token: String, sessionId: String) = updates
        override suspend fun checkpoint(token: String, sessionId: String): Long {
            checkpointGate?.await()
            return acknowledged
        }
        override suspend fun page(token: String, sessionId: String, throughSeq: Long, beforeSeq: Long): ChatHistoryPage {
            return ChatHistoryPage(sessionId, throughSeq, 0, events = eventCoverage(0, beforeSeq - 1))
        }
        override suspend fun read(token: String, sessionId: String) = error("not used")
        override suspend fun send(token: String, sessionId: String, requestId: String, text: String, uploadIds: List<String>) = error("not used")
        override suspend fun receipt(token: String, sessionId: String, requestId: String): ChatReceipt? = null
        override suspend fun stop(token: String, sessionId: String) = error("receiver never stops backend work")
    }

    private fun kotlinx.coroutines.test.TestScope.bind(
        store: ChatSyncStore,
        backend: RemoteChatBackend,
        platform: RemoteNotificationPlatform,
    ): RemoteChatState {
        val receiver = RemoteReceiveState(Sessions(), backgroundScope, { settle ->
            RemoteChatState(backend, backgroundScope, store, "https://fairy", settleReminders = settle)
        }, platform)
        receiver.bind("alice", 1); runCurrent()
        return requireNotNull(receiver.stateForSession("s"))
    }

    @Test fun `a reminder reports the activity the panel already presents`() = runTest {
        val store = Store()
        val platform = Platform()
        val backend = Backend()
        val state = bind(store, backend, platform)
        var presented: RemoteActivity? = null
        platform.before = { presented = state.snapshot.value.activity }
        backend.updates.emit(deliveryProjection(1)); runCurrent()
        assertEquals(1, platform.alerts.size)
        assertEquals(1, presented?.status)
        assertEquals("s/1", presented?.completed?.episodeId)
    }

    @Test fun `the writer barrier settles presentation and reminder through one value`() = runTest {
        val store = Store()
        val platform = Platform()
        val backend = Backend().apply { checkpointGate = CompletableDeferred() }
        val state = bind(store, backend, platform)
        backend.updates.emit(deliveryProjection(1)); runCurrent()
        // Waiting for the writer barrier: the panel cannot state the activity and nothing is settled.
        assertEquals(0, state.snapshot.value.activity.status)
        assertTrue(platform.alerts.isEmpty())
        assertEquals(0L, store.load(key).notificationThrough)
        backend.checkpointGate!!.complete(Unit); runCurrent()
        // The same commit establishes both.
        assertEquals(1, state.snapshot.value.activity.status)
        assertEquals(listOf("delivered1"), platform.alerts.map { it.second })
        assertEquals(1L, store.load(key).notificationThrough)
    }

    @Test fun `activity beyond the committed watermark stays unknown and silent`() = runTest {
        val store = Store()
        val platform = Platform()
        // The writer has only acknowledged seq 0 while the reported run claims coverage through 1.
        val backend = Backend().apply { acknowledged = 0 }
        val state = bind(store, backend, platform)
        backend.updates.emit(deliveryProjection(1)); runCurrent()
        assertEquals(0, state.snapshot.value.activity.status)
        assertTrue(platform.alerts.isEmpty())
        assertEquals(0L, store.load(key).notificationThrough)
    }

    @Test fun `detaching during the commit leaves the panel unknown and publishes nothing`() = runTest {
        val store = Store()
        val platform = Platform()
        val backend = Backend()
        val state = bind(store, backend, platform)
        val gate = CompletableDeferred<Unit>()
        store.saveGate = gate
        backend.updates.emit(deliveryProjection(1)); runCurrent()
        state.detach(); runCurrent()
        gate.complete(Unit); runCurrent()
        // The commit still lands, so the backlog is not replayed; the abandoned snapshot publishes nothing.
        assertTrue(platform.alerts.isEmpty())
        assertEquals(0, state.snapshot.value.activity.status)
        assertEquals(1L, store.load(key).notificationThrough)
    }

}
