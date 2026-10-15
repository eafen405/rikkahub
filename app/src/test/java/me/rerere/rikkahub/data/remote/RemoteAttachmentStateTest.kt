package me.rerere.rikkahub.data.remote

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteAttachmentStateTest {
    private class Store : ChatSyncStore {
        val records = mutableMapOf<ChatSyncKey, ChatSyncRecord>()
        var fail = false
        override suspend fun load(key: ChatSyncKey) = records[key] ?: ChatSyncRecord()
        override suspend fun save(key: ChatSyncKey, record: ChatSyncRecord) {
            if (fail) throw IOException("disk unavailable")
            records[key] = record
        }
    }
    private class Chat : RemoteChatBackend {
        val sent = mutableListOf<StoredChatSend>()
        var fail = true
        override fun observe(token:String, sessionId:String): Flow<RemoteChatProjection> = MutableSharedFlow()
        override suspend fun read(token:String,sessionId:String) = RemoteChatProjection(sessionId)
        override suspend fun send(token:String,sessionId:String,requestId:String,text:String,uploadIds:List<String>):ChatReceipt {
            sent += StoredChatSend(requestId, text, uploadIds)
            if (fail) throw IdentityUnreachable(IOException("lost send response"))
            return ChatReceipt(sessionId,requestId,"message",0)
        }
        override suspend fun receipt(token:String,sessionId:String,requestId:String):ChatReceipt? = null
        override suspend fun stop(token:String,sessionId:String) {}
        override suspend fun page(token:String,sessionId:String,throughSeq:Long,beforeSeq:Long) = ChatHistoryPage(sessionId,throughSeq,0)
        override suspend fun checkpoint(token:String,sessionId:String) = -1L
    }
    private class Files : RemoteAttachmentBackend {
        var uploadGate: CompletableDeferred<Unit>? = null
        var downloadGate: CompletableDeferred<Unit>? = null
        val originals = mutableListOf<ByteArray>()
        override suspend fun upload(token:String,sessionId:String,file:File,name:String,mime:String):RemoteAttachmentUpload {
            originals += file.readBytes()
            uploadGate?.let { withContext(NonCancellable) { it.await() } }
            return RemoteAttachmentUpload("a".repeat(32),name,mime,file.length(),"b".repeat(64),1,file.length(),Long.MAX_VALUE)
        }
        override suspend fun download(token:String,sessionId:String,messageId:String,attachment:RemoteChatAttachment,destination:File) {
            downloadGate?.let { withContext(NonCancellable) { it.await() } }
            destination.writeText("original")
        }
    }
    private fun original() = File.createTempFile("attachment", ".txt").apply { writeText("exact original\n") }

    @Test fun `attachment only unknown send survives process restart with original request and ordered references`() = runTest {
        val store = Store()
        val chat = Chat()
        val files = Files()
        val state = RemoteChatState(chat,backgroundScope,store,"https://fairy",attachmentsBackend=files)
        state.bind("alice","s1",1); runCurrent()
        val file = original()
        state.addAttachment(state.snapshot.value.attachmentEpoch,file,"note.txt","text/plain"); runCurrent()
        assertArrayEquals("exact original\n".toByteArray(),files.originals.single())
        assertTrue(state.send("")); runCurrent()
        val unknown = state.snapshot.value.pending!!
        assertEquals(listOf("a".repeat(32)),unknown.uploadIds)
        assertTrue(unknown.acceptanceUnknown)
        val serialized = Json.encodeToString(store.records.values.single())
        val restoredStore = Store().apply { records[ChatSyncKey("https://fairy",1,"s1")] = Json.decodeFromString(serialized) }
        state.detach()
        val restored = RemoteChatState(chat,backgroundScope,restoredStore,"https://fairy",attachmentsBackend=files)
        restored.bind("rotated","s1",1); runCurrent()
        assertEquals(unknown.requestId,restored.snapshot.value.pending?.requestId)
        assertEquals(unknown.uploadIds,restored.snapshot.value.pending?.uploadIds)
        assertEquals(1,chat.sent.size)
        chat.fail = false
        restored.retryPending(); runCurrent()
        assertEquals(chat.sent[0],chat.sent[1])
        assertNull(restored.snapshot.value.pending)
        file.delete()
    }

    @Test fun `failed pending persistence prevents attachment send and retains upload reference`() = runTest {
        val store = Store()
        val chat = Chat()
        val state = RemoteChatState(chat,backgroundScope,store,"https://fairy",attachmentsBackend=Files())
        state.bind("alice","s1",1); runCurrent()
        state.addAttachment(state.snapshot.value.attachmentEpoch,original(),"note.txt","text/plain"); runCurrent()
        store.fail = true
        assertTrue(state.send("note")); runCurrent()
        assertTrue(chat.sent.isEmpty())
        assertEquals(listOf("a".repeat(32)),state.snapshot.value.pending?.uploadIds)
    }

    @Test fun `late upload after account switch is discarded rather than attached to new draft`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val files = Files().apply { uploadGate = gate }
        val state = RemoteChatState(Chat(),backgroundScope,Store(),"https://fairy",attachmentsBackend=files)
        state.bind("alice","s1",1); runCurrent()
        val oldEpoch = state.snapshot.value.attachmentEpoch
        val file = original()
        state.addAttachment(oldEpoch,file,"private.txt","text/plain"); runCurrent()
        state.bind("bob","s2",2); runCurrent()
        gate.complete(Unit); runCurrent()
        assertTrue(state.snapshot.value.attachments.isEmpty())
        assertFalse(file.exists())
        val selectedBeforeSwitch = original()
        state.addAttachment(oldEpoch,selectedBeforeSwitch,"late.txt","text/plain"); runCurrent()
        assertFalse(selectedBeforeSwitch.exists())
        assertTrue(state.snapshot.value.attachments.isEmpty())
    }

    @Test fun `late original download after session switch never exports to selected document`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val files = Files().apply { downloadGate = gate }
        val state = RemoteChatState(Chat(),backgroundScope,Store(),"https://fairy",attachmentsBackend=files)
        state.bind("alice","s1",1); runCurrent()
        val destination = File.createTempFile("download", ".tmp")
        var saved = false
        state.downloadAttachment(state.snapshot.value.attachmentEpoch,"message",RemoteChatAttachment(0,1,"private.txt","text/plain",8),destination) { saved = true }
        runCurrent()
        state.bind("alice","s2",1); runCurrent()
        gate.complete(Unit); runCurrent()
        assertFalse(saved)
        assertFalse(destination.exists())
        assertNull(state.snapshot.value.attachmentNotice)
    }
}
