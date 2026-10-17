package me.rerere.rikkahub.data.remote

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Captured real product SSE, through production HTTP decoder and synchronization owner. */
class RemoteNotificationWireTest {
    @Test fun `actual durable recipient SSE is caught up and consumed once through the shipped client`() = runTest {
        val frames = javaClass.getResourceAsStream("/remote-notifications/actual-recipient-observe.sse")!!.readBytes()
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val sessionId = "ourfairy-user-3-primary"
        val endpoint = "http://127.0.0.1:${server.address.port}"
        val key = ChatSyncKey(endpoint, 3, sessionId)
        var stored = ChatSyncRecord(0, true, notificationThrough = 0)
        val store = object : ChatSyncStore {
            override suspend fun load(key: ChatSyncKey) = stored
            override suspend fun save(key: ChatSyncKey, record: ChatSyncRecord) { stored = record }
        }
        val alerts = mutableListOf<Pair<ChatSyncKey, String>>()
        val published = CompletableDeferred<Unit>()
        val platform = object : RemoteNotificationPlatform {
            override fun isForeground() = false
            override fun publish(key: ChatSyncKey, preview: String) {
                assertEquals(2L, stored.notificationThrough)
                alerts += key to preview
                published.complete(Unit)
            }
        }
        server.createContext("/") { exchange ->
            try {
                assertEquals("Bearer fixture-recipient", exchange.requestHeaders.getFirst("Authorization"))
                when (exchange.requestURI.path) {
                    "/chat/observe" -> {
                        exchange.responseHeaders.add("Content-Type", "text/event-stream")
                        exchange.sendResponseHeaders(200, 0)
                        exchange.responseBody.use { out -> out.write(frames); out.flush(); release.await(10, TimeUnit.SECONDS) }
                    }
                    "/chat/checkpoint" -> {
                        val bytes = """{"ok":true,"durableThroughSeq":2}""".toByteArray()
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    }
                    else -> error("unexpected recovery request ${exchange.requestURI.path}")
                }
            } finally { exchange.close() }
        }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
        // The shipped client and synchronization owner, under the module that owns publication.
        val receiver = RemoteReceiveState(object : SessionBackend {
            override suspend fun list(token: String) = OwnedSessions(listOf(OwnedSession(sessionId, primary = true)), sessionId)
            override suspend fun read(token: String, sessionId: String) = OwnedSession(sessionId)
            override suspend fun select(token: String, sessionId: String) = read(token, sessionId)
            override suspend fun create(token: String) = error("not used")
            override suspend fun primary(token: String) = OwnedSession(sessionId, primary = true)
        }, backgroundScope, { settle ->
            RemoteChatState(RemoteChatApi(endpoint, OkHttpClient()), backgroundScope, store, endpoint, settleReminders = settle)
        }, platform)
        val state: RemoteChatState
        try {
            receiver.bind("fixture-recipient", 3)
            withContext(Dispatchers.IO) { published.await() }
            state = requireNotNull(receiver.stateForSession(sessionId))
            assertEquals(1, alerts.size)
            assertEquals(listOf(key to "T15 actual durable delivery fixture."), alerts)
            assertEquals(1, state.snapshot.value.activity.status)
            assertEquals("ourfairy-user-3-primary/1", stored.notificationEpisodeId)
            state.retrySync()
            runCurrent()
            assertEquals(1, alerts.size)
        } finally {
            receiver.bind(null, null)
            release.countDown()
            server.stop(0)
            (server.executor as java.util.concurrent.ExecutorService).shutdownNow()
        }
    }
}
