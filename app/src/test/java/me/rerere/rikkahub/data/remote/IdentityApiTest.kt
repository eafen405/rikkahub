package me.rerere.rikkahub.data.remote

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
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
import java.util.concurrent.TimeUnit

/**
 * The identity client, against a real HTTP server on loopback.
 *
 * The double is the JDK's own [HttpServer] rather than a mocked client: the
 * contract under test is the bytes on the wire, and a mock would let the
 * request shape drift from what the backend actually accepts.
 */
class IdentityApiTest {

    private data class Recorded(
        val method: String,
        val path: String,
        val contentType: String?,
        val authorization: String?,
        val body: String,
    )

    private lateinit var server: HttpServer
    private lateinit var client: OkHttpClient
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
        client = OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
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
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(replyStatus, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun reply(status: Int, body: String) {
        replyStatus = status
        replyBody = body
    }

    private fun api() = IdentityApi(baseUrl = baseUrl, client = client)

    @Test
    fun `sign-in posts a JSON document and reads back the credential`() = runTest {
        reply(
            200,
            """{"ok":true,"token":"token-phaethon","user":{"shortId":1,"name":"phaethon","isAdmin":true}}""",
        )

        val signedIn = api().signIn("phaethon", "secret")

        assertEquals("token-phaethon", signedIn.token)
        assertEquals(1, signedIn.user.shortId)
        assertEquals("phaethon", signedIn.user.name)
        assertTrue("Phaethon is the administrator", signedIn.user.isAdmin)

        val sent = recorded.single()
        assertEquals("POST", sent.method)
        assertEquals("/identity/sign-in", sent.path)
        assertNotNull("a document must announce its media type", sent.contentType)
        assertTrue(sent.contentType!!.startsWith("application/json"))
        assertTrue("the password must travel in the body", sent.body.contains("\"phaethon\""))
        assertTrue(sent.body.contains("\"secret\""))
    }

    @Test
    fun `credential-only operations send no document and no media type`() = runTest {
        reply(200, """{"ok":true,"invite":{"code":"ABCD-2345"},"user":{"shortId":1,"name":"phaethon","isAdmin":true}}""")
        assertEquals("ABCD-2345", api().createInvite("token-phaethon"))

        reply(200, """{"ok":true,"user":{"shortId":2,"name":"agent","isAdmin":false}}""")
        assertEquals(2, api().session("token-phaethon").shortId)

        reply(200, """{"ok":true}""")
        api().signOut("token-phaethon")

        assertEquals(3, recorded.size)
        for (sent in recorded) {
            // A request with no payload has no media type to declare. Asking for
            // one is what made the backend answer 415 to its own clients.
            assertEquals("$sent must carry nothing", "", sent.body)
            assertNull("$sent must not declare a media type", sent.contentType)
            assertEquals("Bearer token-phaethon", sent.authorization)
        }
        assertEquals(
            listOf("/identity/invite", "/identity/session", "/identity/sign-out"),
            recorded.map { it.path },
        )
    }

    @Test
    fun `register posts the invite document as JSON`() = runTest {
        reply(200, """{"ok":true,"token":"token-agent","user":{"shortId":2,"name":"agent-one","isAdmin":false}}""")

        val signedIn = api().register("INVITE-CODE", "agent-one", "pw")

        assertEquals(2, signedIn.user.shortId)
        val sent = recorded.single()
        assertEquals("/identity/register", sent.path)
        assertTrue(sent.contentType!!.startsWith("application/json"))
        assertTrue(sent.body.contains("INVITE-CODE"))
        assertTrue(sent.body.contains("agent-one"))
    }

    @Test
    fun `a refusal surfaces as its typed code and not as a parse failure`() = runTest {
        reply(409, """{"ok":false,"error":{"code":"identity/invite-used","message":"the invite was already used"}}""")

        try {
            api().register("SPENT", "agent", "pw")
            fail("a spent invite must not register")
        } catch (refused: IdentityRefused) {
            assertEquals(409, refused.status)
            assertEquals("identity/invite-used", refused.code)
        }
    }

    @Test
    fun `an anonymous refusal is distinguishable from a spent invite`() = runTest {
        reply(401, """{"ok":false,"error":{"code":"identity/unauthorized","message":"present a live session credential"}}""")

        try {
            api().session("forged")
            fail("a forged credential must be refused")
        } catch (refused: IdentityRefused) {
            assertEquals(401, refused.status)
            assertEquals("identity/unauthorized", refused.code)
        }
    }

    @Test
    fun `an unreachable backend is a transport failure and not a refusal`() = runTest {
        server.stop(0)
        try {
            api().signIn("phaethon", "secret")
            fail("a closed port must not look like a successful sign-in")
        } catch (unreachable: IdentityUnreachable) {
            assertNotNull(unreachable.cause)
        } catch (refused: IdentityRefused) {
            fail("a transport failure must not be reported as a refusal: ${refused.code}")
        }
    }
}
