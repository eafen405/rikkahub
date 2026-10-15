package me.rerere.rikkahub.data.remote

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The remote chat client, against a real HTTP server on loopback.
 *
 * The double is the JDK's own [HttpServer] for the reason [SessionApiTest] uses
 * it: what is under test is the bytes on the wire — the request identity the
 * backend makes idempotent on, the Bearer credential, the refusal envelope and
 * the event stream's framing — and a mocked HTTP client would let all of that
 * drift freely.
 */
class RemoteChatApiTest {

    private data class Recorded(
        val method: String,
        val path: String,
        val contentType: String?,
        val authorization: String?,
        val body: String,
    )

    private lateinit var server: HttpServer
    private lateinit var client: OkHttpClient
    private lateinit var api: RemoteChatApi
    private val recorded = Collections.synchronizedList(mutableListOf<Recorded>())
    private val streamed = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private var replyStatus = 200
    private var replyBody = "{}"
    private var sseFrames: List<String> = emptyList()
    private var holdStreamOpen = false

    private val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    @Before
    fun start() {
        recorded.clear()
        sseFrames = emptyList()
        holdStreamOpen = false
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handle(exchange) }
        server.executor = null
        server.start()
        client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
        api = RemoteChatApi(baseUrl, client)
    }

    @After
    fun stop() {
        release.countDown()
        server.stop(0)
    }

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        recorded += Recorded(
            method = exchange.requestMethod,
            path = exchange.requestURI.path,
            contentType = exchange.requestHeaders.getFirst("Content-Type"),
            authorization = exchange.requestHeaders.getFirst("Authorization"),
            body = body,
        )
        when (exchange.requestURI.path) {
            "/chat/observe" -> stream(exchange)
            else -> respond(exchange, replyStatus, replyBody)
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /** Answers as `text/event-stream` in chunks, so the client's own framing decides. */
    private fun stream(exchange: HttpExchange) {
        // A refusal arrives as an ordinary document before any streaming starts.
        if (replyStatus != 200) {
            respond(exchange, replyStatus, replyBody)
            return
        }
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, 0)
        exchange.responseBody.use { out ->
            for (frame in sseFrames) {
                out.write(frame.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            streamed.countDown()
            if (holdStreamOpen) release.await(10, TimeUnit.SECONDS)
        }
    }

    private fun reply(status: Int, body: String) {
        replyStatus = status
        replyBody = body
    }

    private fun onlyRequest(): Recorded = recorded.single()

    private fun paths(): List<String> = recorded.map { it.path }

    // ---- sending ----

    @Test
    fun `send presents one stable request identity and answers with the receipt`() = runTest {
        reply(
            200,
            """{"ok":true,"receipt":{"sessionId":"s-1","requestId":"r-1","messageId":"m-2","acceptedSeq":2}}""",
        )

        val receipt = api.send(TOKEN, "s-1", "r-1", TEXT)

        val request = onlyRequest()
        assertEquals("POST", request.method)
        assertEquals("/chat/send", request.path)
        assertEquals("Bearer $TOKEN", request.authorization)
        assertEquals("application/json; charset=utf-8", request.contentType)
        // The identity is exactly what was asked for, byte for byte: the backend
        // makes `send` idempotent on this document, so the client must never
        // rewrite it, timestamp it, or re-roll it on a retry.
        val document = Json.parseToJsonElement(request.body).jsonObject
        assertEquals("s-1", document.getValue("sessionId").jsonPrimitive.content)
        assertEquals("r-1", document.getValue("requestId").jsonPrimitive.content)
        assertEquals(TEXT, document.getValue("text").jsonPrimitive.content)

        assertEquals("s-1", receipt.sessionId)
        assertEquals("r-1", receipt.requestId)
        assertEquals("m-2", receipt.messageId)
        assertEquals(2L, receipt.acceptedSeq)
    }

    @Test
    fun `an unreadable send answer is unreachable rather than a receipt`() = runTest {
        reply(200, "<html>not our document</html>")

        try {
            api.send(TOKEN, "s-1", "r-1", TEXT)
            fail("expected the unreadable answer to be raised")
        } catch (unreachable: IdentityUnreachable) {
            assertNotNull(unreachable.cause)
        }
    }

    // ---- asking whether the same request was accepted ----

    @Test
    fun `receipt asks about the same identity and a null answer means not yet accepted`() = runTest {
        reply(200, """{"ok":true,"receipt":null}""")

        val receipt = api.receipt(TOKEN, "s-1", "r-1")

        val request = onlyRequest()
        assertEquals("POST", request.method)
        assertEquals("/chat/receipt", request.path)
        assertEquals("Bearer $TOKEN", request.authorization)
        assertEquals("""{"sessionId":"s-1","requestId":"r-1"}""", request.body)

        // Null is an answer, not a failure: the same request may still be sent.
        assertNull(receipt)
    }

    @Test
    fun `a receipt the backend already recorded is returned as it stands`() = runTest {
        reply(
            200,
            """{"ok":true,"receipt":{"sessionId":"s-1","requestId":"r-1","messageId":"m-2","acceptedSeq":2}}""",
        )

        val receipt = api.receipt(TOKEN, "s-1", "r-1")

        assertEquals("r-1", receipt?.requestId)
        assertEquals("m-2", receipt?.messageId)
    }

    // ---- refusal ----

    @Test
    fun `a refusal carries the backend's own code and message`() = runTest {
        reply(409, """{"ok":false,"error":{"code":"chat/request-conflict","message":"that requestId already carries other text"}}""")

        try {
            api.send(TOKEN, "s-1", "r-1", TEXT)
            fail("expected the refusal to be raised")
        } catch (refused: IdentityRefused) {
            assertEquals(409, refused.status)
            assertEquals("chat/request-conflict", refused.code)
            assertEquals("that requestId already carries other text", refused.message)
        }
    }

    @Test
    fun `a failed status is a failure even when the envelope claims success`() = runTest {
        reply(500, """{"ok":true,"receipt":null}""")

        try {
            api.receipt(TOKEN, "s-1", "r-1")
            fail("expected the failed status to be raised")
        } catch (refused: IdentityRefused) {
            assertEquals(500, refused.status)
        }
    }

    // ---- reading the transcript ----

    @Test
    fun `read names the session and answers with the projection as sent`() = runTest {
        reply(200, """{"ok":true,"projection":$FIRST_SNAPSHOT}""")

        val projection = api.read(TOKEN, "s-1")

        val request = onlyRequest()
        assertEquals("POST", request.method)
        assertEquals("/chat/read", request.path)
        assertEquals("Bearer $TOKEN", request.authorization)
        assertEquals("""{"sessionId":"s-1"}""", request.body)

        assertEquals("s-1", projection.sessionId)
        assertEquals("gen-1", projection.generation)
        assertEquals(1L, projection.version)
        assertEquals(1L, projection.cursor)
        // -1 is "unknown", and saying so is different from claiming nothing is
        // durable.
        assertEquals(-1L, projection.durableThroughSeq)
        assertEquals(2, projection.replyStatus)
        assertEquals("the model fell over", projection.error)
        assertEquals("m-1", projection.messages.single().messageId)
        assertEquals(TEXT, projection.messages.single().text)
        assertEquals("r-1", projection.messages.single().requestId)
        assertEquals(1, projection.messages.single().role)
        assertEquals(2, projection.messages.single().status)
        assertEquals("a-1", projection.attempt?.attemptId)
        assertEquals(3L, projection.attempt?.revision)
    }

    // ---- stopping ----

    @Test
    fun `stop names the session and is not retried behind the user's back`() = runTest {
        reply(200, """{"ok":true}""")

        api.stop(TOKEN, "s-1")

        val request = onlyRequest()
        assertEquals("POST", request.method)
        assertEquals("/chat/stop", request.path)
        assertEquals("Bearer $TOKEN", request.authorization)
        assertEquals("""{"sessionId":"s-1"}""", request.body)
    }

    // ---- watching ----

    @Test
    fun `observe emits each cumulative snapshot whole and completes when the stream closes`() = runTest {
        // A keepalive comment, an event this client does not model, and two
        // projections: none of the first two may reach the caller, and each
        // projection must arrive as one complete snapshot rather than as text
        // to be concatenated with the previous one.
        sseFrames = listOf(
            ": keepalive\n\n",
            "event: ping\ndata: {\"ignored\":true}\n\n",
            "event: projection\ndata: $FIRST_SNAPSHOT\n\n",
            ": keepalive\n\n",
            "event: projection\ndata: $SECOND_SNAPSHOT\n\n",
            // One frame whose payload is split across two `data:` lines is one
            // snapshot, joined by the newline the stream put between them.
            "event: projection\ndata: {\"sessionId\":\"s-1\",\"generation\":\"gen-1\",\ndata: \"version\":3,\"cursor\":2,\"durableThroughSeq\":2,\"messages\":[],\"attempt\":null,\"replyStatus\":0,\"error\":null}\n\n",
        )

        val snapshots = withContext(Dispatchers.IO) { api.observe(TOKEN, "s-1").toList() }

        val request = onlyRequest()
        assertEquals("POST", request.method)
        assertEquals("/chat/observe", request.path)
        assertEquals("Bearer $TOKEN", request.authorization)
        assertEquals("""{"sessionId":"s-1"}""", request.body)

        assertEquals(3, snapshots.size)
        assertEquals(1L, snapshots[0].version)
        assertEquals(2L, snapshots[1].version)
        assertEquals(3L, snapshots[2].version)
        // The later snapshot replaces the earlier one; nothing was concatenated.
        assertEquals(2, snapshots[1].messages.size)
        // Text is whatever the native side saved, whitespace and all.
        assertEquals(TEXT, snapshots[1].messages[0].text)
        assertEquals("\n  second  \n", snapshots[1].messages[1].text)
        assertEquals("", snapshots[2].messages.joinToString(""))
        // The stream ended normally, so the flow completed; a dropped
        // connection would be a different thing and is not reported as one.
        assertEquals(2L, snapshots[2].cursor)
    }

    @Test
    fun `slow rendering still receives the final complete snapshot`() = runTest {
        sseFrames = (1..200).map { version ->
            "event: projection\ndata: {\"sessionId\":\"s-1\",\"generation\":\"g\",\"version\":$version}\n\n"
        }
        val snapshots = withContext(Dispatchers.IO) {
            api.observe(TOKEN, "s-1").onEach { Thread.sleep(2) }.toList()
        }
        assertEquals(200L, snapshots.last().version)
    }

    @Test
    fun `a refused observe reports the refusal instead of a stream`() = runTest {
        reply(404, """{"ok":false,"error":{"code":"sessions/unknown-session","message":"no such session"}}""")

        try {
            withContext(Dispatchers.IO) { api.observe(TOKEN, "nope").toList() }
            fail("expected the refusal to be raised")
        } catch (refused: IdentityRefused) {
            assertEquals(404, refused.status)
            assertEquals("sessions/unknown-session", refused.code)
        }
    }

    @Test
    fun `closing an observer cancels the call and never stops the reply`() = runTest {
        // Watch a running reply, then walk away from the screen.
        sseFrames = listOf("event: projection\ndata: $FIRST_SNAPSHOT\n\n")
        holdStreamOpen = true
        val first = CompletableDeferred<Unit>()
        val observing = launch(Dispatchers.IO) {
            api.observe(TOKEN, "s-1").collect { first.complete(Unit) }
        }
        first.await()

        observing.cancelAndJoin()
        yield()

        // Closing an observer is not cancelling the reply: the work belongs to
        // the session, and only `stop` may end it.
        assertEquals(listOf("/chat/observe"), paths())
        assertTrue(observing.isCancelled)
    }

    private companion object {
        const val TOKEN = "cred-alice"
        const val TEXT = "  hello\n\tworld  "

        /** A reply still being produced, with exact original text and Markdown. */
        const val FIRST_SNAPSHOT =
            """{"sessionId":"s-1","generation":"gen-1","version":1,"cursor":1,"durableThroughSeq":-1,""" +
                """"messages":[{"messageId":"m-1","seq":1,"requestId":"r-1","role":1,"status":2,"text":"  hello\n\tworld  "}],""" +
                """"attempt":{"attemptId":"a-1","revision":3,"text":"thinking"},"replyStatus":2,"error":"the model fell over"}"""

        /** A later, cumulative snapshot: two messages, no attempt left. */
        const val SECOND_SNAPSHOT =
            """{"sessionId":"s-1","generation":"gen-1","version":2,"cursor":2,"durableThroughSeq":1,""" +
                """"messages":[{"messageId":"m-1","seq":1,"requestId":"r-1","role":1,"status":2,"text":"  hello\n\tworld  "},""" +
                """{"messageId":"m-2","seq":2,"requestId":"r-1","role":2,"status":3,"text":"\n  second  \n"}],""" +
                """"attempt":null,"replyStatus":0,"error":null}"""
    }
}
