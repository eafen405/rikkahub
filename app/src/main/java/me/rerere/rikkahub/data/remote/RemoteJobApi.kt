package me.rerere.rikkahub.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** What is kept of a task's output. [earliest] is the oldest retained byte, [total] the whole length. */
@Serializable
data class RemoteTaskOutputWindow(
    val earliest: Long,
    val total: Long,
)

/** How the task's answer is anchored: 0 none, 1 pending, 2 running, 3 completed, 4 canceled, 5 failed, 6 unknown. */
@Serializable
data class RemoteTaskReport(
    val status: Int = 0,
    val messageId: String? = null,
    val turn: Int? = null,
)

/**
 * One background task of a session, as the backend holds it.
 * [status]: 0 unknown, 1 running, 2 stopping, 3 completed, 4 killed, 5 failed.
 * [availability]: 0 unavailable, 1 available.
 */
@Serializable
data class RemoteTask(
    val taskId: String,
    val kind: String,
    val label: String,
    val status: Int,
    val availability: Int,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val detail: String? = null,
    val progress: String? = null,
    val output: RemoteTaskOutputWindow,
    val report: RemoteTaskReport,
)

/** One output chunk at the absolute UTF-8 byte offset [at]. [gapBefore] marks lost output. */
@Serializable
data class RemoteJobChunk(
    val at: Long,
    val text: String,
    val channel: String? = null,
    val gapBefore: Boolean = false,
)

/**
 * The output available from [RemoteJobBackend.output]. [next] is the cursor the following read
 * starts at, [availability]: 0 unavailable, 1 available, and [lossy] says whether anything was
 * dropped behind [earliest].
 */
@Serializable
data class RemoteJobOutput(
    val taskId: String,
    val availability: Int,
    val chunks: List<RemoteJobChunk> = emptyList(),
    val next: Long,
    val earliest: Long,
    val total: Long,
    val lossy: Boolean,
    val status: Int,
)

/** The job operations this app performs. [RemoteJobApi] is the HTTP implementation. */
interface RemoteJobBackend {
    /** Reads the task's output from [from]. A task with nothing to read still answers. */
    suspend fun output(token: String, sessionId: String, taskId: String, from: Long): RemoteJobOutput

    /** Asks for the task to stop. Outcome: 0 unavailable, 1 requested, 2 already-finished. */
    suspend fun stop(token: String, sessionId: String, taskId: String): Int
}

@Serializable
internal data class JobEnvelope(
    val ok: Boolean = false,
    val output: RemoteJobOutput? = null,
    val outcome: Int? = null,
    val error: JobEnvelope.Failure? = null,
) {
    @Serializable
    internal data class Failure(val code: String, val message: String = "")
}

@Serializable
private data class OutputDocument(val sessionId: String, val taskId: String, val from: Long)

@Serializable
private data class TaskRef(val sessionId: String, val taskId: String)

/** The job wire: read a task's output, ask for it to stop. */
class RemoteJobApi(
    baseUrl: String,
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : RemoteJobBackend {

    private val baseUrl = baseUrl.trimEnd('/')

    override suspend fun output(
        token: String,
        sessionId: String,
        taskId: String,
        from: Long,
    ): RemoteJobOutput =
        exchange("/chat/jobs/output", json.encodeToString(OutputDocument(sessionId, taskId, from)), token).output
            ?: throw IdentityUnreachable(
                IllegalStateException("the backend accepted the output read without an output"),
            )

    override suspend fun stop(token: String, sessionId: String, taskId: String): Int =
        exchange("/chat/jobs/stop", json.encodeToString(TaskRef(sessionId, taskId)), token).outcome
            ?: throw IdentityUnreachable(
                IllegalStateException("the backend accepted the stop without an outcome"),
            )

    private suspend fun exchange(path: String, document: String, token: String): JobEnvelope =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(baseUrl + path)
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
                val envelope = runCatching { json.decodeFromString<JobEnvelope>(text) }.getOrNull()
                    ?: throw IdentityUnreachable(
                        IllegalStateException("the backend answered ${it.code} with an unreadable document"),
                    )
                // A `200`-shaped body does not make a failed status a success:
                // the body's claim and the transport's verdict must agree before
                // an answer is adopted, exactly as RemoteChatApi requires.
                if (!envelope.ok || !it.isSuccessful) throw refusal(it.code, text, envelope)
                envelope
            }
        }

    private fun refusal(status: Int, text: String, envelope: JobEnvelope? = null): IdentityRefused {
        val failure = (envelope ?: runCatching { json.decodeFromString<JobEnvelope>(text) }.getOrNull())?.error
        return IdentityRefused(
            status = status,
            // The backend's own code, so a caller can tell "not your session"
            // from "not your task" without matching on prose.
            code = failure?.code ?: "chat/jobs/unknown",
            message = failure?.message?.takeIf { it.isNotEmpty() } ?: text,
        )
    }

    private companion object {
        val JSON_MEDIA_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()
    }
}
