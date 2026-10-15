package me.rerere.rikkahub.data.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteChatRecoveryTest {
    private class Store : ChatSyncStore {
        val records = mutableMapOf<ChatSyncKey, ChatSyncRecord>()
        var failSave = false
        var saveGate: CompletableDeferred<Unit>? = null
        override suspend fun load(key: ChatSyncKey) = records[key] ?: ChatSyncRecord()
        override suspend fun save(key: ChatSyncKey, record: ChatSyncRecord) {
            saveGate?.let { withContext(NonCancellable) { it.await() } }
            if (failSave) throw IOException("disk unavailable")
            records[key] = record
        }
    }
    private class Backend : RemoteChatBackend {
        val flow = MutableSharedFlow<RemoteChatProjection>(extraBufferCapacity = 20)
        var gate: CompletableDeferred<Unit>? = null
        var failPage = false
        var ack = 9L
        var checkpointFailure = false
        var disconnects = 0
        var connections = 0
        var sendCount = 0
        var sendGate: CompletableDeferred<Unit>? = null
        var onSend: (() -> Unit)? = null
        val pages = mutableListOf<Pair<Long,Long>>()
        override fun observe(token:String,sessionId:String):Flow<RemoteChatProjection> = flow {
            connections++
            if (disconnects > 0) {
                disconnects--
                emit(projection(0,2).copy(attempt=RemoteChatAttempt("a",1,"prefix while disconnected"),replyStatus=1))
                throw IdentityUnreachable(IOException("connection lost"))
            }
            emitAll(this@Backend.flow)
        }
        override suspend fun page(token:String,sessionId:String,throughSeq:Long,beforeSeq:Long):ChatHistoryPage {
            pages += throughSeq to beforeSeq
            gate?.let { withContext(NonCancellable) { it.await() } }
            if (failPage) throw IdentityUnreachable(IOException("page lost"))
            val from = (beforeSeq - 2).coerceAtLeast(0)
            return ChatHistoryPage(sessionId,throughSeq,from,from.takeIf { it > 0 },from>0,
                (from until beforeSeq).map(::event), listOf(message(from)))
        }
        override suspend fun checkpoint(token:String,sessionId:String): Long {
            if (checkpointFailure) throw IdentityUnreachable(IOException("writer flush failed"))
            return ack
        }
        override suspend fun read(token:String,sessionId:String)=projection(0,1)
        override suspend fun send(token:String,sessionId:String,requestId:String,text:String,uploadIds:List<String>):ChatReceipt {
            sendCount++; onSend?.invoke()
            sendGate?.await()
            throw IdentityUnreachable(IOException("response lost"))
        }
        override suspend fun receipt(token:String,sessionId:String,requestId:String):ChatReceipt?=null
        override suspend fun stop(token:String,sessionId:String) = Unit
    }
    @Test fun `fixed cut backfills raw facts while live terminal remains newest`() = runTest {
        val store=Store(); val backend=Backend(); val gate=CompletableDeferred<Unit>(); backend.gate=gate
        val state=RemoteChatState(backend,backgroundScope,store,"https://fairy",retryDelayMillis=1000)
        state.bind("alice","s",1); runCurrent()
        backend.flow.emit(projection(4,5).copy(attempt=RemoteChatAttempt("a",1,"prefix"),replyStatus=1));runCurrent()
        assertTrue(state.snapshot.value.syncing)
        assertEquals(-1L,state.snapshot.value.consumedThrough)
        backend.flow.emit(projection(4,7,2).copy(throughSeq=5,messages=listOf(message(7).copy(text="terminal"))));runCurrent()
        gate.complete(Unit);runCurrent()
        assertEquals(listOf(5L to 4L,5L to 2L),backend.pages)
        assertNull(state.snapshot.value.projection?.attempt)
        assertEquals("terminal",state.snapshot.value.projection?.messages?.last()?.text)
        assertEquals(7L,state.snapshot.value.consumedThrough)
        assertEquals(7L,state.snapshot.value.batch?.throughSeq)
        assertTrue(state.snapshot.value.batch!!.initialization)
    }
    @Test fun `failed page or local commit cannot advance consumed prefix and retry recovers`() = runTest {
        val store=Store();val backend=Backend().apply { failPage=true }
        val state=RemoteChatState(backend,backgroundScope,store,"https://fairy",retryDelayMillis=1000)
        state.bind("alice","s",1);runCurrent()
        backend.flow.emit(projection(4,5));runCurrent()
        assertEquals(-1L,state.snapshot.value.consumedThrough)
        assertNotNull(state.snapshot.value.failure)
        backend.failPage=false;store.failSave=true
        state.retrySync();runCurrent()
        assertEquals(-1L,state.snapshot.value.consumedThrough)
        assertNull(state.snapshot.value.batch)
        store.failSave=false;state.retrySync();runCurrent()
        assertEquals(5L,state.snapshot.value.consumedThrough)
    }
    @Test fun `saved consumption without a message cache still reloads all history`() = runTest {
        val key=ChatSyncKey("https://fairy",1,"s")
        val store=Store().apply { records[key]=ChatSyncRecord(5,true) }
        val backend=Backend()
        val state=RemoteChatState(backend,backgroundScope,store,"https://fairy")
        state.bind("new-token","s",1);runCurrent()
        backend.flow.emit(projection(4,7));runCurrent()
        assertEquals(listOf(7L to 4L,7L to 2L),backend.pages)
        assertEquals(0L,state.snapshot.value.projection?.messages?.first()?.seq)
        assertEquals(5L,state.snapshot.value.batch?.fromExclusive)
        assertFalse(state.snapshot.value.batch!!.initialization)
    }
    @Test fun `pending command is saved before network and restored with same identity after process recreation`() = runTest {
        val store=Store();val key=ChatSyncKey("https://fairy",1,"s");val backend=Backend()
        backend.onSend={ assertNotNull(store.records[key]?.pending) }
        val state=RemoteChatState(backend,backgroundScope,store,"https://fairy")
        state.bind("alice","s",1);runCurrent();state.send("exact\n text");runCurrent()
        val original=state.snapshot.value.pending!!
        state.detach()
        val restarted=RemoteChatState(backend,backgroundScope,store,"https://fairy")
        restarted.bind("rotated-token","s",1);runCurrent()
        assertEquals(original.requestId,restarted.snapshot.value.pending?.requestId)
        assertEquals("exact\n text",restarted.snapshot.value.pending?.text)
        assertTrue(restarted.snapshot.value.pending!!.acceptanceUnknown)
        assertEquals(1,backend.sendCount)
    }
    @Test fun `failed pending commit prevents network send`() = runTest {
        val store=Store().apply { failSave=true };val backend=Backend()
        val state=RemoteChatState(backend,backgroundScope,store,"https://fairy")
        state.bind("alice","s",1);runCurrent();state.send("hello");runCurrent()
        assertEquals(0,backend.sendCount)
        assertNotNull(state.snapshot.value.failure)
    }
    @Test fun `automatic reconnect preserves active prefix and settles without cancellation`() = runTest {
        val backend=Backend().apply { disconnects=1 };val store=Store()
        val state=RemoteChatState(backend,backgroundScope,store,"https://fairy",retryDelayMillis=1000)
        state.bind("alice","s",1);runCurrent()
        assertEquals("prefix while disconnected",state.snapshot.value.projection?.attempt?.text)
        assertFalse(state.snapshot.value.connected)
        assertEquals(1,backend.connections)
        advanceTimeBy(1001);runCurrent()
        assertEquals(2,backend.connections)
        backend.flow.emit(projection(0,3,2));runCurrent()
        assertTrue(state.snapshot.value.connected)
        assertNull(state.snapshot.value.projection?.attempt)
        assertEquals(3L,state.snapshot.value.consumedThrough)
    }
    @Test fun `a late old session page is discarded even when cancellation cannot stop its response`() = runTest {
        val gate=CompletableDeferred<Unit>();val backend=Backend().apply { this.gate=gate }
        val state=RemoteChatState(backend,backgroundScope,Store(),"https://fairy")
        state.bind("alice","s",1);runCurrent()
        backend.flow.emit(projection(4,5));runCurrent()
        state.bind("bob","other",2);runCurrent()
        backend.flow.emit(projection(0,1).copy(sessionId="other",messages=listOf(message(1).copy(text="bob"))));runCurrent()
        gate.complete(Unit);runCurrent()
        assertEquals("other",state.snapshot.value.sessionId)
        assertEquals(listOf("bob"),state.snapshot.value.projection?.messages?.map { it.text })
        assertEquals(1L,state.snapshot.value.consumedThrough)
    }
    @Test fun `writer acknowledgement failure cannot publish a completed batch`() = runTest {
        val backend=Backend().apply { checkpointFailure=true }
        val state=RemoteChatState(backend,backgroundScope,Store(),"https://fairy")
        state.bind("alice","s",1);runCurrent();backend.flow.emit(projection(0,3));runCurrent()
        assertEquals(-1L,state.snapshot.value.consumedThrough)
        assertNull(state.snapshot.value.batch)
        backend.checkpointFailure=false;state.retrySync();runCurrent()
        assertEquals(3L,state.snapshot.value.consumedThrough)
    }
    @Test fun `account changed during pending commit cannot send under the previous credential`() = runTest {
        val gate=CompletableDeferred<Unit>();val store=Store().apply { saveGate=gate };val backend=Backend()
        val state=RemoteChatState(backend,backgroundScope,store,"https://fairy")
        state.bind("alice","s",1);runCurrent();state.send("alice command");runCurrent()
        state.bind("bob","other",2);runCurrent();gate.complete(Unit);runCurrent()
        assertEquals(0,backend.sendCount)
        assertNull(state.snapshot.value.pending)
        assertEquals("other",state.snapshot.value.sessionId)
    }
    @Test fun `pending save failure retains editable text for explicit retry without network side effects`() = runTest {
        val store=Store().apply { failSave=true };val backend=Backend()
        val state=RemoteChatState(backend,backgroundScope,store,"https://fairy")
        state.bind("alice","s",1);runCurrent();state.send("unsaved text");runCurrent()
        assertEquals("unsaved text",state.snapshot.value.pending?.text)
        assertEquals(0,backend.sendCount)
        store.failSave=false;state.retryPending();runCurrent()
        assertEquals(1,backend.sendCount)
    }
    @Test fun `live tail arriving during local commit is consumed without another server event`() = runTest {
        val gate=CompletableDeferred<Unit>();val store=Store().apply { saveGate=gate };val backend=Backend()
        val state=RemoteChatState(backend,backgroundScope,store,"https://fairy",retryDelayMillis=1000)
        state.bind("alice","s",1);runCurrent();backend.flow.emit(projection(0,1));runCurrent()
        backend.flow.emit(projection(0,3,2).copy(throughSeq=1));runCurrent()
        gate.complete(Unit);runCurrent();advanceTimeBy(1001);runCurrent()
        assertEquals(3L,state.snapshot.value.consumedThrough)
        assertFalse(state.snapshot.value.syncing)
    }
    @Test fun `lost send response cannot resurrect a command already reconciled from durable messages`() = runTest {
        val gate=CompletableDeferred<Unit>();val backend=Backend().apply { sendGate=gate };val store=Store()
        val state=RemoteChatState(backend,backgroundScope,store,"https://fairy")
        state.bind("alice","s",1);runCurrent();state.send("saved");runCurrent()
        val request=state.snapshot.value.pending!!.requestId
        backend.flow.emit(projection(0,1).copy(messages=listOf(message(1).copy(role=1,requestId=request))));runCurrent()
        assertNull(state.snapshot.value.pending)
        gate.complete(Unit);runCurrent()
        assertNull(state.snapshot.value.pending)
        assertFalse(state.snapshot.value.sending)
    }
    @Test fun `recovered generation replaces observed unsaved event with interrupted closer without losing pending or W`() = runTest {
        val key=ChatSyncKey("https://fairy",1,"s")
        val pending=StoredChatSend("stable-request","not yet confirmed")
        val store=Store().apply { records[key]=ChatSyncRecord(0,true,pending) }
        val backend=Backend().apply { ack=0 }
        val state=RemoteChatState(backend,backgroundScope,store,"https://fairy")
        state.bind("alice","s",1);runCurrent()
        backend.flow.emit(projection(0,1).copy(generation="old-agent",messages=listOf(message(1).copy(text="unsaved"))));runCurrent()
        assertEquals(0L,state.snapshot.value.consumedThrough)
        state.detach();state.bind("rotated-token","s",1);runCurrent()
        backend.ack=1
        val closer=Json.parseToJsonElement("""{"seq":1,"type":"turn/end","status":"interrupted"}""") as JsonObject
        backend.flow.emit(projection(0,1).copy(generation="recovered-agent",events=listOf(event(0),closer),
            messages=listOf(message(1).copy(text="recovered partial",status=3))));runCurrent()
        assertTrue(state.snapshot.value.connected)
        assertNull(state.snapshot.value.failure)
        assertEquals("recovered-agent",state.snapshot.value.projection?.generation)
        assertEquals(listOf("recovered partial"),state.snapshot.value.projection?.messages?.map { it.text })
        assertEquals(1L,state.snapshot.value.consumedThrough)
        assertEquals("stable-request",state.snapshot.value.pending?.requestId)
        assertEquals(pending,store.records[key]?.pending)
    }
    @Test fun `shorter new generation removes orphan bubbles and refills body despite saved consumption`() = runTest {
        val key=ChatSyncKey("https://fairy",1,"s")
        val store=Store().apply { records[key]=ChatSyncRecord(0,true) };val backend=Backend().apply { ack=0 }
        val state=RemoteChatState(backend,backgroundScope,store,"https://fairy")
        state.bind("alice","s",1);runCurrent();backend.flow.emit(projection(0,3).copy(generation="old"));runCurrent()
        state.detach();state.bind("alice","s",1);runCurrent()
        backend.flow.emit(projection(0,0).copy(generation="new",messages=listOf(message(0).copy(text="saved body"))));runCurrent()
        assertTrue(state.snapshot.value.connected)
        assertEquals(listOf("saved body"),state.snapshot.value.projection?.messages?.map { it.text })
        assertEquals(0L,state.snapshot.value.consumedThrough)
        assertFalse(state.snapshot.value.syncing)
        // Same generation corruption remains rejected after the generation reset.
        val conflict=Json.parseToJsonElement("""{"seq":0,"type":"conflict"}""") as JsonObject
        backend.flow.emit(projection(0,0,2).copy(generation="new",events=listOf(conflict)));runCurrent()
        assertFalse(state.snapshot.value.connected)
        assertEquals(listOf("saved body"),state.snapshot.value.projection?.messages?.map { it.text })
        assertNotNull(state.snapshot.value.failure)
    }
    companion object {
        fun event(seq:Long):JsonObject=Json.parseToJsonElement("""{"seq":$seq,"type":"fact","payload":"$seq"}""") as JsonObject
        fun message(seq:Long)=RemoteChatMessage("m$seq",seq,null,2,2,"body$seq")
        fun projection(from:Long,cursor:Long,version:Long=1)=RemoteChatProjection(
            "s","g",version,cursor,-1,listOf(message(cursor)),null,0,null,
            cursor,from,(from..cursor).map(::event),from>0,from.takeIf { it>0 })
    }
}
