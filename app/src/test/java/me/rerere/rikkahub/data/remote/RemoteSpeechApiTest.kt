package me.rerere.rikkahub.data.remote

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

/**
 * The speech transport, against a real HTTP server on loopback.
 *
 * The double is the JDK's own [HttpServer] for the reason
 * [RemoteAttachmentApiTest] uses it: what is under test is the wire — that the
 * availability and transcription routes are asked with the Bearer credential
 * and the raw recorded bytes, and that the backend's own refusal codes survive
 * to the caller — and a mocked HTTP client would let all of that drift.
 */
class RemoteSpeechApiTest {

    private lateinit var server: HttpServer
    private lateinit var api: RemoteSpeechApi

    private var status = 200
    private var response = "{}"
    private var requestPath = ""
    private var contentType: String? = null
    private var authorization: String? = null
    private var requestBody = ByteArray(0)

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requestPath = exchange.requestURI.path
            contentType = exchange.requestHeaders.getFirst("Content-Type")
            authorization = exchange.requestHeaders.getFirst("Authorization")
            requestBody = exchange.requestBody.readBytes()
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        api = RemoteSpeechApi(
            baseUrl = "http://127.0.0.1:${server.address.port}",
            client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
        )
    }

    @After
    fun stop() {
        server.stop(0)
    }

    @Test
    fun `availability is asked with the credential and reports the backend's ceilings`() = runTest {
        status = 200
        response = """{"ok":true,"available":true,"maxDurationMs":30000,"maxAudioBytes":1048576}"""

        val availability = api.availability("token-abc")

        assertEquals("/speech", requestPath)
        assertEquals("Bearer token-abc", authorization)
        assertTrue(availability.available)
        assertEquals(30000, availability.maxDurationMs)
        assertEquals(1048576, availability.maxAudioBytes)
    }

    @Test
    fun `a take is posted as raw wav and its transcript comes back verbatim`() = runTest {
        status = 200
        response = """{"ok":true,"text":"今天天气不错"}"""
        val wav = ByteArray(64) { it.toByte() }

        val text = api.transcribe("token-abc", wav)

        assertEquals("/speech/transcriptions", requestPath)
        assertEquals("Bearer token-abc", authorization)
        assertEquals("audio/wav", contentType)
        assertTrue("the recorded bytes are sent unwrapped", requestBody.contentEquals(wav))
        assertEquals("今天天气不错", text)
    }

    @Test
    fun `the backend's refusal code survives to the caller`() = runTest {
        status = 429
        response = """{"ok":false,"error":{"code":"speech/rate-limited","message":"slow down"}}"""

        val refused = refused { api.transcribe("token-abc", ByteArray(8)) }

        assertEquals(429, refused.status)
        assertEquals("speech/rate-limited", refused.code)
    }

    /** A refusal the call must raise, from a suspending call under [runTest]. */
    private suspend inline fun refused(block: suspend () -> Unit): IdentityRefused =
        try {
            block()
            throw AssertionError("the call answered instead of refusing")
        } catch (refused: IdentityRefused) {
            refused
        }
}
