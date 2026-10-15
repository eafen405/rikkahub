package me.rerere.rikkahub.data.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteChatStateTest {
    private class Backend : RemoteChatBackend {
        val events = mutableMapOf<String, MutableSharedFlow<RemoteChatProjection>>()
        val sent = mutableListOf<Triple<String, String, String>>()
        var failure: Exception? = null
        var receiptValue: ChatReceipt? = null
        var stopped = 0
        var sendGate: CompletableDeferred<Unit>? = null
        override fun observe(token: String, sessionId: String): Flow<RemoteChatProjection> =
            events.getOrPut("$token/$sessionId") { MutableSharedFlow(extraBufferCapacity = 8) }
        override suspend fun read(token: String, sessionId: String) = projection(sessionId)
        override suspend fun send(token: String, sessionId: String, requestId: String, text: String): ChatReceipt {
            sent += Triple(sessionId, requestId, text)
            sendGate?.let { withContext(NonCancellable) { it.await() } }
            failure?.let { throw it }
            return ChatReceipt(sessionId, requestId, "user-1", 3)
        }
        override suspend fun receipt(token: String, sessionId: String, requestId: String) = receiptValue
        override suspend fun stop(token: String, sessionId: String) { stopped++ }
    }

    @Test fun `stream snapshots replace cumulative text and pure terminal state enables rich text`() = runTest {
        val backend = Backend()
        val state = RemoteChatState(backend, backgroundScope)
        state.bind("alice", "s1")
        runCurrent()
        backend.events.getValue("alice/s1").emit(projection("s1", 1, RemoteChatAttempt("a1", 1, " # hi\n")))
        runCurrent()
        assertEquals(" # hi\n", state.snapshot.value.projection?.attempt?.text)
        backend.events.getValue("alice/s1").emit(projection("s1", 2, RemoteChatAttempt("a1", 2, " # hi\n**x**")))
        runCurrent()
        assertEquals(" # hi\n**x**", state.snapshot.value.projection?.attempt?.text)
        val message = RemoteChatMessage("m1", 7, null, 2, 3, " # hi\n**x**")
        backend.events.getValue("alice/s1").emit(projection("s1", 3).copy(messages = listOf(message)))
        runCurrent()
        assertNull(state.snapshot.value.projection?.attempt)
        assertEquals(" # hi\n**x**", state.snapshot.value.projection?.messages?.single()?.text)
        assertEquals(RemoteMessageFormat.MARKDOWN, remoteMessageFormat(message))
        assertEquals(RemoteMessageFormat.PLAIN, remoteAttemptFormat())
    }

    @Test fun `unknown acceptance retains request identity and explicit retry uses identical bytes`() = runTest {
        val backend = Backend().apply { failure = IdentityUnreachable(java.io.IOException("lost")) }
        val state = RemoteChatState(backend, backgroundScope)
        state.bind("alice", "s1")
        state.send("  hello\n")
        runCurrent()
        val pending = state.snapshot.value.pending!!
        assertTrue(pending.acceptanceUnknown)
        state.send("  hello\n")
        runCurrent()
        assertEquals(1, backend.sent.size)
        state.reconcile()
        runCurrent()
        assertEquals(pending.requestId, state.snapshot.value.pending?.requestId)
        backend.failure = null
        state.retryPending()
        runCurrent()
        assertEquals(2, backend.sent.size)
        assertEquals(backend.sent[0], backend.sent[1])
        assertNull(state.snapshot.value.pending)
    }

    @Test fun `late old account send result cannot change the new account projection`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val backend = Backend().apply { sendGate = gate }
        val state = RemoteChatState(backend, backgroundScope)
        state.bind("alice", "s1")
        state.send("secret")
        runCurrent()
        state.bind("bob", "s2")
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals("s2", state.snapshot.value.sessionId)
        assertNull(state.snapshot.value.pending)
        assertNull(state.snapshot.value.projection)
        assertFalse(state.snapshot.value.sending)
    }

    @Test fun `closing observation never requests stop and explicit stop does`() = runTest {
        val backend = Backend()
        val state = RemoteChatState(backend, backgroundScope)
        state.bind("alice", "s1")
        runCurrent()
        state.detach()
        runCurrent()
        assertEquals(0, backend.stopped)
        state.bind("alice", "s1")
        state.stop()
        runCurrent()
        assertEquals(1, backend.stopped)
    }

    @Test fun `older same generation projection cannot overwrite newer content`() = runTest {
        val backend = Backend()
        val state = RemoteChatState(backend, backgroundScope)
        state.bind("alice", "s1")
        runCurrent()
        val flow = backend.events.getValue("alice/s1")
        flow.emit(projection("s1", 5, RemoteChatAttempt("a", 3, "complete prefix")))
        runCurrent()
        flow.emit(projection("s1", 4, RemoteChatAttempt("a", 2, "old")))
        runCurrent()
        assertEquals("complete prefix", state.snapshot.value.projection?.attempt?.text)
    }

    @Test fun `reopening an observer does not orphan an in flight send result`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val backend = Backend().apply {
            sendGate = gate
            failure = IdentityUnreachable(java.io.IOException("lost response"))
        }
        val state = RemoteChatState(backend, backgroundScope)
        state.bind("alice", "s1")
        state.send("original")
        runCurrent()
        state.detach()
        state.bind("alice", "s1")
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertFalse(state.snapshot.value.sending)
        assertTrue(state.snapshot.value.pending!!.acceptanceUnknown)
    }

    @Test fun `unconfirmed live messages do not resolve unknown durable acceptance`() = runTest {
        val backend = Backend().apply { failure = IdentityUnreachable(java.io.IOException("lost")) }
        val state = RemoteChatState(backend, backgroundScope)
        state.bind("alice", "s1")
        state.send("original")
        runCurrent()
        val requestId = state.snapshot.value.pending!!.requestId
        val message = RemoteChatMessage("user-1", 8, requestId, 1, 1, "original")
        val flow = backend.events.getValue("alice/s1")
        flow.emit(projection("s1", 1).copy(messages = listOf(message), cursor = 8, durableThroughSeq = 7))
        runCurrent()
        assertEquals(requestId, state.snapshot.value.pending?.requestId)
        flow.emit(projection("s1", 2).copy(messages = listOf(message), cursor = 8, durableThroughSeq = 8))
        runCurrent()
        assertNull(state.snapshot.value.pending)
    }

    @Test fun `switching sessions keeps an unknown command for explicit reconciliation`() = runTest {
        val backend = Backend().apply { failure = IdentityUnreachable(java.io.IOException("lost")) }
        val state = RemoteChatState(backend, backgroundScope)
        state.bind("alice", "s1")
        state.send("original")
        runCurrent()
        val requestId = state.snapshot.value.pending!!.requestId
        state.bind("alice", "s2")
        assertNull(state.snapshot.value.pending)
        state.bind("alice", "s1")
        runCurrent()
        assertEquals(requestId, state.snapshot.value.pending?.requestId)
        assertEquals(1, backend.sent.size)
    }

    companion object {
        fun projection(id: String, version: Long = 0, attempt: RemoteChatAttempt? = null) =
            RemoteChatProjection(id, "g1", version, 7, 7, emptyList(), attempt, if (attempt == null) 0 else 1, null)
    }
}
