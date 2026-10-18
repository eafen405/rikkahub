package me.rerere.rikkahub.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Where one credential reference stands.
 *
 * [ref] is an environment-variable NAME — `MINIMAX_API_KEY`, `VOICE_API_KEY` —
 * never a secret value. The backend reads the value from its own environment;
 * this client has no field that could carry one, so a secret cannot be sent by
 * accident or typed into the page.
 *
 * [writable] says whether this deployment lets the phone write that reference
 * at all. When it is false the reference is shown as it is and left alone.
 */
@Serializable
data class GatewayCredential(
    val ref: String,
    val configured: Boolean = false,
    val writable: Boolean = false,
)

/** One model in a route's catalog, as `llm-pi-ai` declares it. */
@Serializable
data class GatewayRouteModel(
    val id: String,
    val name: String? = null,
    val contextWindow: Int? = null,
    val maxTokens: Int? = null,
    /**
     * Accepted modalities (`text`, `image`). Kept as the backend's own strings
     * rather than an enum: a modality this build does not know is still a fact
     * about the route, and refusing to decode it would hide the whole route.
     */
    val input: List<String>? = null,
)

/** One configurable provider route: the shared supplier and its models. */
@Serializable
data class GatewayRoute(
    val provider: String,
    val displayName: String? = null,
    val api: String? = null,
    val baseURL: String? = null,
    val apiKeyEnv: String? = null,
    val models: List<GatewayRouteModel> = emptyList(),
    val credential: GatewayCredential,
    /**
     * A running reply still uses this route, so changing or deleting it is
     * refused until that reply ends. The page shows it disabled with the reason
     * instead of offering an edit the backend will reject.
     */
    val inUse: Boolean = false,
)

/** The shared model selection every conversation adopts from its next reply. */
@Serializable
data class GatewaySelection(
    val provider: String,
    val model: String,
    val reasoningEffort: String? = null,
)

/**
 * The ASR connection the backend uses for speech input.
 *
 * Every setting is nullable because a deployment whose `speech` row is not
 * mounted answers `null` for it; the page then shows the connection as not
 * configurable here instead of failing to decode the whole document.
 */
@Serializable
data class GatewayAsr(
    val model: String? = null,
    val endpoint: String? = null,
    val maxDurationMs: Int? = null,
    val maxAudioBytes: Int? = null,
    val timeoutMs: Int? = null,
    val maxGlobalInFlight: Int? = null,
    val maxUserInFlight: Int? = null,
    val available: Boolean = false,
    val credential: GatewayCredential,
)

/** Which parts of the gateway this deployment lets the phone change. */
@Serializable
data class GatewayEditable(
    val selection: Boolean = false,
    val routes: Boolean = false,
    val asr: Boolean = false,
)

/**
 * What the backend actually persisted on the last write.
 *
 * Deliberately separate from [GatewayEditable]: being allowed to ask is not the
 * same as the answer having been stored, and the page must not present one as
 * the other.
 */
@Serializable
data class GatewaySaved(
    val selection: Boolean = false,
    val routes: Boolean = false,
    val asr: Boolean = false,
)

/** One whole read of the gateway configuration. */
@Serializable
data class GatewayConfig(
    val selection: GatewaySelection,
    val routes: List<GatewayRoute> = emptyList(),
    val asr: GatewayAsr,
    val editable: GatewayEditable = GatewayEditable(),
    val saved: GatewaySaved = GatewaySaved(),
)

/** The saved selection, and when it takes effect (`next-turn`). */
data class GatewaySelectionSaved(
    val selection: GatewaySelection,
    val applies: String,
)

/** What the phone sends to add or replace a route. No secret value has a field here. */
@Serializable
data class GatewayRouteDraft(
    val provider: String,
    val displayName: String? = null,
    val api: String? = null,
    val baseURL: String? = null,
    val apiKeyEnv: String? = null,
    val models: List<GatewayRouteModel>,
)

/** What the phone sends to change the ASR connection. */
@Serializable
data class GatewayAsrDraft(
    val model: String,
    val endpoint: String,
    val maxDurationMs: Int,
    val maxAudioBytes: Int,
    val timeoutMs: Int,
    val maxGlobalInFlight: Int,
    val maxUserInFlight: Int,
)

/** The gateway operations this app performs. [GatewayApi] is the HTTP implementation. */
interface GatewayBackend {
    /** The current shared selection, every route, the ASR connection, and what may be changed. */
    suspend fun read(token: String): GatewayConfig

    /** Save the shared selection; it applies from each conversation's next reply. */
    suspend fun select(token: String, selection: GatewaySelection): GatewaySelectionSaved

    /** Add or replace one provider route. */
    suspend fun saveRoute(token: String, route: GatewayRouteDraft): GatewayRoute

    /** Delete one provider route. */
    suspend fun deleteRoute(token: String, provider: String)

    /** Save the ASR connection. */
    suspend fun saveAsr(token: String, asr: GatewayAsrDraft): GatewayAsr
}

@Serializable
internal data class GatewayEnvelope(
    val ok: Boolean = false,
    val gateway: GatewayConfig? = null,
    val selection: GatewaySelection? = null,
    val applies: String? = null,
    val route: GatewayRoute? = null,
    val asr: GatewayAsr? = null,
    val error: Failure? = null,
) {
    @Serializable
    internal data class Failure(val code: String, val message: String = "")
}

@Serializable
private data class ProviderRef(val provider: String)

/**
 * The gateway endpoints, over the same HTTP client and the same build-time
 * endpoint as [IdentityApi] and [RemoteChatApi].
 *
 * Every operation is a POST presenting the same Bearer credential that names
 * the account. The backend decides whether that account is the administrator;
 * nothing here claims a role, and an ordinary agent's request is refused by the
 * backend as well as by the navigation gate.
 *
 * Failures reuse [IdentityRefused] and [IdentityUnreachable]: the envelope is
 * the same `{ok:false,error:{code,message}}` document, and the gateway's own
 * codes — `gateway/route-in-use`, `gateway/route-selected`,
 * `gateway/invalid-selection` and the rest — reach the page unchanged so it can
 * say what actually happened instead of a generic failure.
 *
 * A `200`-shaped body does not make a failed status a success, and a body that
 * says `ok:false` is a refusal even when the status says `200`: both must agree
 * before an answer is adopted.
 */
class GatewayApi(
    private val baseUrl: String,
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : GatewayBackend {

    override suspend fun read(token: String): GatewayConfig =
        call("/gateway/read", token = token).gateway
            ?: throw IdentityUnreachable(
                IllegalStateException("the backend accepted the read without a gateway configuration"),
            )

    override suspend fun select(token: String, selection: GatewaySelection): GatewaySelectionSaved {
        val envelope = call(
            "/gateway/selection",
            document = json.encodeToString(GatewaySelection.serializer(), selection),
            token = token,
        )
        return GatewaySelectionSaved(
            selection = envelope.selection ?: throw IdentityUnreachable(
                IllegalStateException("the backend accepted the selection without echoing it"),
            ),
            applies = envelope.applies ?: throw IdentityUnreachable(
                IllegalStateException("the backend accepted the selection without saying when it applies"),
            ),
        )
    }

    override suspend fun saveRoute(token: String, route: GatewayRouteDraft): GatewayRoute =
        call(
            "/gateway/route",
            document = json.encodeToString(GatewayRouteDraft.serializer(), route),
            token = token,
        ).route ?: throw IdentityUnreachable(
            IllegalStateException("the backend accepted the route without echoing it"),
        )

    override suspend fun deleteRoute(token: String, provider: String) {
        call(
            "/gateway/route/delete",
            document = json.encodeToString(ProviderRef.serializer(), ProviderRef(provider)),
            token = token,
        )
    }

    override suspend fun saveAsr(token: String, asr: GatewayAsrDraft): GatewayAsr =
        call(
            "/gateway/asr",
            document = json.encodeToString(GatewayAsrDraft.serializer(), asr),
            token = token,
        ).asr ?: throw IdentityUnreachable(
            IllegalStateException("the backend accepted the ASR connection without echoing it"),
        )

    /**
     * One authenticated POST. The document is always JSON: unlike the identity
     * routes, every gateway operation takes a body, including `/gateway/read`,
     * which names nothing and is sent as `{}`.
     */
    private suspend fun call(
        path: String,
        document: String = "{}",
        token: String,
    ): GatewayEnvelope = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .post(document.toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $token")
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (unreachable: IOException) {
            throw IdentityUnreachable(unreachable)
        }

        response.use {
            val text = it.body.string()
            val envelope = runCatching { json.decodeFromString<GatewayEnvelope>(text) }.getOrNull()
                ?: throw IdentityUnreachable(
                    IllegalStateException("the backend answered ${it.code} with an unreadable document"),
                )
            if (!envelope.ok || !it.isSuccessful) {
                val failure = envelope.error
                throw IdentityRefused(
                    status = it.code,
                    code = failure?.code ?: "gateway/unknown",
                    message = failure?.message?.takeIf { message -> message.isNotEmpty() } ?: text,
                )
            }
            envelope
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
