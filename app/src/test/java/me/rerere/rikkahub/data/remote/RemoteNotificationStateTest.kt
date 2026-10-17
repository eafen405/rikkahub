package me.rerere.rikkahub.data.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

/**
 * A session only records the episode it owes the reminder; reception sees every session and
 * publishes the backlog once. These tests therefore drive the session through reception, because
 * that module owns when a reminder appears.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteNotificationStateTest {
    private class Store : ChatSyncStore {
        val records = mutableMapOf<ChatSyncKey, ChatSyncRecord>()
        var saveGate: CompletableDeferred<Unit>? = null
        var failSave = false
        override suspend fun load(key: ChatSyncKey) = records[key] ?: ChatSyncRecord()
        override suspend fun save(key: ChatSyncKey, record: ChatSyncRecord) {
            saveGate?.let { withContext(NonCancellable) { it.await() } }
            if (failSave) throw IOException("disk unavailable before consumption")
            records[key] = record
        }
    }
    private class Platform : RemoteNotificationPlatform {
        var foreground = false
        val alerts = mutableListOf<Pair<ChatSyncKey, String>>()
        var before: (() -> Unit)? = null
        var crashBefore = false
        var crashAfter = false
        override fun isForeground() = foreground
        override fun publish(key: ChatSyncKey, preview: String) {
            before?.invoke()
            if (crashBefore) throw IOException("process lost before notify")
            alerts += key to preview
            if (crashAfter) throw IOException("process lost after notify")
        }
    }
    private class Backend : RemoteChatBackend {
        val updates = MutableSharedFlow<RemoteChatProjection>(extraBufferCapacity = 10)
        var pageGate: CompletableDeferred<Unit>? = null
        var checkpointGate: CompletableDeferred<Unit>? = null
        var failCheckpoint = false
        override fun observe(token: String, sessionId: String) = updates
        override suspend fun checkpoint(token: String, sessionId: String): Long {
            checkpointGate?.await()
            if (failCheckpoint) throw IOException("writer not flushed")
            return 100L
        }
        override suspend fun page(token: String, sessionId: String, throughSeq: Long, beforeSeq: Long): ChatHistoryPage {
            pageGate?.await()
            return ChatHistoryPage(sessionId, throughSeq, 0, events = (0 until beforeSeq).map(::event))
        }
        override suspend fun read(token: String, sessionId: String) = error("not used")
        override suspend fun send(token: String, sessionId: String, requestId: String, text: String, uploadIds: List<String>) = error("not used")
        override suspend fun receipt(token: String, sessionId: String, requestId: String): ChatReceipt? = null
        override suspend fun stop(token: String, sessionId: String) = error("receiver never stops backend work")
    }

    /** The account's session list: reception binds these sessions, the test does not. */
    private class Sessions(
        private val ids: Map<String, String> = mapOf("alice" to "s", "bob" to "bob-session"),
    ) : SessionBackend {
        override suspend fun list(token: String) = OwnedSessions(
            listOf(OwnedSession(ids.getValue(token), primary = true)), ids.getValue(token),
        )
        override suspend fun read(token: String, sessionId: String) = OwnedSession(sessionId)
        override suspend fun select(token: String, sessionId: String) = read(token, sessionId)
        override suspend fun create(token: String) = error("not used")
        override suspend fun primary(token: String) = OwnedSession(ids.getValue(token), primary = true)
    }

    private fun TestScope.received(
        store: ChatSyncStore,
        backend: RemoteChatBackend,
        platform: Platform,
        enabled: () -> Boolean = { true },
    ) = RemoteReceiveState(Sessions(), backgroundScope, { settle ->
        RemoteChatState(backend, backgroundScope, store, "https://fairy", notifies = settle)
    }, platform, enabled)

    private fun TestScope.opened(
        store: ChatSyncStore,
        backend: RemoteChatBackend,
        platform: Platform,
        enabled: () -> Boolean = { true },
        user: String = "alice",
        userShortId: Int = 1,
        sessionId: String = "s",
    ): RemoteChatState {
        val receiver = received(store, backend, platform, enabled)
        receiver.bind(user, userShortId); runCurrent()
        return requireNotNull(receiver.stateForSession(sessionId))
    }

    @Test fun `background delivery is consumed once before platform publication`() = runTest {
        val store = Store()
        val key = ChatSyncKey("https://fairy", 1, "s")
        store.records[key] = ChatSyncRecord(0, true, notificationThrough = 0)
        val platform = Platform()
        val backend = Backend()
        val state = opened(store, backend, platform)
        backend.updates.emit(delivery(1)); runCurrent()
        assertEquals(listOf(key to "delivered1"), platform.alerts)
        assertEquals(1L, store.records[key]?.notificationThrough)
        assertEquals("s/1", store.records[key]?.notificationEpisodeId)
        assertFalse(store.records.getValue(key).notificationOwed)
        state.retrySync(); runCurrent()
        assertEquals(1, platform.alerts.size)
    }
    @Test fun `first device and legacy sync-only records baseline history then allow a later episode`() = runTest {
        val platform = Platform()
        val store = Store()
        val first = ChatSyncKey("https://fairy", 1, "s")
        // This old valid sync record predates notifications; it is not a notification watermark.
        store.records[first] = Json.decodeFromString<ChatSyncRecord>("""{"consumedThrough":3,"initialized":true}""")
        val backend = Backend()
        val state = opened(store, backend, platform)
        backend.updates.emit(delivery(5)); runCurrent()
        assertTrue(platform.alerts.isEmpty())
        assertEquals(5L, store.records[first]?.notificationThrough)
        backend.updates.emit(delivery(7, 2).copy(throughSeq = 5)); runCurrent()
        assertEquals(listOf(first to "delivered7"), platform.alerts)
        // unreadable/missing local consumption restores the same empty record.
        state.detach()
        store.records.clear()
        val reset = opened(store, backend, platform)
        backend.updates.emit(delivery(9)); runCurrent()
        assertEquals(1, platform.alerts.size)
        assertEquals(9L, store.records[first]?.notificationThrough)
        assertNotNull(reset)
    }
    @Test fun `active delivery and its final completion share one reminder while the next episode can alert`() = runTest {
        val store = Store()
        val key = ChatSyncKey("https://fairy", 1, "s")
        store.records[key] = ChatSyncRecord(0, true, notificationThrough = 0)
        val platform = Platform()
        val backend = Backend()
        opened(store, backend, platform)
        backend.updates.emit(delivery(2).copy(activity = RemoteActivity(2, listOf(2, 4), "s/1", 1, 2,
            delivery = RemoteActivityDelivery("s/1", 2)))); runCurrent()
        assertEquals(1, platform.alerts.size)
        backend.updates.emit(delivery(4, 2).copy(throughSeq = 2, activity = RemoteActivity(1, episodeId = "s/1", throughSeq = 4,
            completed = RemoteCompletedActivity("s/1", 1, 4, 3), delivery = RemoteActivityDelivery("s/1", 2)))); runCurrent()
        assertEquals(1, platform.alerts.size)
        backend.updates.emit(delivery(6, 3).copy(throughSeq = 2, activity = RemoteActivity(1, episodeId = "s/5", throughSeq = 6,
            completed = RemoteCompletedActivity("s/5", 5, 6, 5), delivery = RemoteActivityDelivery("s/1", 2)))); runCurrent()
        assertEquals(listOf("delivered2", "delivered6"), platform.alerts.map { it.second })
    }
    @Test fun `reconnect waits for catchup and writer ack then merges backlog without guessing unknown as quiet`() = runTest {
        val store = Store()
        val key = ChatSyncKey("https://fairy", 1, "s")
        store.records[key] = ChatSyncRecord(0, true, notificationThrough = 0)
        val platform = Platform()
        val backend = Backend().apply { pageGate = CompletableDeferred(); checkpointGate = CompletableDeferred() }
        val state = opened(store, backend, platform)
        backend.updates.emit(delivery(8).copy(fromSeq = 6, events = (6L..8).map(::event))); runCurrent()
        assertTrue(platform.alerts.isEmpty())
        backend.updates.emit(delivery(10, 2).copy(throughSeq = 8, fromSeq = 6, events = (6L..10).map(::event),
            activity = RemoteActivity(0, throughSeq = 10, completed = RemoteCompletedActivity("s/9", 9, 10, 9)))); runCurrent()
        backend.pageGate!!.complete(Unit); runCurrent()
        assertTrue(platform.alerts.isEmpty())
        backend.checkpointGate!!.complete(Unit); runCurrent()
        assertTrue(platform.alerts.isEmpty())
        assertEquals(0L, store.records[key]?.notificationThrough)
        backend.updates.emit(delivery(10, 3).copy(throughSeq = 8)); runCurrent()
        assertEquals(listOf(key to "delivered10"), platform.alerts)
        state.retrySync(); runCurrent()
        assertEquals(1, platform.alerts.size)
    }
    @Test fun `foreground at awaited publication consumes without replay when the application later backgrounds`() = runTest {
        val store = Store()
        val key = ChatSyncKey("https://fairy", 1, "s")
        store.records[key] = ChatSyncRecord(0, true, notificationThrough = 0)
        val platform = Platform()
        val backend = Backend()
        val state = opened(store, backend, platform)
        val gate = CompletableDeferred<Unit>(); store.saveGate = gate
        backend.updates.emit(delivery(1)); runCurrent()
        platform.foreground = true // e.g. account/settings page became visible during disk work.
        gate.complete(Unit); runCurrent()
        assertTrue(platform.alerts.isEmpty())
        assertEquals(1L, store.records[key]?.notificationThrough)
        platform.foreground = false
        state.retrySync(); runCurrent()
        assertTrue(platform.alerts.isEmpty())
        backend.updates.emit(delivery(3, 2).copy(throughSeq = 1)); runCurrent()
        assertEquals(listOf(key to "delivered3"), platform.alerts)
    }
    @Test fun `disabled receiving consumes old changes without replay after explicit enable`() = runTest {
        val store = Store()
        val key = ChatSyncKey("https://fairy", 1, "s")
        store.records[key] = ChatSyncRecord(0, true, notificationThrough = 0)
        var enabled = false
        val platform = Platform(); val backend = Backend()
        val state = opened(store, backend, platform, enabled = { enabled })
        backend.updates.emit(delivery(1)); runCurrent()
        enabled = true
        state.retrySync(); runCurrent()
        assertTrue(platform.alerts.isEmpty())
        backend.updates.emit(delivery(2, 2).copy(throughSeq = 1)); runCurrent()
        assertEquals(1, platform.alerts.size)
    }
    @Test fun `failed consumption commit leaves backlog eligible after process recreation`() = runTest {
        val store = Store()
        val key = ChatSyncKey("https://fairy", 1, "s")
        store.records[key] = ChatSyncRecord(0, true, notificationThrough = 0)
        val platform = Platform(); val backend = Backend()
        val state = opened(store, backend, platform)
        store.failSave = true
        backend.updates.emit(delivery(1)); runCurrent()
        assertTrue(platform.alerts.isEmpty())
        assertEquals(0L, store.records[key]?.notificationThrough)
        state.detach(); store.failSave = false
        opened(store, backend, platform)
        platform.before = { assertEquals(1L, store.records[key]?.notificationThrough) }
        backend.updates.emit(delivery(1)); runCurrent()
        assertEquals(1, platform.alerts.size)
    }
    @Test fun `crash between durable consumption and notify may miss sound but does not retry it`() = runTest {
        val store = Store()
        val key = ChatSyncKey("https://fairy", 1, "s")
        store.records[key] = ChatSyncRecord(0, true, notificationThrough = 0)
        val platform = Platform().apply { crashBefore = true }; val backend = Backend()
        val state = opened(store, backend, platform)
        backend.updates.emit(delivery(1)); runCurrent()
        assertTrue(platform.alerts.isEmpty())
        assertEquals(1L, store.records[key]?.notificationThrough)
        assertEquals("delivered1", state.snapshot.value.projection?.messages?.single()?.text)
        state.detach(); platform.crashBefore = false
        val restarted = opened(store, backend, platform)
        backend.updates.emit(delivery(1)); runCurrent()
        assertTrue(platform.alerts.isEmpty())
        backend.updates.emit(delivery(2, 2).copy(throughSeq = 1)); runCurrent()
        assertEquals(1, platform.alerts.size)
        assertNotNull(restarted)
    }
    @Test fun `crash after notify cannot repeat that episode on restart`() = runTest {
        val store = Store()
        val key = ChatSyncKey("https://fairy", 1, "s")
        store.records[key] = ChatSyncRecord(0, true, notificationThrough = 0)
        val platform = Platform().apply { crashAfter = true }; val backend = Backend()
        val state = opened(store, backend, platform)
        backend.updates.emit(delivery(1)); runCurrent()
        assertEquals(1, platform.alerts.size)
        state.detach(); platform.crashAfter = false
        val restarted = opened(store, backend, platform)
        backend.updates.emit(delivery(1)); runCurrent()
        assertEquals(1, platform.alerts.size)
        assertEquals(1L, store.records[key]?.notificationThrough)
        assertNotNull(restarted)
    }
    @Test fun `an account retired during consumption commit cannot publish its late notification`() = runTest {
        val store = Store()
        val alice = ChatSyncKey("https://fairy", 1, "s")
        store.records[alice] = ChatSyncRecord(0, true, notificationThrough = 0)
        val platform = Platform(); val backend = Backend()
        val state = opened(store, backend, platform)
        val gate = CompletableDeferred<Unit>(); store.saveGate = gate
        backend.updates.emit(delivery(1)); runCurrent()
        state.bind("bob", "bob-session", 2); runCurrent()
        gate.complete(Unit); runCurrent()
        assertTrue(platform.alerts.isEmpty())
        assertEquals("bob-session", state.snapshot.value.sessionId)
        assertEquals(1L, store.records[alice]?.notificationThrough)
    }
    companion object {
        fun event(seq: Long): JsonObject = Json.parseToJsonElement("""{"seq":$seq,"type":"fact"}""") as JsonObject
        fun delivery(seq: Long, version: Long = 1) = RemoteChatProjection(
            sessionId = "s", generation = "g", version = version, cursor = seq,
            messages = listOf(RemoteChatMessage("delivery$seq", seq, role = 2, status = 2, text = "delivered$seq")),
            events = (0..seq).map(::event),
            activity = RemoteActivity(status = 1, episodeId = "s/$seq", throughSeq = seq,
                completed = RemoteCompletedActivity("s/$seq", seq, seq, seq),
                delivery = RemoteActivityDelivery("s/$seq", seq)),
        )
    }
}
