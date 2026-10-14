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

/** One identity on this backend, named by the short number it registered as. */
@Serializable
data class IdentityUser(
    val shortId: Int,
    val name: String,
    val isAdmin: Boolean = false,
)

/** A live credential together with the identity it names. */
data class SignedIn(
    val token: String,
    val user: IdentityUser,
)

/**
 * The backend refused the request on purpose, and said why.
 *
 * The code is the backend's own (`identity/invite-used`, `identity/name-taken`,
 * `identity/unauthorized`, …) so callers can react to the actual reason instead
 * of matching on prose.
 */
class IdentityRefused(
    val status: Int,
    val code: String,
    message: String,
) : Exception(message)

/** The backend could not be reached, or answered something unreadable. */
class IdentityUnreachable(cause: Throwable) :
    Exception("the identity backend could not be reached", cause)

/** The identity operations this app performs. [IdentityApi] is the HTTP implementation. */
interface IdentityBackend {
    suspend fun signIn(name: String, password: String): SignedIn

    suspend fun register(inviteCode: String, name: String, password: String): SignedIn

    suspend fun createInvite(token: String): String

    suspend fun session(token: String): IdentityUser

    suspend fun signOut(token: String)
}

@Serializable
internal data class IdentityEnvelope(
    val ok: Boolean = false,
    val token: String? = null,
    val user: IdentityUser? = null,
    val invite: Invite? = null,
    val error: Failure? = null,
) {
    @Serializable
    internal data class Invite(val code: String)

    @Serializable
    internal data class Failure(val code: String, val message: String = "")
}

@Serializable
private data class SignInDocument(val name: String, val password: String)

@Serializable
private data class RegisterDocument(val inviteCode: String, val name: String, val password: String)

/**
 * The identity endpoints, over the app's existing HTTP client.
 *
 * These routes deliberately live outside dsh's shared `/api` prefix: that
 * prefix is admitted as a whole before any handler runs, so a sign-in endpoint
 * cannot exist beneath it. Sign-in is the one request that must work with no
 * credential at all.
 *
 * Note which requests carry a document. `/identity/invite`, `/identity/session`
 * and `/identity/sign-out` act purely on the presented credential, so they send
 * no body and declare no media type — the backend answers `415` to a media type
 * that was never sent, and that is a real bug this client must not provoke.
 */
class IdentityApi(
    private val baseUrl: String,
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : IdentityBackend {

    override suspend fun signIn(name: String, password: String): SignedIn =
        call("/identity/sign-in", document = json.encodeToString(SignInDocument(name, password)))
            .credential()

    override suspend fun register(inviteCode: String, name: String, password: String): SignedIn =
        call(
            "/identity/register",
            document = json.encodeToString(RegisterDocument(inviteCode, name, password)),
        ).credential()

    override suspend fun createInvite(token: String): String {
        val envelope = call("/identity/invite", token = token)
        return envelope.invite?.code ?: throw IdentityUnreachable(
            IllegalStateException("the backend accepted the invite request without issuing a code"),
        )
    }

    override suspend fun session(token: String): IdentityUser {
        val envelope = call("/identity/session", token = token)
        return envelope.user ?: throw IdentityUnreachable(
            IllegalStateException("the backend accepted the credential without naming an identity"),
        )
    }

    override suspend fun signOut(token: String) {
        call("/identity/sign-out", token = token)
    }

    private suspend fun call(
        path: String,
        document: String? = null,
        token: String? = null,
    ): IdentityEnvelope = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .apply {
                // An empty body with no media type is what a bodyless POST looks
                // like on the wire; a null media type keeps the header absent.
                post((document ?: "").toRequestBody(document?.let { JSON_MEDIA_TYPE }))
                if (token != null) header("Authorization", "Bearer $token")
            }
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (unreachable: IOException) {
            throw IdentityUnreachable(unreachable)
        }

        response.use {
            val text = it.body.string()
            val envelope = runCatching { json.decodeFromString<IdentityEnvelope>(text) }.getOrNull()
                ?: throw IdentityUnreachable(
                    IllegalStateException("the backend answered ${it.code} with an unreadable document"),
                )
            if (!envelope.ok) {
                val failure = envelope.error
                throw IdentityRefused(
                    status = it.code,
                    code = failure?.code ?: "identity/unknown",
                    message = failure?.message?.takeIf { message -> message.isNotEmpty() } ?: text,
                )
            }
            envelope
        }
    }

    private fun IdentityEnvelope.credential(): SignedIn {
        val token = token ?: throw IdentityUnreachable(
            IllegalStateException("the backend accepted the request without issuing a credential"),
        )
        val user = user ?: throw IdentityUnreachable(
            IllegalStateException("the backend accepted the request without naming the identity"),
        )
        return SignedIn(token, user)
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
