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
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** One attachment as the backend holds it. `status` is 0 while proposed and 1 */
@Serializable
data class RemoteAttachmentUpload(
    val uploadId: String,
    val name: String,
    val mime: String,
    val size: Long,
    val sha256: String,
    val status: Int,
    val offset: Long,
    val expiresAt: Long,
)

/** The attachment operations this app performs. [RemoteAttachmentApi] is the HTTP implementation. */
interface RemoteAttachmentBackend {
    /** Uploads [file]'s original bytes and answers with the completed receipt. */
    suspend fun upload(
        token: String,
        sessionId: String,
        file: File,
        name: String,
        mime: String,
    ): RemoteAttachmentUpload

    /** Writes the original bytes of [attachment] into [destination]. */
    suspend fun download(
        token: String,
        sessionId: String,
        messageId: String,
        attachment: RemoteChatAttachment,
        destination: File,
    )
}

@Serializable
internal data class AttachmentEnvelope(
    val ok: Boolean = false,
    val upload: RemoteAttachmentUpload? = null,
    val error: AttachmentEnvelope.Failure? = null,
) {
    @Serializable
    internal data class Failure(val code: String, val message: String = "")
}

@Serializable
private data class ProposeDocument(
    val sessionId: String,
    val name: String,
    val mime: String,
    val size: Long,
    val sha256: String,
)

@Serializable
private data class CompleteDocument(val uploadId: String)

@Serializable
private data class DownloadDocument(val sessionId: String, val messageId: String, val index: Int)

/** The attachment wire: propose, sequential chunks, complete, download. */
class RemoteAttachmentApi(
    baseUrl: String,
    client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : RemoteAttachmentBackend {

    private val baseUrl = baseUrl.trimEnd('/')

    /** A redirect would carry the Bearer credential somewhere it was not granted. */
    private val client: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    override suspend fun upload(
        token: String,
        sessionId: String,
        file: File,
        name: String,
        mime: String,
    ): RemoteAttachmentUpload = withContext(Dispatchers.IO) {
        val size = file.length()
        require(size in 1..MAX_ORIGINAL_BYTES) {
            "an original is 1..$MAX_ORIGINAL_BYTES bytes, this one is $size"
        }
        val digest = file.sha256()

        val proposed = try {
            receipt(
                upload = call(
                    "/attachments/propose",
                    json.encodeToString(ProposeDocument(sessionId, name, mime, size, digest)),
                    token,
                ),
                size = size,
                digest = digest,
            )
        } catch (lost: IOException) {
            // A proposal is never repeated. The backend may or may not have taken
            // it, and a second proposal would be a second upload, not a retry.
            throw IdentityUnreachable(lost)
        }
        val uploadId = proposed.uploadId

        require(proposed.status == 0 && proposed.offset == 0L) { "invalid initial upload receipt" }
        var offset = 0L
        file.inputStream().buffered().use { source ->
            val buffer = ByteArray(CHUNK_BYTES.toInt())
            while (offset < size) {
                val read = source.read(buffer, 0, minOf(buffer.size.toLong(), size - offset).toInt())
                // An empty chunk is invalid on the wire, so a short read before the
                // end means the file changed underneath the proposal, not a chunk.
                require(read > 0) { "the original ended at $offset of $size bytes" }
                val bytes = buffer.copyOf(read)
                offset = chunk(token, uploadId, offset, bytes)
            }
        }

        complete(token, uploadId, size, digest)
    }

    /** Sends one chunk and returns the offset the backend acknowledged. */
    private fun chunk(token: String, uploadId: String, offset: Long, bytes: ByteArray): Long {
        repeat(2) { attempt ->
            val request = Request.Builder()
                .url("$baseUrl/attachments/chunk")
                .post(bytes.toRequestBody(OCTET_STREAM))
                .header("Authorization", "Bearer $token")
                .header("x-upload-id", uploadId)
                .header("x-upload-offset", offset.toString())
                .build()
            val acknowledged = try {
                receipt(exchange(request), id = uploadId)
            } catch (lost: IOException) {
                if (attempt == 1) throw IdentityUnreachable(lost)
                return@repeat
            }
            // The next offset the backend acknowledges is what the following chunk
            // must start at; anything else means the original is not whole here.
            val end = offset + bytes.size
            if (acknowledged.offset != end) {
                throw IdentityUnreachable(
                    IllegalStateException("upload $uploadId acknowledged ${acknowledged.offset} bytes, this chunk ends at $end"),
                )
            }
            return acknowledged.offset
        }
        error("unreachable: the chunk was neither answered nor repeated")
    }

    /** Completion is asked for once, and repeated once under the same id: the same */
    private fun complete(
        token: String,
        uploadId: String,
        size: Long,
        digest: String,
    ): RemoteAttachmentUpload {
        repeat(2) { attempt ->
            val document = json.encodeToString(CompleteDocument(uploadId))
            val completed = try {
                receipt(
                    exchange(jsonRequest("/attachments/complete", document, token)),
                    id = uploadId,
                    size = size,
                    digest = digest,
                )
            } catch (lost: IOException) {
                if (attempt == 1) throw IdentityUnreachable(lost)
                return@repeat
            }
            // The receipt is the only proof the original became durable. Below
            // that, this upload is not sendable however well shaped the answer is.
            if (completed.status != 1 || completed.offset != size) {
                throw IdentityUnreachable(
                    IllegalStateException(
                        "upload $uploadId is status ${completed.status} at ${completed.offset} of $size bytes",
                    ),
                )
            }
            return completed
        }
        error("unreachable: the completion was neither answered nor repeated")
    }

    override suspend fun download(
        token: String,
        sessionId: String,
        messageId: String,
        attachment: RemoteChatAttachment,
        destination: File,
    ) = withContext(Dispatchers.IO) {
        val expected = attachment.bytes
        require(expected in 1..MAX_ORIGINAL_BYTES) {
            "an original is 1..$MAX_ORIGINAL_BYTES bytes, this one claims $expected"
        }
        val document = json.encodeToString(DownloadDocument(sessionId, messageId, attachment.index))
        val response = try {
            client.newCall(jsonRequest("/attachments/download", document, token)).execute()
        } catch (unreachable: IOException) {
            throw IdentityUnreachable(unreachable)
        }

        response.use {
            if (!it.isSuccessful) throw refusal(it.code, it.body.string())
            // A download is only the original if it is the original's length: an
            // absent length, or one that disagrees with the message, is refused
            // before a single byte is written.
            val declared = it.header("Content-Length")?.toLongOrNull()
            if (declared != expected) {
                throw IdentityUnreachable(
                    IllegalStateException("the download declares $declared bytes, the message says $expected"),
                )
            }
            var written = 0L
            try {
                it.body.byteStream().use { source ->
                    destination.outputStream().buffered().use { out ->
                        val buffer = ByteArray(COPY_BUFFER)
                        while (true) {
                            val read = source.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            written += read
                        }
                    }
                }
                if (written != expected) {
                    throw IdentityUnreachable(
                        IllegalStateException("the download stopped at $written of $expected bytes"),
                    )
                }
            } catch (lost: IOException) {
                // A body that ends early is a lost transfer, not an answer.
                destination.delete()
                throw IdentityUnreachable(lost)
            } catch (short: IdentityUnreachable) {
                // What was written is not the original, so it is not left behind
                // to be mistaken for one.
                destination.delete()
                throw short
            }
        }
    }

    /** A receipt is believed only once it describes the upload it answers for: */
    private fun receipt(
        upload: RemoteAttachmentUpload?,
        id: String? = null,
        size: Long? = null,
        digest: String? = null,
    ): RemoteAttachmentUpload {
        val receipt = upload ?: throw IdentityUnreachable(
            IllegalStateException("the backend answered without an upload"),
        )
        if (receipt.uploadId.isBlank()) {
            throw IdentityUnreachable(IllegalStateException("the receipt has no upload id"))
        }
        if (id != null && receipt.uploadId != id) {
            throw IdentityUnreachable(
                IllegalStateException("the receipt is for upload ${receipt.uploadId}, not $id"),
            )
        }
        if (size != null && receipt.size != size) {
            throw IdentityUnreachable(
                IllegalStateException("the receipt describes ${receipt.size} bytes, not this original's $size"),
            )
        }
        if (digest != null && !receipt.sha256.equals(digest, ignoreCase = true)) {
            throw IdentityUnreachable(IllegalStateException("the receipt describes other bytes, not this original"))
        }
        // The backend is the authority on the name it stores: it strips paths and
        // control characters. A name that still carries either was not sanitized.
        if (receipt.name.any { it == '/' || it == '\\' || it.isISOControl() }) {
            throw IdentityUnreachable(IllegalStateException("the receipt's name is not a sanitized file name"))
        }
        return receipt
    }

    private fun call(path: String, document: String, token: String): RemoteAttachmentUpload =
        exchange(jsonRequest(path, document, token))

    /** One call, with its [IOException] left intact: whether a lost answer may be */
    private fun exchange(request: Request): RemoteAttachmentUpload {
        val response = client.newCall(request).execute()
        response.use {
            val text = it.body.string()
            val envelope = runCatching { json.decodeFromString<AttachmentEnvelope>(text) }.getOrNull()
                ?: throw IdentityUnreachable(
                    IllegalStateException("the backend answered ${it.code} with an unreadable document"),
                )
            // A `200`-shaped body does not make a failed status a success, for the
            // same reason SessionApi does not.
            if (!envelope.ok || !it.isSuccessful) throw refusal(it.code, text, envelope)
            // An `ok` answer without the upload it promises is not an upload.
            return envelope.upload
                ?: throw IdentityUnreachable(IllegalStateException("the backend answered without an upload"))
        }
    }

    private fun jsonRequest(path: String, document: String, token: String): Request = Request.Builder()
        .url(baseUrl + path)
        .post(document.toRequestBody(JSON_MEDIA_TYPE))
        .header("Authorization", "Bearer $token")
        .build()

    private fun refusal(status: Int, text: String, envelope: AttachmentEnvelope? = null): IdentityRefused {
        val failure = (envelope ?: runCatching { json.decodeFromString<AttachmentEnvelope>(text) }.getOrNull())?.error
        return IdentityRefused(
            status = status,
            code = failure?.code ?: "attachments/unknown",
            message = failure?.message?.takeIf { it.isNotEmpty() } ?: text,
        )
    }

    private companion object {
        val JSON_MEDIA_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()
        val OCTET_STREAM: MediaType = "application/octet-stream".toMediaType()

        /** The wire's chunk ceiling, and the original ceiling this app sends under. */
        const val CHUNK_BYTES = 256L * 1024
        const val MAX_ORIGINAL_BYTES = 32L * 1024 * 1024
        const val COPY_BUFFER = 64 * 1024
    }
}

/** The digest of the original bytes, lowercased hex, as the wire spells it. */
private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { source ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
