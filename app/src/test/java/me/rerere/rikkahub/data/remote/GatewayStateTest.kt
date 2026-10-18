package me.rerere.rikkahub.data.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the gateway page shows while it reads and writes.
 *
 * The fake backend here is a scripted [GatewayBackend]: the assertions are about
 * what the page may claim — that an unreachable server says unreachable, that a
 * refusal keeps the backend's own code and message, that a refused write does
 * not silently look saved, and that "in use" is a state the page can show before
 * an edit is attempted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private class ScriptedGateway(
    var read: suspend () -> GatewayConfig = { config() },
    var select: suspend (GatewaySelection) -> GatewaySelectionSaved = {
        GatewaySelectionSaved(it, applies = "next-turn")
    },
    var saveRoute: suspend (GatewayRouteDraft) -> GatewayRoute = { draft ->
        route(provider = draft.provider, displayName = draft.displayName, inUse = false)
    },
    var deleteRoute: suspend (String) -> Unit = {},
    var saveAsr: suspend (GatewayAsrDraft) -> GatewayAsr = { asrConfig(it.model) },
) : GatewayBackend {
    val selections = mutableListOf<GatewaySelection>()
    val savedRoutes = mutableListOf<GatewayRouteDraft>()
    val deleted = mutableListOf<String>()
    val savedAsr = mutableListOf<GatewayAsrDraft>()

    override suspend fun read(token: String): GatewayConfig = read()

    override suspend fun select(token: String, selection: GatewaySelection): GatewaySelectionSaved {
        selections += selection
        return select(selection)
    }

    override suspend fun saveRoute(token: String, route: GatewayRouteDraft): GatewayRoute {
        savedRoutes += route
        return saveRoute(route)
    }

    override suspend fun deleteRoute(token: String, provider: String) {
        deleted += provider
        deleteRoute(provider)
    }

    override suspend fun saveAsr(token: String, asr: GatewayAsrDraft): GatewayAsr {
        savedAsr += asr
        return saveAsr(asr)
    }
}

private fun route(
    provider: String = "minimax",
    displayName: String? = "MiniMax",
    inUse: Boolean = false,
    editable: Boolean = true,
) = GatewayRoute(
    provider = provider,
    displayName = displayName,
    api = "anthropic-messages",
    baseURL = "https://api.minimax.io/anthropic",
    apiKeyEnv = "MINIMAX_API_KEY",
    models = listOf(GatewayRouteModel(id = "MiniMax-M2", name = "MiniMax M2")),
    credential = GatewayCredential(ref = "MINIMAX_API_KEY", configured = true, writable = editable),
    inUse = inUse,
)

private fun asrConfig(model: String = "FunAudioLLM/SenseVoiceSmall") = GatewayAsr(
    model = model,
    endpoint = "https://api.siliconflow.cn/v1/audio/transcriptions",
    maxDurationMs = 60_000,
    maxAudioBytes = 1_048_576,
    timeoutMs = 30_000,
    maxGlobalInFlight = 4,
    maxUserInFlight = 1,
    available = true,
    credential = GatewayCredential(ref = "VOICE_API_KEY", configured = true, writable = false),
)

private fun config(
    selection: GatewaySelection = GatewaySelection("deepseek-official", "deepseek-flash"),
    routes: List<GatewayRoute> = listOf(route()),
    editable: GatewayEditable = GatewayEditable(selection = true, routes = true, asr = true),
    saved: GatewaySaved = GatewaySaved(selection = true, routes = true, asr = false),
) = GatewayConfig(
    selection = selection,
    routes = routes,
    asr = asrConfig(),
    editable = editable,
    saved = saved,
)

class GatewayStateTest {

    @Test
    fun `a read shows the backend's configuration`() = runTest {
        val backend = ScriptedGateway()
        val state = GatewayState(backend, this)

        state.load("token-abc")
        advanceUntilIdle()

        val snapshot = state.snapshot.value
        assertFalse(snapshot.loading)
        assertNull(snapshot.failure)
        assertEquals("deepseek-flash", snapshot.config?.selection?.model)
        assertEquals(listOf("minimax"), snapshot.config?.routes?.map { it.provider })
    }

    @Test
    fun `an unreachable backend says unreachable and never shows an empty configuration`() = runTest {
        val backend = ScriptedGateway(read = { throw IdentityUnreachable(java.io.IOException("down")) })
        val state = GatewayState(backend, this)

        state.load("token-abc")
        advanceUntilIdle()

        val snapshot = state.snapshot.value
        assertNull("an unreachable read is not an empty gateway", snapshot.config)
        assertTrue(snapshot.failure?.unreachable == true)
        assertEquals("", snapshot.failure?.code)
    }

    @Test
    fun `a refusal keeps the backend's code and message`() = runTest {
        val backend = ScriptedGateway(
            read = { throw IdentityRefused(403, "gateway/forbidden", "the administrator configures the gateway") },
        )
        val state = GatewayState(backend, this)

        state.load("token-abc")
        advanceUntilIdle()

        val failure = state.snapshot.value.failure
        assertNotNull(failure)
        assertFalse(failure!!.unreachable)
        assertEquals("gateway/forbidden", failure.code)
        assertEquals("the administrator configures the gateway", failure.message)
    }

    @Test
    fun `a refused route deletion is shown as itself and keeps the loaded configuration`() = runTest {
        val backend = ScriptedGateway(
            deleteRoute = { throw IdentityRefused(409, "gateway/route-in-use", "a running reply still uses this route") },
        )
        val state = GatewayState(backend, this)
        state.load("token-abc")
        advanceUntilIdle()

        state.deleteRoute("token-abc", "minimax")
        advanceUntilIdle()

        val snapshot = state.snapshot.value
        assertEquals("gateway/route-in-use", snapshot.failure?.code)
        assertEquals("a running reply still uses this route", snapshot.failure?.message)
        assertEquals("a refusal must not wipe what was already read", "minimax", snapshot.config?.routes?.single()?.provider)
        assertEquals(listOf("minimax"), backend.deleted)
    }

    @Test
    fun `a saved selection is followed by a fresh read so the page shows what the backend stored`() = runTest {
        var current = config()
        val backend = ScriptedGateway(
            read = { current },
            select = { chosen ->
                current = current.copy(selection = chosen, saved = current.saved.copy(selection = true))
                GatewaySelectionSaved(chosen, applies = "next-turn")
            },
        )
        val state = GatewayState(backend, this)
        state.load("token-abc")
        advanceUntilIdle()

        val chosen = GatewaySelection("minimax", "MiniMax-M2", reasoningEffort = "high")
        state.select("token-abc", chosen)
        advanceUntilIdle()

        assertEquals(listOf(chosen), backend.selections)
        assertEquals("minimax", state.snapshot.value.config?.selection?.provider)
        assertNull(state.snapshot.value.failure)
    }

    @Test
    fun `a refused selection is shown as its own code and does not claim the new model`() = runTest {
        val backend = ScriptedGateway(
            select = { throw IdentityRefused(400, "gateway/invalid-selection", "the provider is not configured") },
        )
        val state = GatewayState(backend, this)
        state.load("token-abc")
        advanceUntilIdle()

        state.select("token-abc", GatewaySelection("missing", "none"))
        advanceUntilIdle()

        val snapshot = state.snapshot.value
        assertEquals("gateway/invalid-selection", snapshot.failure?.code)
        assertEquals("the provider is not configured", snapshot.failure?.message)
        assertEquals("deepseek-flash", snapshot.config?.selection?.model)
    }

    @Test
    fun `a route the backend says is in use is locked before the edit is attempted`() {
        val inUse = route(inUse = true)
        assertEquals(GatewayRouteLock.IN_USE, inUse.lock(GatewayEditable(routes = true)))

        val readOnly = route(inUse = false)
        assertEquals(GatewayRouteLock.READ_ONLY, readOnly.lock(GatewayEditable(routes = false)))

        val free = route(inUse = false)
        assertNull(free.lock(GatewayEditable(routes = true)))
    }

    @Test
    fun `an accepted route write closes the editor even when the follow-up read fails`() = runTest {
        var reads = 0
        val backend = ScriptedGateway(
            read = {
                reads += 1
                if (reads == 1) config() else throw IdentityUnreachable(java.io.IOException("gone"))
            },
        )
        val state = GatewayState(backend, this)
        state.load("token-abc")
        advanceUntilIdle()

        var closed = false
        state.saveRoute(
            "token-abc",
            GatewayRouteDraft(provider = "minimax", models = emptyList()),
            onSaved = { closed = true },
        )
        advanceUntilIdle()

        assertTrue("the backend stored the route, so the form may close", closed)
        assertTrue("the failed re-read is still reported", state.snapshot.value.failure?.unreachable == true)
    }

    @Test
    fun `a refused route write keeps the editor open`() = runTest {
        val backend = ScriptedGateway(
            saveRoute = { throw IdentityRefused(409, "gateway/route-in-use", "a running reply still uses this route") },
        )
        val state = GatewayState(backend, this)
        state.load("token-abc")
        advanceUntilIdle()

        var closed = false
        state.saveRoute(
            "token-abc",
            GatewayRouteDraft(provider = "minimax", models = emptyList()),
            onSaved = { closed = true },
        )
        advanceUntilIdle()

        assertEquals(false, closed)
        assertEquals("gateway/route-in-use", state.snapshot.value.failure?.code)
    }

    @Test
    fun `a write waits for the previous one instead of racing it`() = runTest {
        val first = CompletableDeferred<Unit>()
        val backend = ScriptedGateway(
            deleteRoute = { first.await() },
        )
        val state = GatewayState(backend, this)
        state.load("token-abc")
        advanceUntilIdle()

        state.deleteRoute("token-abc", "first")
        state.deleteRoute("token-abc", "second")
        advanceUntilIdle()
        assertEquals("one write at a time", listOf("first"), backend.deleted)

        first.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("first", "second"), backend.deleted)
    }
}
