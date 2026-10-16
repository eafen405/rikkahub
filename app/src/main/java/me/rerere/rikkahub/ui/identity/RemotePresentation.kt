package me.rerere.rikkahub.ui.identity

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transformLatest
import me.rerere.rikkahub.data.remote.RemoteMessageFormat
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
