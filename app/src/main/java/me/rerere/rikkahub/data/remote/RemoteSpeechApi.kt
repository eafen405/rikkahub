package me.rerere.rikkahub.data.remote

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What the backend offers for speech, and the ceilings it enforces on a take. */
@Serializable
data class RemoteSpeechAvailability(
    val available: Boolean = false,
    val maxDurationMs: Int = 0,
    val maxAudioBytes: Int = 0,
)

/**
 * The speech operations this app performs. [RemoteSpeechApi] is the HTTP
 * implementation. The cloud key stays on the backend: nothing here carries it,
 * and nothing here sends a chat — a transcript is handed back for review.
 */
interface RemoteSpeechBackend {

    /** Answers whether speech is configured there, and within what ceilings. */
    suspend fun availability(token: String): RemoteSpeechAvailability

    /** Transcribes [wav] as recorded; the caller decides whether to send it. */
    suspend fun transcribe(token: String, wav: ByteArray): String
}

@Serializable
private data class SpeechEnvelope(
    val ok: Boolean = false,
    val available: Boolean = false,
    val maxDurationMs: Int = 0,
    val maxAudioBytes: Int = 0,
    val text: String? = null,
    val error: SpeechEnvelope.Failure? = null,
) {
    @Serializable
    data class Failure(val code: String, val message: String = "")
}

/**
 * The speech wire: an authenticated availability answer, then the take itself.
 *
 * Calls are asynchronous and cancellation cancels the HTTP exchange, so a take
 * the user abandoned leaves no request running behind it.
 */
class RemoteSpeechApi(
    baseUrl: String,
    client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : RemoteSpeechBackend {

    private val baseUrl = baseUrl.trimEnd('/')

    /** A redirect would carry the Bearer credential somewhere it was not granted. */
    private val client: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    override suspend fun availability(token: String): RemoteSpeechAvailability {
        val request = Request.Builder()
            .url("$baseUrl/speech")
            .header("Authorization", "Bearer $token")
            .build()
        val envelope = exchange(request)
        return RemoteSpeechAvailability(
            available = envelope.available,
            maxDurationMs = envelope.maxDurationMs,
            maxAudioBytes = envelope.maxAudioBytes,
        )
    }

    override suspend fun transcribe(token: String, wav: ByteArray): String {
        require(wav.isNotEmpty()) { "an empty recording carries no speech" }
        val request = Request.Builder()
            .url("$baseUrl/speech/transcriptions")
            .post(wav.toRequestBody(WAV_MEDIA_TYPE))
            .header("Authorization", "Bearer $token")
            .build()
        // The transcript is what the user reviews before it is sent, so a blank
        // answer is a failed recognition rather than an empty draft to overwrite
        // something already typed.
        val text = exchange(request).text?.takeIf { it.isNotBlank() }
            ?: throw IdentityRefused(422, "speech/no-match", "the backend answered without recognized text")
        return text
    }

    /**
     * One authenticated answer, decoded off the calling thread. A lost
     * connection is unreachable; a refusal the backend spoke is its own code.
     */
    private suspend fun exchange(request: Request): SpeechEnvelope =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IdentityUnreachable(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    val outcome = runCatching { decode(response) }
                    if (continuation.isActive) {
                        outcome.fold(
                            onSuccess = { continuation.resume(it) },
                            onFailure = { continuation.resumeWithException(it) },
                        )
                    }
                }
            })
        }

    private fun decode(response: Response): SpeechEnvelope = response.use {
        val text = it.body.string()
        val envelope = runCatching { json.decodeFromString<SpeechEnvelope>(text) }.getOrNull()
            ?: throw IdentityUnreachable(
                IllegalStateException("the backend answered ${it.code} with an unreadable document"),
            )
        // A `200`-shaped body does not make a failed status a success, for the
        // same reason RemoteAttachmentApi does not.
        if (!it.isSuccessful || !envelope.ok) {
            throw IdentityRefused(
                status = it.code,
                code = envelope.error?.code ?: "speech/unknown",
                message = envelope.error?.message?.takeIf { message -> message.isNotEmpty() } ?: text,
            )
        }
        return envelope
    }

    private companion object {
        val WAV_MEDIA_TYPE: MediaType = "audio/wav".toMediaType()
    }
}
