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
 * One of this account's remote sessions, as the backend describes it.
 *
 * [id] is opaque: it is the backend's business how a session is named, and this
 * client must never read meaning out of it — in particular [owner] is not parsed
 * out of the id to decide who may read it. Authorization is the backend's job;
 * this is a projection of what it already decided.
 *
 * [createdAt] is the backend's own creation instant, passed through untouched:
 * the product exposes `createdAt: header.createdAt` from a header whose value is
 * `Date.now()` milliseconds (backend/sessions/src/service.mjs, `expose`), so on
 * the wire it is a number. It is decoded as the number it is rather than
 * accepting a second shape the server never sends.
 */
@Serializable
data class OwnedSession(
    val id: String,
    val owner: String = "",
    val primary: Boolean = false,
    val createdAt: Long = 0L,
)

/** The account's sessions together with the one that is permanently its main. */
data class OwnedSessions(
    val sessions: List<OwnedSession>,
    val primarySessionId: String?,
)

/** The session operations this app performs. [SessionApi] is the HTTP implementation. */
interface SessionBackend {
    suspend fun list(token: String): OwnedSessions

    suspend fun create(token: String): OwnedSession

    suspend fun select(token: String, sessionId: String): OwnedSession

    suspend fun primary(token: String): OwnedSession

    suspend fun read(token: String, sessionId: String): OwnedSession
}

@Serializable
internal data class SessionEnvelope(
    val ok: Boolean = false,
    val sessions: List<OwnedSession>? = null,
    val session: OwnedSession? = null,
    val primarySessionId: String? = null,
    val error: Failure? = null,
) {
    @Serializable
    internal data class Failure(val code: String, val message: String = "")
}

@Serializable
private data class SessionDocument(val sessionId: String)

/**
 * The remote session endpoints, over the same HTTP client and the same build-time
 * endpoint as [IdentityApi].
 *
 * Every operation is a POST presenting the same Bearer credential that names the
 * account, and no operation takes a caller-chosen user: the backend reads the
 * owner off the credential, so this client cannot ask for somebody else's
 * sessions and cannot ask "as" anyone.
 *
 * Which requests carry a document is not cosmetic. `/sessions/list`,
 * `/sessions/create` and `/sessions/primary` act purely on the presented
 * credential, so they send no body and declare no media type; only
 * `/sessions/select` and `/sessions/read` name a session, and those send
 * `{"sessionId": …}`. Sending a media type the backend was not sent is what
 * earns a `415`, so the bodyless shape is preserved deliberately.
 *
 * Failures reuse [IdentityRefused] and [IdentityUnreachable] rather than
 * introducing a parallel error vocabulary: the envelope is the same
 * `{ok:false,error:{code,message}}` document, and one way to say "refused" is
 * one thing this app has to get right.
 */
class SessionApi(
    private val baseUrl: String,
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SessionBackend {

    override suspend fun list(token: String): OwnedSessions {
        val envelope = call("/sessions/list", token = token)
        return OwnedSessions(
            sessions = envelope.sessions.orEmpty(),
            primarySessionId = envelope.primarySessionId,
        )
    }

    override suspend fun create(token: String): OwnedSession =
        call("/sessions/create", token = token).named()

    override suspend fun select(token: String, sessionId: String): OwnedSession =
        call("/sessions/select", document = json.encodeToString(SessionDocument(sessionId)), token = token)
            .named()

    override suspend fun primary(token: String): OwnedSession =
        call("/sessions/primary", token = token).named()

    override suspend fun read(token: String, sessionId: String): OwnedSession =
        call("/sessions/read", document = json.encodeToString(SessionDocument(sessionId)), token = token)
            .named()

    private suspend fun call(
        path: String,
        document: String? = null,
        token: String,
    ): SessionEnvelope = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .apply {
                // An empty body with no media type is what a bodyless POST looks
                // like on the wire; a null media type keeps the header absent.
                post((document ?: "").toRequestBody(document?.let { JSON_MEDIA_TYPE }))
                header("Authorization", "Bearer $token")
            }
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (unreachable: IOException) {
            throw IdentityUnreachable(unreachable)
        }

        response.use {
            val text = it.body.string()
            val envelope = runCatching { json.decodeFromString<SessionEnvelope>(text) }.getOrNull()
                ?: throw IdentityUnreachable(
                    IllegalStateException("the backend answered ${it.code} with an unreadable document"),
                )
            if (!envelope.ok || !it.isSuccessful) {
                // A `200`-shaped body does not make a failed status a success.
                // Both have to agree before the answer is adopted, exactly as
                // IdentityApi requires, and a genuine refusal keeps the status
                // and the backend's own code so the failure stays meaningful.
                val failure = envelope.error
                throw IdentityRefused(
                    status = it.code,
                    code = failure?.code ?: "sessions/unknown",
                    message = failure?.message?.takeIf { message -> message.isNotEmpty() } ?: text,
                )
            }
            envelope
        }
    }

    private fun SessionEnvelope.named(): OwnedSession = session ?: throw IdentityUnreachable(
        IllegalStateException("the backend accepted the request without naming a session"),
    )

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
