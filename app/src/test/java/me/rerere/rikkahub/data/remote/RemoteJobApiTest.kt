package me.rerere.rikkahub.data.remote

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Actual HTTP/JSON adapter with a deterministic network boundary, not a live backend. */
class RemoteJobApiTest {
    private lateinit var server: HttpServer
    private lateinit var api: RemoteJobApi
    private var response = "{}"
    private var status = 200
    private var path = ""
    private var body = ""
    private var authorization: String? = null
    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            path = exchange.requestURI.path
            body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            authorization = exchange.requestHeaders.getFirst("Authorization")
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        api = RemoteJobApi("http://127.0.0.1:${server.address.port}", OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build())
    }
    @After fun stop() { server.stop(0) }

    @Test fun `output uses absolute byte cursor and authenticated original session task identity`() = runTest {
        response = """{"ok":true,"output":{"taskId":"gen/bash-1","availability":1,"chunks":[{"at":3,"text":"你好🙂","channel":"stdout","gapBefore":true}],"next":13,"earliest":3,"total":13,"lossy":true,"status":3}}"""
        val output = api.output("alice", "s1", "gen/bash-1", 3)
        assertEquals("/chat/jobs/output", path)
        assertEquals("Bearer alice", authorization)
        val document = Json.parseToJsonElement(body).jsonObject
        assertEquals("s1", document.getValue("sessionId").jsonPrimitive.content)
        assertEquals("gen/bash-1", document.getValue("taskId").jsonPrimitive.content)
        assertEquals("3", document.getValue("from").jsonPrimitive.content)
        assertEquals("你好🙂", output.chunks.single().text)
        assertEquals(13L, output.next)
        assertTrue(output.lossy)
        assertTrue(output.chunks.single().gapBefore)
    }

    @Test fun `stop task is distinct from reply stop and returns only admission outcome`() = runTest {
        response = """{"ok":true,"outcome":1}"""
        assertEquals(1, api.stop("alice", "s1", "gen/bash-1"))
        assertEquals("/chat/jobs/stop", path)
        assertEquals("Bearer alice", authorization)
        assertEquals("""{"sessionId":"s1","taskId":"gen/bash-1"}""", body)
    }

    @Test fun `known terminal task can have unavailable output after server restart`() = runTest {
        response = """{"ok":true,"output":{"taskId":"old/bash-1","availability":0,"chunks":[],"next":0,"earliest":0,"total":31,"lossy":false,"status":3}}"""
        val output = api.output("alice", "s1", "old/bash-1", 0)
        assertEquals(0, output.availability)
        assertEquals(3, output.status)
        assertEquals(31L, output.total)
        assertTrue(output.chunks.isEmpty())
    }

    @Test fun `a refused owner does not adopt a success shaped payload`() = runTest {
        status = 403
        response = """{"ok":true,"outcome":1,"error":{"code":"sessions/not-owned","message":"not yours"}}"""
        try { api.stop("bob", "s1", "gen/bash-1"); fail("expected refusal") }
        catch (failure: IdentityRefused) { assertEquals(403, failure.status); assertEquals("sessions/not-owned", failure.code) }
    }

    @Test fun `chat projection carries native lifecycle and report facts without another roster`() {
        val projection = Json { ignoreUnknownKeys = true }.decodeFromString<RemoteChatProjection>(
            """{"sessionId":"s1","tasks":[{"taskId":"gen/bash-1","kind":"bash","label":"long script","status":3,"availability":0,"startedAt":10,"finishedAt":20,"output":{"earliest":0,"total":31},"report":{"status":3,"messageId":"result-1","turn":4}}]}"""
        )
        val task = projection.tasks.single()
        assertEquals("gen/bash-1", task.taskId)
        assertEquals(3, task.status)
        assertEquals(0, task.availability)
        assertEquals("result-1", task.report.messageId)
        assertEquals(4, task.report.turn)
    }
}
