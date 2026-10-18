package me.rerere.rikkahub.data.remote

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * The gateway wire, against a real HTTP server on loopback.
 *
 * The double is the JDK's own [HttpServer] for the same reason the identity,
 * session, chat and speech clients use it: what is under test is the bytes on
 * the wire — route paths, the Bearer credential, the JSON document each
 * operation sends, and the backend's own refusal codes surviving to the caller
 * instead of being folded into a generic failure.
 *
 * The expected documents below are written as JSON literals from the ticket's
 * contract, not composed through the client's own types: a test that re-encodes
 * with the implementation would agree with a wrong body.
 */
class GatewayApiTest {

    private data class Recorded(
        val method: String,
        val path: String,
        val contentType: String?,
        val authorization: String?,
        val body: String,
    )

    private lateinit var server: HttpServer
    private lateinit var api: GatewayApi
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
        api = GatewayApi(
            baseUrl = baseUrl,
            client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build(),
        )
    }

    @After
    fun stop() {
        runCatching { server.stop(0) }
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

    /** Compare documents as JSON values, so member order and formatting are not the assertion. */
    private fun assertJson(expected: String, actual: String) {
        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(actual))
    }

    private fun only(): Recorded {
        assertEquals("exactly one request", 1, recorded.size)
        return recorded.single()
    }

    @Test
    fun `read posts the credential and decodes the whole gateway configuration`() = runTest {
        reply(200, READ)

        val config = api.read("token-abc")

        val request = only()
        assertEquals("POST", request.method)
        assertEquals("/gateway/read", request.path)
        assertEquals("Bearer token-abc", request.authorization)
        assertEquals("application/json; charset=utf-8", request.contentType)
        assertJson("{}", request.body)

        assertEquals("deepseek-official", config.selection.provider)
        assertEquals("deepseek-flash", config.selection.model)

        val route = config.routes.single()
        assertEquals("minimax", route.provider)
        assertEquals("MiniMax", route.displayName)
        assertEquals("anthropic-messages", route.api)
        assertEquals("https://api.minimax.io/anthropic", route.baseURL)
        assertEquals("MINIMAX_API_KEY", route.apiKeyEnv)
        // The credential is a reference name with its management state; no
        // secret value has a field here to decode into.
        assertEquals("MINIMAX_API_KEY", route.credential.ref)
        assertTrue(route.credential.configured)
        assertEquals(false, route.credential.writable)
        assertTrue("a running reply still uses this route", route.inUse)
        val model = route.models.single()
        assertEquals("MiniMax-M2", model.id)
        assertEquals("MiniMax M2", model.name)
        assertEquals(200_000, model.contextWindow)
        assertEquals(32_768, model.maxTokens)
        assertEquals(listOf("text", "image"), model.input)

        assertEquals("FunAudioLLM/SenseVoiceSmall", config.asr.model)
        assertEquals("https://api.siliconflow.cn/v1/audio/transcriptions", config.asr.endpoint)
        assertEquals(60_000, config.asr.maxDurationMs)
        assertEquals(1_048_576, config.asr.maxAudioBytes)
        assertEquals(30_000, config.asr.timeoutMs)
        assertEquals(4, config.asr.maxGlobalInFlight)
        assertEquals(1, config.asr.maxUserInFlight)
        assertTrue(config.asr.available)
        assertEquals("VOICE_API_KEY", config.asr.credential.ref)

        assertTrue(config.editable.selection)
        assertTrue(config.editable.routes)
        assertTrue(config.editable.asr)
        assertTrue(config.saved.selection)
        assertTrue(config.saved.routes)
        assertEquals(false, config.saved.asr)
    }

    @Test
    fun `selection is posted exactly and the backend's next-turn answer comes back`() = runTest {
        reply(200, """{"ok":true,"selection":{"provider":"minimax","model":"MiniMax-M2","reasoningEffort":"high"},"applies":"next-turn"}""")

        val saved = api.select(
            "token-abc",
            GatewaySelection(provider = "minimax", model = "MiniMax-M2", reasoningEffort = "high"),
        )

        val request = only()
        assertEquals("/gateway/selection", request.path)
        assertJson(
            """{"provider":"minimax","model":"MiniMax-M2","reasoningEffort":"high"}""",
            request.body,
        )
        assertEquals("minimax", saved.selection.provider)
        assertEquals("MiniMax-M2", saved.selection.model)
        assertEquals("high", saved.selection.reasoningEffort)
        assertEquals("next-turn", saved.applies)
    }

    @Test
    fun `an unset reasoning effort is left out of the selection document`() = runTest {
        reply(200, """{"ok":true,"selection":{"provider":"deepseek-official","model":"deepseek-flash"},"applies":"next-turn"}""")

        api.select("token-abc", GatewaySelection(provider = "deepseek-official", model = "deepseek-flash"))

        val request = only()
        assertJson("""{"provider":"deepseek-official","model":"deepseek-flash"}""", request.body)
    }

    @Test
    fun `a route is posted with its models and the saved route comes back`() = runTest {
        reply(200, ROUTE_ENVELOPE)

        val saved = api.saveRoute(
            "token-abc",
            GatewayRouteDraft(
                provider = "minimax",
                displayName = "MiniMax",
                api = "anthropic-messages",
                baseURL = "https://api.minimax.io/anthropic",
                apiKeyEnv = "MINIMAX_API_KEY",
                models = listOf(
                    GatewayRouteModel(
                        id = "MiniMax-M2",
                        name = "MiniMax M2",
                        contextWindow = 200_000,
                        maxTokens = 32_768,
                        input = listOf("text", "image"),
                    ),
                ),
            ),
        )

        val request = only()
        assertEquals("/gateway/route", request.path)
        assertJson(
            """
            {"provider":"minimax","displayName":"MiniMax","api":"anthropic-messages",
             "baseURL":"https://api.minimax.io/anthropic","apiKeyEnv":"MINIMAX_API_KEY",
             "models":[{"id":"MiniMax-M2","name":"MiniMax M2","contextWindow":200000,
                        "maxTokens":32768,"input":["text","image"]}]}
            """,
            request.body,
        )
        assertEquals("minimax", saved.provider)
        assertEquals("MiniMax", saved.displayName)
        assertEquals("MINIMAX_API_KEY", saved.credential.ref)
    }

    @Test
    fun `a route deletion names only the provider`() = runTest {
        reply(200, """{"ok":true}""")

        api.deleteRoute("token-abc", "minimax")

        val request = only()
        assertEquals("/gateway/route/delete", request.path)
        assertJson("""{"provider":"minimax"}""", request.body)
    }

    @Test
    fun `the asr connection is posted field by field and its availability comes back`() = runTest {
        reply(200, ASR_ENVELOPE)

        val saved = api.saveAsr(
            "token-abc",
            GatewayAsrDraft(
                model = "FunAudioLLM/SenseVoiceSmall",
                endpoint = "https://api.siliconflow.cn/v1/audio/transcriptions",
                maxDurationMs = 60_000,
                maxAudioBytes = 1_048_576,
                timeoutMs = 30_000,
                maxGlobalInFlight = 4,
                maxUserInFlight = 1,
            ),
        )

        val request = only()
        assertEquals("/gateway/asr", request.path)
        assertJson(
            """
            {"model":"FunAudioLLM/SenseVoiceSmall",
             "endpoint":"https://api.siliconflow.cn/v1/audio/transcriptions",
             "maxDurationMs":60000,"maxAudioBytes":1048576,"timeoutMs":30000,
             "maxGlobalInFlight":4,"maxUserInFlight":1}
            """,
            request.body,
        )
        assertTrue(saved.available)
        assertEquals("VOICE_API_KEY", saved.credential.ref)
    }

    @Test
    fun `every gateway refusal keeps its status code and message`() = runTest {
        val refusals = listOf(
            Triple(401, "gateway/unauthorized", "sign in as the administrator"),
            Triple(403, "gateway/forbidden", "the administrator configures the gateway"),
            Triple(409, "gateway/route-in-use", "a running reply still uses this route"),
            Triple(409, "gateway/route-selected", "the shared selection uses this route"),
            Triple(400, "gateway/invalid-selection", "the provider is not configured"),
            Triple(400, "gateway/invalid-route", "the base URL is not a URL"),
            Triple(400, "gateway/invalid-asr", "the endpoint is required"),
            Triple(503, "gateway/configuration-unavailable", "the gateway is not mounted"),
        )

        for ((status, code, message) in refusals) {
            reply(status, """{"ok":false,"error":{"code":"$code","message":"$message"}}""")

            val refused = refused {
                api.select("token-abc", GatewaySelection(provider = "p", model = "m"))
            }

            assertEquals(status, refused.status)
            assertEquals(code, refused.code)
            assertEquals(message, refused.message)
        }
    }

    @Test
    fun `a refusal sent with a success status is still a refusal, not an answer`() = runTest {
        reply(200, """{"ok":false,"error":{"code":"gateway/invalid-route","message":"unknown protocol"}}""")

        val refused = refused { api.deleteRoute("token-abc", "minimax") }

        assertEquals(200, refused.status)
        assertEquals("gateway/invalid-route", refused.code)
        assertEquals("unknown protocol", refused.message)
    }

    @Test
    fun `an unreachable gateway is unreachable rather than a refusal`() = runTest {
        server.stop(0)

        val outcome = runCatching { api.read("token-abc") }

        assertTrue(
            "a stopped server must read as unreachable, not as a refusal or a blank answer",
            outcome.exceptionOrNull() is IdentityUnreachable,
        )
        assertFalse(outcome.exceptionOrNull() is IdentityRefused)
    }

    private suspend inline fun refused(block: suspend () -> Unit): IdentityRefused =
        try {
            block()
            throw AssertionError("the call answered instead of refusing")
        } catch (refused: IdentityRefused) {
            refused
        }

    private companion object {
        const val READ = """
        {"ok":true,"gateway":{
          "selection":{"provider":"deepseek-official","model":"deepseek-flash"},
          "routes":[{
            "provider":"minimax","displayName":"MiniMax","api":"anthropic-messages",
            "baseURL":"https://api.minimax.io/anthropic","apiKeyEnv":"MINIMAX_API_KEY",
            "models":[{"id":"MiniMax-M2","name":"MiniMax M2","contextWindow":200000,
                       "maxTokens":32768,"input":["text","image"]}],
            "credential":{"ref":"MINIMAX_API_KEY","configured":true,"writable":false},
            "inUse":true
          }],
          "asr":{
            "model":"FunAudioLLM/SenseVoiceSmall",
            "endpoint":"https://api.siliconflow.cn/v1/audio/transcriptions",
            "maxDurationMs":60000,"maxAudioBytes":1048576,"timeoutMs":30000,
            "maxGlobalInFlight":4,"maxUserInFlight":1,"available":true,
            "credential":{"ref":"VOICE_API_KEY","configured":true,"writable":false}
          },
          "editable":{"selection":true,"routes":true,"asr":true},
          "saved":{"selection":true,"routes":true,"asr":false}
        }}
        """

        const val ROUTE_ENVELOPE = """
        {"ok":true,"route":{
          "provider":"minimax","displayName":"MiniMax","api":"anthropic-messages",
          "baseURL":"https://api.minimax.io/anthropic","apiKeyEnv":"MINIMAX_API_KEY",
          "models":[{"id":"MiniMax-M2","name":"MiniMax M2","contextWindow":200000,
                     "maxTokens":32768,"input":["text","image"]}],
          "credential":{"ref":"MINIMAX_API_KEY","configured":true,"writable":false},
          "inUse":false
        }}
        """

        const val ASR_ENVELOPE = """
        {"ok":true,"asr":{
          "model":"FunAudioLLM/SenseVoiceSmall",
          "endpoint":"https://api.siliconflow.cn/v1/audio/transcriptions",
          "maxDurationMs":60000,"maxAudioBytes":1048576,"timeoutMs":30000,
          "maxGlobalInFlight":4,"maxUserInFlight":1,"available":true,
          "credential":{"ref":"VOICE_API_KEY","configured":true,"writable":false}
        }}
        """
    }
}
