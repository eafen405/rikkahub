package me.rerere.rikkahub.data.remote

import kotlinx.coroutines.CompletableDeferred
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
        override suspend fun load(key: ChatSyncKey) = records[key] ?: ChatSyncRecord()
        override suspend fun save(key: ChatSyncKey, record: ChatSyncRecord) {
            if (failSave) throw IOException("disk unavailable")
            records[key] = record
        }
    }
    private class Backend : RemoteChatBackend {
        val flow = MutableSharedFlow<RemoteChatProjection>(extraBufferCapacity = 20)
        var gate: CompletableDeferred<Unit>? = null
        var failPage = false
        var ack = 9L
        var sendCount = 0
        var onSend: (() -> Unit)? = null
        val pages = mutableListOf<Pair<Long,Long>>()
        override fun observe(token:String,sessionId:String):Flow<RemoteChatProjection> = flow
        override suspend fun page(token:String,sessionId:String,throughSeq:Long,beforeSeq:Long):ChatHistoryPage {
            pages += throughSeq to beforeSeq
            gate?.await()
            if (failPage) throw IdentityUnreachable(IOException("page lost"))
            val from = (beforeSeq - 2).coerceAtLeast(0)
            return ChatHistoryPage(sessionId,throughSeq,from,from.takeIf { it > 0 },from>0,
                (from until beforeSeq).map(::event), listOf(message(from)))
        }
        override suspend fun checkpoint(token:String,sessionId:String)=ack
        override suspend fun read(token:String,sessionId:String)=projection(0,1)
        override suspend fun send(token:String,sessionId:String,requestId:String,text:String):ChatReceipt {
            sendCount++; onSend?.invoke()
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
    companion object {
        fun event(seq:Long):JsonObject=Json.parseToJsonElement("""{"seq":$seq,"type":"fact","payload":"$seq"}""") as JsonObject
        fun message(seq:Long)=RemoteChatMessage("m$seq",seq,null,2,2,"body$seq")
        fun projection(from:Long,cursor:Long,version:Long=1)=RemoteChatProjection(
            "s","g",version,cursor,-1,listOf(message(cursor)),null,0,null,
            cursor,from,(from..cursor).map(::event),from>0,from.takeIf { it>0 })
    }
}
