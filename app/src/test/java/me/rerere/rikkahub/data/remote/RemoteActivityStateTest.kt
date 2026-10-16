package me.rerere.rikkahub.data.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteActivityStateTest {
    private class Store : ChatSyncStore {
        private val records = mutableMapOf<ChatSyncKey, ChatSyncRecord>()
        override suspend fun load(key: ChatSyncKey) = records[key] ?: ChatSyncRecord()
        override suspend fun save(key: ChatSyncKey, record: ChatSyncRecord) { records[key] = record }
    }
    private class Backend : RemoteChatBackend {
        val streams = mutableMapOf<String, MutableSharedFlow<RemoteChatProjection>>()
        var ackGate: CompletableDeferred<Unit>? = null
        override fun observe(token: String, sessionId: String): Flow<RemoteChatProjection> = streams.getOrPut(sessionId) { MutableSharedFlow(extraBufferCapacity = 8) }
        override suspend fun checkpoint(token: String, sessionId: String): Long { ackGate?.await(); return 10 }
        override suspend fun read(token: String, sessionId: String) = error("not needed")
        override suspend fun stop(token: String, sessionId: String) = Unit
        override suspend fun receipt(token: String, sessionId: String, requestId: String): ChatReceipt? = null
        override suspend fun page(token: String, sessionId: String, throughSeq: Long, beforeSeq: Long) = error("not needed")
        override suspend fun send(token: String, sessionId: String, requestId: String, text: String, uploadIds: List<String>) = error("not needed")
        suspend fun emit(session: String, value: RemoteActivity, version: Long = 1, generation: String = "g1") {
            streams.getValue(session).emit(RemoteChatProjection(session, generation = generation, version = version,
                cursor = 0, durableThroughSeq = 0, throughSeq = 0, fromSeq = 0,
                events = listOf(RemoteChatRecoveryTest.event(0)), activity = value))
        }
    }
    private fun active(reason: Int) = RemoteActivity(status = 2, reasons = listOf(reason), episodeId = "s/0", startedSeq = 0, throughSeq = 0)

    @Test fun `shared activity follows backend job report handoff without guessing from idle reply`() = runTest {
        val backend = Backend()
        val state = RemoteChatState(backend, backgroundScope, Store(), "https://fairy")
        state.bind("alice", "s", 1); runCurrent()
        backend.emit("s", active(2)); runCurrent()
        assertEquals(0, state.snapshot.value.projection!!.replyStatus)
        assertEquals(2, state.snapshot.value.activity.status)
        assertEquals(listOf(2), state.snapshot.value.activity.reasons)
        backend.emit("s", active(3), version = 2); runCurrent()
        assertEquals("s/0", state.snapshot.value.activity.episodeId)
        assertEquals(2, state.snapshot.value.activity.status)
        assertEquals(listOf(3), state.snapshot.value.activity.reasons)
    }
    @Test fun `quiet remains unknown until recovery writer barrier and detach never implies silence`() = runTest {
        val gate = CompletableDeferred<Unit>(); val backend = Backend().apply { ackGate = gate }
        val state = RemoteChatState(backend, backgroundScope, Store(), "https://fairy")
        state.bind("alice", "s", 1); runCurrent()
        val quiet = RemoteActivity(status = 1, throughSeq = 0,
            completed = RemoteCompletedActivity("s/0", 0, 0, 0))
        backend.emit("s", quiet); runCurrent()
        assertEquals(0, state.snapshot.value.activity.status)
        assertEquals(1, state.snapshot.value.projection!!.activity.status)
        gate.complete(Unit); runCurrent()
        assertEquals(1, state.snapshot.value.activity.status)
        state.detach(); runCurrent()
        assertEquals(0, state.snapshot.value.activity.status)
        assertEquals("s/0", state.snapshot.value.activity.completed!!.episodeId)
    }

    @Test fun `restart generation retires previous active facts and waits for recovered unknown`() = runTest {
        val backend = Backend()
        val state = RemoteChatState(backend, backgroundScope, Store(), "https://fairy")
        state.bind("alice", "s", 1); runCurrent()
        backend.emit("s", active(2)); runCurrent()
        assertEquals(2, state.snapshot.value.activity.status)
        val gate = CompletableDeferred<Unit>(); backend.ackGate = gate
        backend.emit("s", RemoteActivity(status = 0, throughSeq = 0), version = 1, generation = "g2"); runCurrent()
        assertEquals("g2", state.snapshot.value.projection!!.generation)
        assertEquals(0, state.snapshot.value.activity.status)
        assertTrue(state.snapshot.value.activity.reasons.isEmpty())
        gate.complete(Unit); runCurrent()
        assertFalse(state.snapshot.value.syncing)
        assertEquals(0, state.snapshot.value.activity.status)
    }

    @Test fun `one session job does not keep another quiet delivery active or fabricate reply`() = runTest {
        val backend = Backend(); val store = Store()
        val running = RemoteChatState(backend, backgroundScope, store, "https://fairy")
        val recipient = RemoteChatState(backend, backgroundScope, store, "https://fairy")
        running.bind("alice", "s1", 1); recipient.bind("alice", "s2", 1); runCurrent()
        backend.emit("s1", active(2).copy(episodeId = "s1/0"))
        val delivery = RemoteActivity(status = 1, reasons = listOf(4), throughSeq = 0,
            completed = RemoteCompletedActivity("s2/0", 0, 0, 0), delivery = RemoteActivityDelivery("s2/0", 0))
        backend.emit("s2", delivery); runCurrent()
        assertEquals(2, running.snapshot.value.activity.status)
        assertEquals(1, recipient.snapshot.value.activity.status)
        assertEquals("s2/0", recipient.snapshot.value.activity.delivery!!.episodeId)
        assertEquals(0, recipient.snapshot.value.projection!!.replyStatus)
        assertNull(recipient.snapshot.value.projection!!.attempt)
        recipient.detach(); runCurrent()
        assertEquals(2, running.snapshot.value.activity.status)
        assertEquals(0, recipient.snapshot.value.activity.status)
    }

    @Test fun `finished report follows authoritative quiet without retaining old job reason`() = runTest {
        val backend = Backend()
        val state = RemoteChatState(backend, backgroundScope, Store(), "https://fairy")
        state.bind("alice", "s", 1); runCurrent()
        backend.emit("s", active(3)); runCurrent()
        val quiet = RemoteActivity(status = 1, throughSeq = 0,
            completed = RemoteCompletedActivity("s/0", 0, 0))
        backend.emit("s", quiet, version = 2); runCurrent()
        assertEquals(1, state.snapshot.value.activity.status)
        assertTrue(state.snapshot.value.activity.reasons.isEmpty())
        assertEquals("s/0", state.snapshot.value.activity.completed!!.episodeId)
    }

}
