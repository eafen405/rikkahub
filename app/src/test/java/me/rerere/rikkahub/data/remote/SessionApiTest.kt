package me.rerere.rikkahub.data.remote

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * The remote session client, against a real HTTP server on loopback.
 *
 * The double is the JDK's own [HttpServer] for the reason [IdentityApiTest]
 * uses it: what is under test is the bytes on the wire — the paths, the Bearer
 * credential, which requests carry a body and which declare no media type at all
 * — and a mocked HTTP client would let all of that drift freely.
 */
class SessionApiTest {

    private data class Recorded(
        val method: String,
        val path: String,
        val contentType: String?,
        val authorization: String?,
        val body: String,
    )

    private lateinit var server: HttpServer
    private lateinit var client: OkHttpClient
    private lateinit var api: SessionApi
    private val recorded = Collections.synchronizedList(mutableListOf<Recorded>())
    private var replyStatus = 200
    private var replyBody = "{}"

    private val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    @Before
    fun start() {
        recorded.clear()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handle(exchange) }
        server.executor = null
        server.start()
        client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
        api = SessionApi(baseUrl, client)
    }

    @After
    fun stop() {
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
        val bytes = replyBody.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(replyStatus, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun reply(status: Int, body: String) {
        replyStatus = status
        replyBody = body
    }

    private fun onlyRequest(): Recorded = recorded.single()

    // ---- listing your own sessions ----

    @Test
    fun `list posts to the sessions list path with the bearer credential and no body`() = runTest {
        reply(
            200,
            """{"ok":true,"sessions":[{"id":"s-1","owner":"user:1","primary":true,"createdAt":1760000000000}],"primarySessionId":"s-1"}""",
        )

        val listed = api.list(TOKEN)

        val request = onlyRequest()
        assertEquals("POST", request.method)
        assertEquals("/sessions/list", request.path)
        assertEquals("Bearer $TOKEN", request.authorization)
        // Acting purely on the presented credential means no body, and a media
        // type the backend was never sent is what earns a 415.
        assertEquals("", request.body)
        assertNull(request.contentType)

        assertEquals(listOf("s-1"), listed.sessions.map { it.id })
        assertEquals("s-1", listed.primarySessionId)
        // The backend passes `header.createdAt` straight through, and that is
        // `Date.now()` milliseconds — a number on the wire, not an ISO string.
        assertEquals(CREATED_AT, listed.sessions.single().createdAt)
    }

    @Test
    fun `list reports an empty account without inventing a main session`() = runTest {
        reply(200, """{"ok":true,"sessions":[],"primarySessionId":null}""")

        val listed = api.list(TOKEN)

        assertEquals(emptyList<String>(), listed.sessions.map { it.id })
        assertNull(listed.primarySessionId)
    }

    // ---- creating a session ----

    @Test
    fun `create posts to the sessions create path and returns the new session`() = runTest {
        reply(200, """{"ok":true,"session":{"id":"s-2","owner":"user:1","primary":false,"createdAt":1760003600000}}""")

        val created = api.create(TOKEN)

        val request = onlyRequest()
        assertEquals("POST", request.method)
        assertEquals("/sessions/create", request.path)
        assertEquals("Bearer $TOKEN", request.authorization)
        assertEquals("", request.body)
        assertNull(request.contentType)

        assertEquals("s-2", created.id)
        assertEquals("user:1", created.owner)
        assertEquals(false, created.primary)
    }

    // ---- choosing a session ----

    @Test
    fun `select names the session it wants and answers with the same session`() = runTest {
        reply(200, """{"ok":true,"session":{"id":"s-2","owner":"user:1","primary":false,"createdAt":1760003600000}}""")

        val selected = api.select(TOKEN, "s-2")

        val request = onlyRequest()
        assertEquals("POST", request.method)
        assertEquals("/sessions/select", request.path)
        assertEquals("Bearer $TOKEN", request.authorization)
        assertEquals("""{"sessionId":"s-2"}""", request.body)
        assertEquals("application/json; charset=utf-8", request.contentType)

        assertEquals("s-2", selected.id)
    }

    // ---- returning to the fixed main session ----

    @Test
    fun `primary asks for the main session without naming one`() = runTest {
        reply(200, """{"ok":true,"session":{"id":"s-1","owner":"user:1","primary":true,"createdAt":1760000000000}}""")

        val primary = api.primary(TOKEN)

        val request = onlyRequest()
        assertEquals("POST", request.method)
        assertEquals("/sessions/primary", request.path)
        assertEquals("Bearer $TOKEN", request.authorization)
        assertEquals("", request.body)
        assertNull(request.contentType)

        assertEquals("s-1", primary.id)
        assertTrue(primary.primary)
    }

    // ---- reading a session ----

    @Test
    fun `read names the session it wants`() = runTest {
        reply(200, """{"ok":true,"session":{"id":"s-7","owner":"user:9","primary":false,"createdAt":1760007200000}}""")

        val read = api.read(TOKEN, "s-7")

        val request = onlyRequest()
        assertEquals("POST", request.method)
        assertEquals("/sessions/read", request.path)
        assertEquals("""{"sessionId":"s-7"}""", request.body)
        assertEquals("Bearer $TOKEN", request.authorization)

        // The owner is whatever the backend said it is; the client does not
        // decide it, and in particular does not read it back out of the id.
        assertEquals("user:9", read.owner)
        assertEquals("s-7", read.id)
    }

    // ---- refusal ----

    @Test
    fun `a refusal carries the backend's own code and message`() = runTest {
        reply(403, """{"ok":false,"error":{"code":"sessions/not-owner","message":"that session is not yours"}}""")

        try {
            api.read(TOKEN, "s-other")
            fail("expected the refusal to be raised")
        } catch (refused: IdentityRefused) {
            assertEquals(403, refused.status)
            assertEquals("sessions/not-owner", refused.code)
            assertEquals("that session is not yours", refused.message)
        }
    }

    @Test
    fun `an unreadable answer is unreachable rather than a refusal`() = runTest {
        reply(200, "<html>not our document</html>")

        try {
            api.list(TOKEN)
            fail("expected the unreadable answer to be raised")
        } catch (unreachable: IdentityUnreachable) {
            // Unreadable is "could not be read", never a refusal the user did.
            assertNotNull(unreachable.cause)
        }
    }

    @Test
    fun `a failed status is a failure even when the envelope claims success`() = runTest {
        // `ok: true` is the body's claim; the status is the transport's. Both
        // have to hold before an answer is adopted, as IdentityApi requires.
        reply(500, """{"ok":true,"sessions":[],"primarySessionId":"s-1"}""")

        try {
            api.list(TOKEN)
            fail("expected the failed status to be raised")
        } catch (refused: IdentityRefused) {
            assertEquals(500, refused.status)
        }
    }

    private companion object {
        const val TOKEN = "cred-alice"

        /** `Date.now()` milliseconds, the shape `expose` actually sends. */
        const val CREATED_AT = 1_760_000_000_000L
    }
}
