package me.rerere.rikkahub.data.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/**
 * A reconnect that catches up several sessions publishes one reminder, not one per session.
 * Each session records the episode it owes in its own durable record; reception sees every
 * session and decides once, so the backlog has an owner.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteNotificationBacklogTest {
    private class Store : ChatSyncStore {
        private val records = mutableMapOf<ChatSyncKey, ChatSyncRecord>()
        override suspend fun load(key: ChatSyncKey) = records[key] ?: ChatSyncRecord(0, true, notificationThrough = 0)
        fun seed(key: ChatSyncKey, record: ChatSyncRecord) { records[key] = record }
        override suspend fun save(key: ChatSyncKey, record: ChatSyncRecord) { records[key] = record }
    }

    /** Records whether the reminder was suppressed, so a later reconnect cannot replay it. */
    private class Platform : RemoteNotificationPlatform {
        var foreground = false
        var enabled = true
        val alerts = mutableListOf<Pair<ChatSyncKey, String>>()
        override fun isForeground() = foreground
        override fun publish(key: ChatSyncKey, preview: String) {
            if (foreground || !enabled) throw AssertionError("a suppressed reminder was published")
            alerts += key to preview
        }
    }

    private class Sessions : SessionBackend {
        override suspend fun list(token: String) = OwnedSessions(
            listOf(OwnedSession("$token-main", primary = true), OwnedSession("$token-other")),
            "$token-main",
        )
        override suspend fun read(token: String, sessionId: String) = OwnedSession(sessionId)
        override suspend fun select(token: String, sessionId: String) = read(token, sessionId)
        override suspend fun create(token: String) = error("not used")
        override suspend fun primary(token: String) = OwnedSession("$token-main", primary = true)
    }

    private class Chats : RemoteChatBackend {
        val updates = mutableMapOf<String, MutableSharedFlow<RemoteChatProjection>>()
        var gate: CompletableDeferred<Unit>? = null
        override fun observe(token: String, sessionId: String) =
            updates.getOrPut(sessionId) { MutableSharedFlow(extraBufferCapacity = 10) }
        override suspend fun checkpoint(token: String, sessionId: String): Long {
            gate?.let { withContext(NonCancellable) { it.await() } }
            return 100L
        }
        override suspend fun page(token: String, sessionId: String, throughSeq: Long, beforeSeq: Long) =
            ChatHistoryPage(sessionId, throughSeq, 0, events = eventCoverage(0, beforeSeq - 1))
        override suspend fun read(token: String, sessionId: String) = error("not used")
        override suspend fun send(token: String, sessionId: String, requestId: String, text: String, uploadIds: List<String>) = error("not used")
        override suspend fun receipt(token: String, sessionId: String, requestId: String): ChatReceipt? = null
        override suspend fun stop(token: String, sessionId: String) = error("reception never stops backend work")
    }

    private fun delivery(sessionId: String, seq: Long) = deliveryProjection(seq).copy(
        sessionId = sessionId,
        activity = RemoteActivity(status = 1, episodeId = "$sessionId/$seq", throughSeq = seq,
            completed = RemoteCompletedActivity("$sessionId/$seq", seq, seq, seq),
            delivery = RemoteActivityDelivery("$sessionId/$seq", seq)),
    )

    private inner class Harness {
        val store = Store()
        val platform = Platform()
        val chats = Chats()
    }

    private fun TestScope.receiver(harness: Harness) = RemoteReceiveState(Sessions(), backgroundScope, { settle ->
        RemoteChatState(harness.chats, backgroundScope, harness.store, "https://fairy", notifies = settle)
    }, harness.platform, { harness.platform.enabled })

    @Test fun `a reconnect that catches up three sessions publishes one reminder`() = runTest {
        val harness = Harness()
        val receiver = receiver(harness)
        receiver.bind("alice", 1); runCurrent()
        val main = harness.chats.updates.getValue("alice-main")
        val other = harness.chats.updates.getValue("alice-other")
        harness.chats.gate = CompletableDeferred()
        main.emit(delivery("alice-main", 1)); other.emit(delivery("alice-other", 2)); runCurrent()
        assertTrue("nothing publishes while a session is still catching up", harness.platform.alerts.isEmpty())
        harness.chats.gate!!.complete(Unit); runCurrent()
        assertEquals(1, harness.platform.alerts.size)
        // The merge opens the account's primary session, which is also where it lands for the user.
        assertEquals("alice-main", harness.platform.alerts.single().first.sessionId)
        // Both sessions consumed their backlog, so nothing is replayed on the next attempt.
        assertFalse(harness.store.load(ChatSyncKey("https://fairy", 1, "alice-main")).notificationOwed)
        assertFalse(harness.store.load(ChatSyncKey("https://fairy", 1, "alice-other")).notificationOwed)
        // The same episode seen again is not a second backlog.
        main.emit(delivery("alice-main", 1).copy(version = 2)); runCurrent()
        assertEquals(1, harness.platform.alerts.size)
    }

    @Test fun `a backlog the application already saw in front is consumed without a reminder`() = runTest {
        val harness = Harness()
        harness.platform.foreground = true
        val receiver = receiver(harness)
        receiver.bind("alice", 1); runCurrent()
        val main = harness.chats.updates.getValue("alice-main")
        main.emit(delivery("alice-main", 1)); runCurrent()
        assertTrue(harness.platform.alerts.isEmpty())
        assertFalse(harness.store.load(ChatSyncKey("https://fairy", 1, "alice-main")).notificationOwed)
        // Leaving the app later must not replay the episode it was present for.
        harness.platform.foreground = false
        receiver.stateForSession("alice-main")!!.retrySync(); runCurrent()
        assertTrue(harness.platform.alerts.isEmpty())
    }

    @Test fun `a reminder still owed by the record is published after the process restarts`() = runTest {
        val harness = Harness()
        val key = ChatSyncKey("https://fairy", 1, "alice-main")
        // The previous run claimed the episode and died before the panel: the record still owes it.
        harness.store.seed(key, ChatSyncRecord(0, true, notificationThrough = 1,
            notificationEpisodeId = "alice-main/1", notificationOwed = true))
        val receiver = receiver(harness)
        receiver.bind("alice", 1); runCurrent()
        harness.chats.updates.getValue("alice-main").emit(delivery("alice-main", 1)); runCurrent()
        assertEquals(listOf("alice-main"), harness.platform.alerts.map { it.first.sessionId })
        assertFalse(harness.store.load(key).notificationOwed)
    }
}
