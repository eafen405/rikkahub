package me.rerere.rikkahub.data.remote

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * The attachment transport, against a real HTTP server on loopback.
 *
 * The double is the JDK's own [HttpServer] for the reason [RemoteChatApiTest]
 * uses it: what is under test is the bytes on the wire — the original file's
 * digest and size, sequential exact offsets, the headers a lost answer is
 * repeated under, and a download that has to be the declared length before it
 * counts — and a mocked HTTP client would let all of that drift freely.
 */
class RemoteAttachmentApiTest {

    private data class Recorded(
        val path: String,
        val contentType: String?,
        val uploadId: String?,
        val offset: String?,
        val body: ByteArray,
    ) {
        val text: String get() = body.toString(Charsets.UTF_8)
    }

    private lateinit var server: HttpServer
    private lateinit var api: RemoteAttachmentApi
    private lateinit var source: File
    private lateinit var target: File
    private val recorded = Collections.synchronizedList(mutableListOf<Recorded>())

    /** Answers the upload routes will take instead of answering, once each. */
    private var droppedChunkAnswers = 0
    private var droppedCompleteAnswers = 0

    /** The original the double pretends to hold, byte for byte. */
    private var stored = ByteArray(0)

    /** The name the backend answers with, which is its own to sanitize. */
    private var sanitizedName = "photo.png"

    /** Download shaping: the body, and the length the answer declares for it. */
    private var downloadBody = ByteArray(0)
    private var downloadDeclared = -1L

    private val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    @Before
    fun start() {
        recorded.clear()
        stored = ByteArray(0)
        droppedChunkAnswers = 0
        droppedCompleteAnswers = 0
        sanitizedName = "photo.png"
        downloadBody = ByteArray(0)
        downloadDeclared = -1L
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handle(exchange) }
        server.executor = null
        server.start()
        val client = OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            // A repeated POST is this client's own decision, never the socket layer's.
            .retryOnConnectionFailure(false)
            .build()
        api = RemoteAttachmentApi(baseUrl, client)
        source = File(Files.createTempDirectory("attachments-source").toFile(), "photo.png")
        source.writeBytes(ORIGINAL)
        target = File(Files.createTempDirectory("attachments-target").toFile(), "downloaded.png")
    }

    @After
    fun stop() {
        source.delete()
        target.delete()
        server.stop(0)
    }

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes()
        val request = Recorded(
            path = exchange.requestURI.path,
            contentType = exchange.requestHeaders.getFirst("Content-Type"),
            uploadId = exchange.requestHeaders.getFirst("x-upload-id"),
            offset = exchange.requestHeaders.getFirst("x-upload-offset"),
            body = body,
        )
        recorded += request
        when (request.path) {
            "/attachments/propose" -> propose(exchange, request)
            "/attachments/chunk" -> chunk(exchange, request)
            "/attachments/complete" -> complete(exchange)
            "/attachments/download" -> download(exchange)
            else -> fail("the client called ${request.path}")
        }
    }

    /** A proposal is stored exactly as asked, and named as the backend chooses. */
    private fun propose(exchange: HttpExchange, request: Recorded) {
        val document = Json.parseToJsonElement(request.text).jsonObject
        json(
            exchange,
            200,
            """{"ok":true,"upload":${upload(
                uploadId = UPLOAD_ID,
                name = sanitizedName,
                mime = document.getValue("mime").jsonPrimitive.content,
                size = document.getValue("size").jsonPrimitive.content.toLong(),
                sha256 = document.getValue("sha256").jsonPrimitive.content,
                status = 0,
                offset = 0,
            )}}""",
        )
    }

    /**
     * Bytes are taken only at the exact next offset, so a repeated chunk is
     * answered from what is already stored, and any other offset is refused.
     */
    private fun chunk(exchange: HttpExchange, request: Recorded) {
        val offset = request.offset!!.toLong()
        val end = offset + request.body.size
        val accepted = when {
            offset == stored.size.toLong() -> {
                stored += request.body
                true
            }

            end <= stored.size -> stored.copyOfRange(offset.toInt(), end.toInt())
                .contentEquals(request.body)

            else -> false
        }
        if (!accepted) {
            json(
                exchange,
                409,
                """{"ok":false,"error":{"code":"attachments/offset-conflict","message":"not the next offset"}}""",
            )
            return
        }
        // The bytes are taken; only the answer can go missing.
        if (droppedChunkAnswers > 0) {
            droppedChunkAnswers--
            exchange.close()
            return
        }
        json(
            exchange,
            200,
            """{"ok":true,"upload":${upload(
                uploadId = UPLOAD_ID,
                name = sanitizedName,
                mime = MIME,
                size = ORIGINAL.size.toLong(),
                sha256 = digest(),
                status = 0,
                offset = stored.size.toLong(),
            )}}""",
        )
    }

    private fun complete(exchange: HttpExchange) {
        if (droppedCompleteAnswers > 0) {
            droppedCompleteAnswers--
            exchange.close()
            return
        }
        json(
            exchange,
            200,
            """{"ok":true,"upload":${upload(
                uploadId = UPLOAD_ID,
                name = sanitizedName,
                mime = MIME,
                size = ORIGINAL.size.toLong(),
                sha256 = digest(),
                status = 1,
                offset = ORIGINAL.size.toLong(),
            )}}""",
        )
    }

    private fun download(exchange: HttpExchange) {
        exchange.responseHeaders.add("Content-Type", MIME)
        val declared = if (downloadDeclared >= 0) downloadDeclared else downloadBody.size.toLong()
        exchange.sendResponseHeaders(200, declared)
        exchange.responseBody.use { it.write(downloadBody) }
    }

    private fun upload(
        uploadId: String,
        name: String,
        mime: String,
        size: Long,
        sha256: String,
        status: Int,
        offset: Long,
    ) = """{"uploadId":"$uploadId","name":"$name","mime":"$mime","size":$size,""" +
        """"sha256":"$sha256","status":$status,"offset":$offset,"expiresAt":$EXPIRES_AT}"""

    private fun json(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun requests(path: String): List<Recorded> = recorded.filter { it.path == path }

    private fun digest(): String = MessageDigest.getInstance("SHA-256")
        .digest(ORIGINAL)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    // ---- uploading an original ----

    @Test
    fun `upload proposes the original file and sends it as ordered sequential chunks`() = runTest {
        val receipt = api.upload(TOKEN, "s-1", source, "photo.png", MIME)

        val document = Json.parseToJsonElement(requests("/attachments/propose").single().text).jsonObject
        assertEquals("s-1", document.getValue("sessionId").jsonPrimitive.content)
        assertEquals("photo.png", document.getValue("name").jsonPrimitive.content)
        assertEquals(MIME, document.getValue("mime").jsonPrimitive.content)
        // The proposal states the original's own size and digest: what arrives is
        // verified against exactly these.
        assertEquals(ORIGINAL.size.toLong(), document.getValue("size").jsonPrimitive.content.toLong())
        assertEquals(digest(), document.getValue("sha256").jsonPrimitive.content)

        val chunks = requests("/attachments/chunk")
        // 600000 bytes at 256KiB is three chunks, not one and not four.
        assertEquals(3, chunks.size)
        assertEquals(listOf("0", "262144", "524288"), chunks.map { it.offset })
        assertTrue(chunks.all { it.uploadId == UPLOAD_ID })
        assertTrue(chunks.all { it.contentType == "application/octet-stream" })
        // What arrived is the original, in order, byte for byte.
        assertArrayEquals(ORIGINAL, chunks.fold(ByteArray(0)) { all, chunk -> all + chunk.body })

        assertEquals("""{"uploadId":"$UPLOAD_ID"}""", requests("/attachments/complete").single().text)

        assertEquals(UPLOAD_ID, receipt.uploadId)
        assertEquals(1, receipt.status)
        assertEquals(ORIGINAL.size.toLong(), receipt.offset)
        assertEquals(ORIGINAL.size.toLong(), receipt.size)
        assertEquals(digest(), receipt.sha256)
    }

    @Test
    fun `a lost chunk answer is repeated with the same bytes and offset, not reproposed`() = runTest {
        // The backend took the chunk and the answer went missing. The only way back
        // is the same bytes at the same offset, which is idempotent there.
        droppedChunkAnswers = 1

        val receipt = api.upload(TOKEN, "s-1", source, "photo.png", MIME)

        val chunks = requests("/attachments/chunk")
        assertEquals(4, chunks.size)
        assertEquals(chunks[0].offset, chunks[1].offset)
        assertArrayEquals(chunks[0].body, chunks[1].body)
        // One upload identity throughout: a second proposal would be a second,
        // unrelated upload, not a retry of this one.
        assertEquals(1, requests("/attachments/propose").size)
        assertEquals(1, requests("/attachments/complete").size)
        assertEquals(1, receipt.status)
        assertEquals(ORIGINAL.size.toLong(), receipt.offset)
    }

    @Test
    fun `a lost completion answer is repeated once under the same upload id`() = runTest {
        droppedCompleteAnswers = 1

        val receipt = api.upload(TOKEN, "s-1", source, "photo.png", MIME)

        // The original was already durable; the same request returns the same receipt.
        val completions = requests("/attachments/complete")
        assertEquals(2, completions.size)
        assertEquals(listOf(UPLOAD_ID, UPLOAD_ID), completions.map {
            Json.parseToJsonElement(it.text).jsonObject.getValue("uploadId").jsonPrimitive.content
        })
        assertEquals(1, receipt.status)
        assertEquals(ORIGINAL.size.toLong(), receipt.offset)
    }

    // ---- downloading an original ----

    @Test
    fun `download writes exactly the original bytes the message describes`() = runTest {
        downloadBody = ORIGINAL

        api.download(TOKEN, "s-1", "m-2", attachment(ORIGINAL.size.toLong()), target)

        assertEquals(
            """{"sessionId":"s-1","messageId":"m-2","index":0}""",
            requests("/attachments/download").single().text,
        )
        assertArrayEquals(ORIGINAL, target.readBytes())
    }

    @Test
    fun `a truncated download is refused and leaves nothing behind`() = runTest {
        // The declared length promises the original; the body stops early.
        downloadBody = ORIGINAL.copyOfRange(0, 1024)
        downloadDeclared = ORIGINAL.size.toLong()

        try {
            api.download(TOKEN, "s-1", "m-2", attachment(ORIGINAL.size.toLong()), target)
            fail("expected the truncated download to be raised")
        } catch (unreachable: IdentityUnreachable) {
            assertNotNull(unreachable.cause)
        }
        // A short file is not the original, and must not be left looking like one.
        assertFalse(target.exists())
    }

    private fun attachment(bytes: Long) = RemoteChatAttachment(
        index = 0,
        type = 1,
        name = "photo.png",
        mime = MIME,
        bytes = bytes,
    )

    private companion object {
        const val TOKEN = "cred-alice"
        const val UPLOAD_ID = "0123456789abcdef0123456789abcdef"
        const val MIME = "image/png"
        const val EXPIRES_AT = 1760000000000L

        /** 600000 bytes: two whole 256KiB chunks and a short tail. */
        val ORIGINAL: ByteArray = ByteArray(600_000) { index -> (index * 31 % 251).toByte() }
    }
}
