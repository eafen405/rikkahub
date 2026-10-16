package me.rerere.rikkahub.data.remote

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteNotificationStateTest {
    private class Store : ChatSyncStore {
        val records = mutableMapOf<ChatSyncKey, ChatSyncRecord>()
        override suspend fun load(key: ChatSyncKey) = records[key] ?: ChatSyncRecord()
        override suspend fun save(key: ChatSyncKey, record: ChatSyncRecord) { records[key] = record }
    }
    private class Platform : RemoteNotificationPlatform {
        var foreground = false
        val alerts = mutableListOf<Pair<ChatSyncKey, String>>()
        override fun isForeground() = foreground
        override fun publish(key: ChatSyncKey, preview: String) { alerts += key to preview }
    }
    private class Backend : RemoteChatBackend {
        val updates = MutableSharedFlow<RemoteChatProjection>(extraBufferCapacity = 10)
        override fun observe(token: String, sessionId: String) = updates
        override suspend fun checkpoint(token: String, sessionId: String) = 100L
        override suspend fun page(token: String, sessionId: String, throughSeq: Long, beforeSeq: Long) =
            ChatHistoryPage(sessionId, throughSeq, 0, events = (0 until beforeSeq).map(::event))
        override suspend fun read(token: String, sessionId: String) = error("not used")
        override suspend fun send(token: String, sessionId: String, requestId: String, text: String, uploadIds: List<String>) = error("not used")
        override suspend fun receipt(token: String, sessionId: String, requestId: String): ChatReceipt? = null
        override suspend fun stop(token: String, sessionId: String) = error("receiver never stops backend work")
    }
    @Test fun `background delivery is consumed once before platform publication`() = runTest {
        val store = Store()
        val key = ChatSyncKey("https://fairy", 1, "s")
        store.records[key] = ChatSyncRecord(0, true, notificationThrough = 0)
        val platform = Platform()
        val backend = Backend()
        val state = RemoteChatState(backend, backgroundScope, store, "https://fairy", notifications = platform)
        state.bind("alice", "s", 1); runCurrent()
        backend.updates.emit(delivery(1)); runCurrent()
        assertEquals(listOf(key to "delivered1"), platform.alerts)
        assertEquals(1L, store.records[key]?.notificationThrough)
        assertEquals("s/1", store.records[key]?.notificationEpisodeId)
        state.retrySync(); runCurrent()
        assertEquals(1, platform.alerts.size)
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
