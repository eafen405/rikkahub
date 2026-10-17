package me.rerere.rikkahub.data.remote

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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

    /**
     * Records whether the reminder was suppressed, so a later reconnect cannot replay it.
     * [failNextPublish] models the platform refusing the attempt after the durable record was
     * consumed: the sound for that episode is lost, and only that episode.
     */
    private class Platform : RemoteNotificationPlatform {
        var foreground = false
        var enabled = true
        var failNextPublish = false
        var publishAttempts = 0
        val alerts = mutableListOf<Pair<ChatSyncKey, String>>()
        override fun isForeground() = foreground
        override fun publish(key: ChatSyncKey, preview: String) {
            publishAttempts += 1
            if (foreground || !enabled) throw AssertionError("a suppressed reminder was published")
            if (failNextPublish) {
                failNextPublish = false
                throw IOException("notification channel refused the reminder")
            }
            alerts += key to preview
        }
    }

    private class Sessions(private val ids: List<String> = listOf("main")) : SessionBackend {
        override suspend fun list(token: String) = OwnedSessions(
            ids.map { OwnedSession("$token-$it", primary = it == ids.first()) },
            "$token-${ids.first()}",
        )
        override suspend fun read(token: String, sessionId: String) = OwnedSession(sessionId)
        override suspend fun select(token: String, sessionId: String) = read(token, sessionId)
        override suspend fun create(token: String) = error("not used")
        override suspend fun primary(token: String) = OwnedSession("$token-${ids.first()}", primary = true)
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

    private fun TestScope.receiver(harness: Harness, sessions: SessionBackend = Sessions()) =
        RemoteReceiveState(sessions, backgroundScope, { settle ->
            RemoteChatState(harness.chats, backgroundScope, harness.store, "https://fairy", settleReminders = settle)
        }, harness.platform, { harness.platform.enabled })

    @Test fun `three staggered reconnects publish one merged reminder`() = runTest {
        val harness = Harness()
        val receiver = receiver(harness, Sessions(listOf("main", "other", "third")))
        receiver.bind("alice", 1); runCurrent()
        val main = harness.chats.updates.getValue("alice-main")
        val other = harness.chats.updates.getValue("alice-other")
        val third = harness.chats.updates.getValue("alice-third")
        // Each session reconnects on its own schedule. The first one to answer must not spend
        // the reminder before the others have said whether they owe anything: `syncing` alone
        // stayed false for a session that had not received its opening frame yet, so the old
        // aggregation published once per arriving session (three notifications, not one).
        main.emit(delivery("alice-main", 1)); runCurrent()
        assertTrue(
            "a session that has not answered yet still holds the merged reminder",
            harness.platform.alerts.isEmpty(),
        )
        other.emit(delivery("alice-other", 2)); runCurrent()
        assertTrue("the second arrival must not publish on its own", harness.platform.alerts.isEmpty())
        third.emit(delivery("alice-third", 3)); runCurrent()
        assertEquals(1, harness.platform.alerts.size)
        // The merge opens the account's primary session, which is also where it lands for the user.
        assertEquals("alice-main", harness.platform.alerts.single().first.sessionId)
        // Every session consumed its backlog, so nothing is replayed on the next attempt.
        for (id in listOf("main", "other", "third")) {
            assertFalse(harness.store.load(ChatSyncKey("https://fairy", 1, "alice-$id")).notificationOwed)
        }
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

    @Test fun `a refused publication after consumption loses one sound and is not replayed`() = runTest {
        val harness = Harness()
        val receiver = receiver(harness)
        receiver.bind("alice", 1); runCurrent()
        val key = ChatSyncKey("https://fairy", 1, "alice-main")
        harness.platform.failNextPublish = true
        harness.chats.updates.getValue("alice-main").emit(delivery("alice-main", 1)); runCurrent()

        // The record was consumed before the attempt, which is exactly the trade: a crash or a
        // refusal here may miss this one sound, but must not replay it on the retry.
        assertEquals(1, harness.platform.publishAttempts)
        assertTrue(harness.platform.alerts.isEmpty())
        assertFalse(harness.store.load(key).notificationOwed)
        assertEquals("alice-main/1", harness.store.load(key).notificationEpisodeId)

        advanceUntilIdle()
        assertEquals(1, harness.platform.publishAttempts)
        assertTrue(harness.platform.alerts.isEmpty())
    }
}
