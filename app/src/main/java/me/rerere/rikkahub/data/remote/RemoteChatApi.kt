package me.rerere.rikkahub.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/** One attachment of a projected message, as the backend stores it. Type: 1 file, 2 image. */
@Serializable
data class RemoteChatAttachment(
    val index: Int,
    val type: Int,
    val name: String,
    val mime: String,
    val bytes: Long,
)

/** Wire role: 1 user, 2 Fairy. Status: 1 pending, 2 complete, 3 interrupted, 4 failed. */
@Serializable
data class RemoteChatMessage(
    val messageId: String,
    val seq: Long,
    val requestId: String? = null,
    val role: Int,
    val status: Int,
    val text: String,
    val attachments: List<RemoteChatAttachment> = emptyList(),
)

/** Cumulative raw text; attempt identity is scoped to the projection generation. */
@Serializable
data class RemoteChatAttempt(
    val attemptId: String,
    val revision: Long,
    val text: String,
)

/** A whole backend snapshot. durableThroughSeq is the writer barrier, not a client consumption cursor. */
@Serializable
data class RemoteChatProjection(
    val sessionId: String,
    val generation: String = "",
    val version: Long = 0L,
    val cursor: Long = -1L,
    val durableThroughSeq: Long = -1L,
    val messages: List<RemoteChatMessage> = emptyList(),
    val attempt: RemoteChatAttempt? = null,
    val replyStatus: Int = 0,
    val error: String? = null,
    val throughSeq: Long = cursor,
    val fromSeq: Long = 0,
    val events: List<JsonObject> = emptyList(),
    val hasMore: Boolean = false,
    val beforeSeq: Long? = null,
)

@Serializable
data class ChatReceipt(
    val sessionId: String,
    val requestId: String,
    val messageId: String,
    val acceptedSeq: Long,
)

@Serializable
data class ChatHistoryPage(
    val sessionId: String,
    val throughSeq: Long,
    val fromSeq: Long,
    val beforeSeq: Long? = null,
    val hasMore: Boolean = false,
    val events: List<JsonObject> = emptyList(),
    val messages: List<RemoteChatMessage> = emptyList(),
)

interface RemoteChatBackend {
    suspend fun read(token: String, sessionId: String): RemoteChatProjection

    /** [uploadIds] are completed uploads in the order the message shows them. */
    suspend fun send(
        token: String,
        sessionId: String,
        requestId: String,
        text: String,
        uploadIds: List<String> = emptyList(),
    ): ChatReceipt

    suspend fun receipt(token: String, sessionId: String, requestId: String): ChatReceipt?

    suspend fun stop(token: String, sessionId: String)

    suspend fun page(token: String, sessionId: String, throughSeq: Long, beforeSeq: Long): ChatHistoryPage

    suspend fun checkpoint(token: String, sessionId: String): Long

    fun observe(token: String, sessionId: String): Flow<RemoteChatProjection>
}

@Serializable
internal data class ChatEnvelope(
    val ok: Boolean = false,
    val receipt: ChatReceipt? = null,
    val projection: RemoteChatProjection? = null,
    val page: ChatHistoryPage? = null,
    val durableThroughSeq: Long? = null,
    val error: Failure? = null,
) {
    @Serializable
    internal data class Failure(val code: String, val message: String = "")
}

@Serializable
private data class SessionRef(val sessionId: String)

/** The same requestId carries this exact text and these exact ids, forever. */
@Serializable
private data class SendDocument(
    val sessionId: String,
    val requestId: String,
    val text: String,
    val uploadIds: List<String> = emptyList(),
)

@Serializable
private data class PageDocument(val sessionId: String, val throughSeq: Long, val beforeSeq: Long)

@Serializable
private data class ReceiptDocument(val sessionId: String, val requestId: String)

class RemoteChatApi(
    private val baseUrl: String,
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : RemoteChatBackend {

    override suspend fun read(token: String, sessionId: String): RemoteChatProjection =
        call("/chat/read", json.encodeToString(SessionRef(sessionId)), token)
            .projection
            ?: throw IdentityUnreachable(
                IllegalStateException("the backend accepted the read without a projection"),
            )

    override suspend fun send(
        token: String,
        sessionId: String,
        requestId: String,
        text: String,
        uploadIds: List<String>,
    ): ChatReceipt =
        call(
            "/chat/send",
            json.encodeToString(SendDocument(sessionId, requestId, text, uploadIds)),
            token,
        ).receipt ?: throw IdentityUnreachable(
            // Accepted but unacknowledged is exactly the state the wire says is
            // unknowable from here; the receipt endpoint resolves it.
            IllegalStateException("the backend accepted the send without a receipt"),
        )

    override suspend fun receipt(token: String, sessionId: String, requestId: String): ChatReceipt? =
        call(
            "/chat/receipt",
            json.encodeToString(ReceiptDocument(sessionId, requestId)),
            token,
        ).receipt

    override suspend fun stop(token: String, sessionId: String) {
        call("/chat/stop", json.encodeToString(SessionRef(sessionId)), token)
    }

        override suspend fun page(token: String, sessionId: String, throughSeq: Long, beforeSeq: Long): ChatHistoryPage =
        call("/chat/page", json.encodeToString(PageDocument(sessionId, throughSeq, beforeSeq)), token).page
            ?: throw IdentityUnreachable(IllegalStateException("the backend omitted the history page"))

    override suspend fun checkpoint(token: String, sessionId: String): Long =
        call("/chat/checkpoint", json.encodeToString(SessionRef(sessionId)), token).durableThroughSeq
            ?: throw IdentityUnreachable(IllegalStateException("the backend omitted the durable checkpoint"))

    // Closing this lease never calls stop. Each SSE event replaces the previous snapshot.
    override fun observe(token: String, sessionId: String): Flow<RemoteChatProjection> = callbackFlow {
        val request = request(
            path = "/chat/observe",
            document = json.encodeToString(SessionRef(sessionId)),
            token = token,
        )
        val call = streamingClient.newCall(request)

        val reading = launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (response.isSuccessful) {
                        for (snapshot in response.body.snapshots()) send(snapshot)
                    } else {
                        // Refused before the stream began: report it as itself,
                        // rather than as a stream that carried nothing.
                        close(refusal(response.code, response.body.string()))
                        return@launch
                    }
                }
                close()
            } catch (unreachable: IOException) {
                // Cancelling the call is how this flow ends, and an IOException
                // is what that looks like from in here. Only a real failure
                // gets reported; a cancellation stays a cancellation.
                if (isActive) close(IdentityUnreachable(unreachable))
            }
        }

        awaitClose {
            reading.cancel()
            call.cancel()
        }
    }

    private val streamingClient: OkHttpClient by lazy {
        client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    }

    private suspend fun call(path: String, document: String, token: String): ChatEnvelope =
        withContext(Dispatchers.IO) {
            val response = try {
                client.newCall(request(path, document, token)).execute()
            } catch (unreachable: IOException) {
                throw IdentityUnreachable(unreachable)
            }

            response.use {
                val text = it.body.string()
                val envelope = runCatching { json.decodeFromString<ChatEnvelope>(text) }.getOrNull()
                    ?: throw IdentityUnreachable(
                        IllegalStateException("the backend answered ${it.code} with an unreadable document"),
                    )
                // A `200`-shaped body does not make a failed status a success:
                // the body's claim and the transport's verdict must agree before
                // an answer is adopted, exactly as SessionApi requires.
                if (!envelope.ok || !it.isSuccessful) throw refusal(it.code, text, envelope)
                envelope
            }
        }

    private fun request(path: String, document: String, token: String): Request = Request.Builder()
        .url(baseUrl.trimEnd('/') + path)
        .post(document.toRequestBody(JSON_MEDIA_TYPE))
        .header("Authorization", "Bearer $token")
        .build()

    private fun refusal(
        status: Int,
        text: String,
        envelope: ChatEnvelope? = null,
    ): IdentityRefused {
        val failure = (envelope ?: runCatching { json.decodeFromString<ChatEnvelope>(text) }.getOrNull())?.error
        return IdentityRefused(
            status = status,
            // The backend's own code, so a caller can tell "not your session"
            // from "already running" without matching on prose.
            code = failure?.code ?: "chat/unknown",
            message = failure?.message?.takeIf { it.isNotEmpty() } ?: text,
        )
    }

        private fun okhttp3.ResponseBody.snapshots(): Sequence<RemoteChatProjection> = sequence {
        val payload = StringBuilder()
        var event: String? = null

        BufferedReader(InputStreamReader(byteStream(), Charsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                when {
                    line.isEmpty() -> {
                        if (event == PROJECTION && payload.isNotEmpty()) {
                            yield(json.decodeFromString<RemoteChatProjection>(payload.toString()))
                        }
                        event = null
                        payload.setLength(0)
                    }

                    line.startsWith(":") -> Unit

                    line.startsWith(DATA) -> {
                        if (payload.isNotEmpty()) payload.append('\n')
                        payload.append(line.substring(DATA.length).removePrefix(" "))
                    }

                    line.startsWith(EVENT) -> event = line.substring(EVENT.length).trim()
                    // `id:` and `retry:` are accepted and ignored: there is no
                    // replay across a reconnect to resume into.
                    else -> Unit
                }
            }
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()
        const val PROJECTION = "projection"
        const val DATA = "data:"
        const val EVENT = "event:"
    }
}
