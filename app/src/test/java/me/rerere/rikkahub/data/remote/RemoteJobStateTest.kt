package me.rerere.rikkahub.data.remote

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteJobStateTest {
    private class MemoryStore : ChatSyncStore {
        private val records = mutableMapOf<ChatSyncKey, ChatSyncRecord>()
        override suspend fun load(key: ChatSyncKey) = records[key] ?: ChatSyncRecord()
        override suspend fun save(key: ChatSyncKey, record: ChatSyncRecord) { records[key] = record }
    }
    private class Chat : RemoteChatBackend {
        val projections = MutableSharedFlow<RemoteChatProjection>(extraBufferCapacity = 4)
        var stops = 0
        override fun observe(token: String, sessionId: String): Flow<RemoteChatProjection> = projections
        override suspend fun read(token: String, sessionId: String) = RemoteChatProjection(sessionId)
        override suspend fun stop(token: String, sessionId: String) { stops++ }
        override suspend fun checkpoint(token: String, sessionId: String) = -1L
        override suspend fun page(token: String, sessionId: String, throughSeq: Long, beforeSeq: Long) = error("no history")
        override suspend fun receipt(token: String, sessionId: String, requestId: String): ChatReceipt? = null
        override suspend fun send(token: String, sessionId: String, requestId: String, text: String, uploadIds: List<String>) = error("no send")
    }
    private class Jobs : RemoteJobBackend {
        val requests = mutableListOf<Long>()
        var stops = 0
        var gate: CompletableDeferred<Unit>? = null
        var response = RemoteJobOutput("gen/bash-1", 1, listOf(RemoteJobChunk(0, "你好")), 6, 0, 6, false, 3)
        override suspend fun output(token: String, sessionId: String, taskId: String, from: Long): RemoteJobOutput {
            requests += from
            val captured = response
            gate?.let { withContext(NonCancellable) { it.await() } }
            return captured
        }
        override suspend fun stop(token: String, sessionId: String, taskId: String): Int { stops++; return 1 }
    }
    private val task = RemoteTask("gen/bash-1", "bash", "long script", 1, 1, 10,
        output = RemoteTaskOutputWindow(0, 6), report = RemoteTaskReport(0))

    @Test fun `task details retain UTF8 output and closing observation never stops work`() = runTest {
        val chat = Chat()
        val jobs = Jobs()
        val state = RemoteChatState(chat, backgroundScope, MemoryStore(), "https://fairy", jobsBackend = jobs)
        state.bind("alice", "s1", 1)
        runCurrent()
        chat.projections.emit(RemoteChatProjection("s1", generation = "gen", tasks = listOf(task)))
        runCurrent()
        state.observeTaskOutput(task.taskId)
        runCurrent()
        assertEquals("你好", state.snapshot.value.taskDetail?.chunks?.joinToString("") { it.text })
        assertEquals(6L, state.snapshot.value.taskDetail?.next)
        state.closeTaskOutput()
        state.detach()
        runCurrent()
        assertNull(state.snapshot.value.taskDetail)
        assertEquals(0, jobs.stops)
        assertEquals(0, chat.stops)
    }
    @Test fun `overlapping whole UTF8 chunks append only unseen bytes and retain explicit loss at terminal`() = runTest {
        val chat = Chat()
        val jobs = Jobs().apply { response = RemoteJobOutput(task.taskId, 1, listOf(RemoteJobChunk(0, "你")), 3, 0, 3, false, 1) }
        val state = RemoteChatState(chat, backgroundScope, MemoryStore(), "https://fairy", jobsBackend = jobs)
        state.bind("alice", "s1", 1); runCurrent()
        chat.projections.emit(RemoteChatProjection("s1", generation = "gen", tasks = listOf(task))); runCurrent()
        state.observeTaskOutput(task.taskId); runCurrent()
        jobs.response = RemoteJobOutput(task.taskId, 1, listOf(RemoteJobChunk(0, "你好🙂")), 10, 0, 10, false, 1)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf(0L, 3L), jobs.requests)
        assertEquals("你好🙂", state.snapshot.value.taskDetail!!.chunks.joinToString("") { it.text })
        jobs.response = RemoteJobOutput(task.taskId, 1, listOf(RemoteJobChunk(13, "终", gapBefore = true)), 16, 13, 16, true, 3)
        advanceTimeBy(1_000); runCurrent()
        val output = state.snapshot.value.taskDetail!!
        assertEquals(16L, output.next)
        assertEquals("终", output.chunks.joinToString("") { it.text })
        assertTrue(output.hasGap)
        assertEquals(3, output.status)
    }

    @Test fun `reply stop task stop and view cancel are independent commands`() = runTest {
        val chat = Chat(); val jobs = Jobs().apply { response = response.copy(status = 1) }
        val state = RemoteChatState(chat, backgroundScope, MemoryStore(), "https://fairy", jobsBackend = jobs)
        state.bind("alice", "s1", 1); runCurrent()
        chat.projections.emit(RemoteChatProjection("s1", tasks = listOf(task))); runCurrent()
        state.observeTaskOutput(task.taskId); runCurrent()
        state.stop(); runCurrent()
        assertEquals(1, chat.stops); assertEquals(0, jobs.stops)
        state.stopTask(task.taskId); runCurrent()
        assertEquals(1, jobs.stops); assertEquals(1, chat.stops)
        assertTrue(state.snapshot.value.taskDetail!!.stopRequested)
        state.closeTaskOutput(); state.detach(); runCurrent()
        assertEquals(1, jobs.stops); assertEquals(1, chat.stops)
    }

    @Test fun `unavailable completed task retains known outcome without claiming complete output`() = runTest {
        val chat = Chat(); val jobs = Jobs().apply {
            response = RemoteJobOutput(task.taskId, 0, emptyList(), 0, 0, 90, false, 3)
        }
        val state = RemoteChatState(chat, backgroundScope, MemoryStore(), "https://fairy", jobsBackend = jobs)
        state.bind("alice", "s1", 1); runCurrent()
        chat.projections.emit(RemoteChatProjection("s1", tasks = listOf(task.copy(status = 3, availability = 0)))); runCurrent()
        state.observeTaskOutput(task.taskId); runCurrent()
        assertEquals(0, state.snapshot.value.taskDetail!!.availability)
        assertEquals(3, state.snapshot.value.taskDetail!!.status)
        assertTrue(state.snapshot.value.taskDetail!!.chunks.isEmpty())
        assertEquals(0L, state.snapshot.value.taskDetail!!.next)
    }

    @Test fun `late output from old account cannot enter the new binding`() = runTest {
        val chat = Chat(); val gate = CompletableDeferred<Unit>(); val jobs = Jobs().apply { this.gate = gate }
        val state = RemoteChatState(chat, backgroundScope, MemoryStore(), "https://fairy", jobsBackend = jobs)
        state.bind("alice", "s1", 1); runCurrent()
        chat.projections.emit(RemoteChatProjection("s1", tasks = listOf(task))); runCurrent()
        state.observeTaskOutput(task.taskId); runCurrent()
        state.bind("bob", "s2", 2); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals("s2", state.snapshot.value.sessionId)
        assertNull(state.snapshot.value.taskDetail)
        assertEquals(0, jobs.stops)
    }

    @Test fun `a moving retention window trims an old overlapping UTF8 chunk at a byte boundary`() = runTest {
        val chat = Chat(); val jobs = Jobs().apply {
            response = RemoteJobOutput(task.taskId, 1, listOf(RemoteJobChunk(0, "你好🙂")), 10, 0, 10, false, 1)
        }
        val state = RemoteChatState(chat, backgroundScope, MemoryStore(), "https://fairy", jobsBackend = jobs)
        state.bind("alice", "s1", 1); runCurrent()
        chat.projections.emit(RemoteChatProjection("s1", tasks = listOf(task))); runCurrent()
        state.observeTaskOutput(task.taskId); runCurrent()
        jobs.response = RemoteJobOutput(task.taskId, 1, listOf(RemoteJobChunk(10, "!")), 11, 3, 11, false, 3)
        advanceTimeBy(1_000); runCurrent()
        val detail = state.snapshot.value.taskDetail!!
        assertEquals("好🙂!", detail.chunks.joinToString("") { it.text })
        assertEquals(3L, detail.chunks.first().at)
        assertTrue(detail.hasGap)
    }

}
