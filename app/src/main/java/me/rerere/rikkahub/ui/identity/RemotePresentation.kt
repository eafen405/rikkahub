package me.rerere.rikkahub.ui.identity

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transformLatest
import me.rerere.rikkahub.data.remote.RemoteMessageFormat
import me.rerere.rikkahub.data.remote.RemoteChatMessage
import me.rerere.ui.markdown.MarkdownDocument

/** Whole raw snapshots only. Parsing never edits or accumulates source text. */
internal data class RemoteBodyInput(val text: String, val format: RemoteMessageFormat)
internal data class RemoteBodyPresentation(val text: String, val document: MarkdownDocument? = null)

/** Keep a readable raw body until its complete document is ready; stale work cannot publish. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun remoteBodyPresentations(
    input: Flow<RemoteBodyInput>,
    parse: suspend (String) -> MarkdownDocument,
): Flow<RemoteBodyPresentation> = input.conflate().transformLatest { body ->
    emit(RemoteBodyPresentation(body.text))
    if (body.format == RemoteMessageFormat.MARKDOWN) {
        emit(RemoteBodyPresentation(body.text, parse(body.text)))
    }
}

internal data class RemoteScrollTarget(val index: Int, val offset: Int)

/** Device viewport state, never a second source of message/activity facts. */
internal data class RemoteReadingPosition(
    val followTail: Boolean = true,
    val userScrolling: Boolean = false,
    val key: String? = null,
    val index: Int = 0,
    val offset: Int = 0,
) {
    fun target(keys: List<String>): RemoteScrollTarget? {
        if (keys.isEmpty() || userScrolling) return null
        if (followTail) return RemoteScrollTarget(keys.lastIndex, Int.MAX_VALUE)
        val anchor = keys.indexOf(key).takeIf { it >= 0 } ?: index.coerceAtMost(keys.lastIndex)
        return RemoteScrollTarget(anchor, offset)
    }
}

/** A visual row; canonical messages remain in the shared session projection. */
internal data class RemoteTranscriptRow(
    val key: String,
    val text: String,
    val message: RemoteChatMessage? = null,
    val deferFormatting: Boolean = false,
) {
    fun documentToShow(document: MarkdownDocument?): MarkdownDocument? =
        document?.takeUnless { deferFormatting }
}

/**
 * Keep a reader's streaming row intact when its formal message arrives. Markdown has no
 * source-to-layout mapping, so only that already-visible raw row waits to change layout.
 * Leaving the row or returning to the tail releases it permanently. History never waits.
 */
internal class RemoteTranscriptPresentation {
    private val rowKeys = mutableMapOf<String, String>()
    private val deferred = mutableSetOf<String>()
    private var observedLiveKey: String? = null

    fun update(
        messages: List<RemoteChatMessage>,
        text: String?,
        key: String?,
        visibleKeys: Set<String>,
        followTail: Boolean,
        settled: Pair<String, Long>? = null,
    ): List<RemoteTranscriptRow> {
        // Only a live row this view actually observed can be carried into its formal row.
        settled?.takeIf { it.first == observedLiveKey }?.let { (liveKey, seq) ->
            messages.singleOrNull { it.seq == seq && it.role == 2 }?.let { final ->
                if (final.messageId !in rowKeys) {
                    rowKeys[final.messageId] = liveKey
                    if (!followTail && liveKey in visibleKeys) deferred += liveKey
                }
                observedLiveKey = null
            }
        }
        if (key != null && text != null && key !in rowKeys.values) observedLiveKey = key
        deferred.removeAll { followTail || it !in visibleKeys }
        val messageIds = messages.mapTo(mutableSetOf()) { it.messageId }
        rowKeys.keys.retainAll(messageIds)
        val rows = messages.map { message ->
            val rowKey = rowKeys[message.messageId] ?: message.messageId
            RemoteTranscriptRow(rowKey, message.text, message, rowKey in deferred)
        }
        return if (key != null && text != null && key !in rowKeys.values) {
            rows + RemoteTranscriptRow(key, text)
        } else rows
    }
}
