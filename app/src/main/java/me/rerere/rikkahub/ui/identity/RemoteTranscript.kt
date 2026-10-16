package me.rerere.rikkahub.ui.identity

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Download01
import me.rerere.rikkahub.data.remote.RemoteChatAttachment
import me.rerere.rikkahub.data.remote.RemoteChatMessage
import me.rerere.rikkahub.data.remote.RemoteMessageFormat
import me.rerere.rikkahub.data.remote.remoteAttemptFormat
import me.rerere.rikkahub.data.remote.remoteMessageFormat
import me.rerere.rikkahub.ui.components.message.ChatMessageFileChip
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.fairy.FairyDialogPaddingH
import me.rerere.rikkahub.ui.fairy.FairyDialogPaddingV
import me.rerere.rikkahub.ui.fairy.LocalFairyTokens
import me.rerere.rikkahub.ui.fairy.fairyDialogPanel
import me.rerere.rikkahub.ui.theme.LocalDarkMode

@Composable
internal fun RemoteTranscript(
    messages: List<RemoteChatMessage>,
    attemptText: String?,
    attemptKey: Pair<String, String?>?,
    downloadEnabled: Boolean,
    onDownload: (RemoteChatMessage, RemoteChatAttachment) -> Unit,
    onFinalPresented: () -> Unit = {},
) {
    if (messages.isEmpty() && attemptText == null) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Text(
                text = "此会话还没有消息。",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    var detail by remember { mutableStateOf<RemoteChatMessage?>(null) }
    detail?.let { message ->
        AlertDialog(
            onDismissRequest = { detail = null },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("关闭") } },
            text = {
                Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())
                    .fairyDialogPanel(active = false)
                    .padding(horizontal = FairyDialogPaddingH, vertical = FairyDialogPaddingV)) {
                    MessageBody(remoteMessageFormat(message), message.text, foreground = LocalFairyTokens.current.textPrimary)
                }
            },
        )
    }
    val listState = rememberLazyListState()
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    val keys = messages.map { it.messageId } +
        if (attemptText != null && attemptKey != null) listOf("attempt:${attemptKey.first}:${attemptKey.second}") else emptyList()
    val currentKeys by rememberUpdatedState(keys)
    var reading by remember { mutableStateOf(RemoteReadingPosition()) }
    LaunchedEffect(dragging) {
        if (dragging) reading = reading.copy(followTail = false, userScrolling = true)
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, listState.isScrollInProgress)
        }.collect { (index, offset, scrolling) ->
            reading = reading.copy(
                key = currentKeys.getOrNull(index), index = index, offset = offset,
                userScrolling = scrolling,
                followTail = if (!scrolling && !listState.canScrollForward) true else reading.followTail,
            )
        }
    }
    fun preservePosition() {
        reading.target(keys)?.let { listState.requestScrollToItem(it.index, it.offset) }
    }
    // StateFlow already conflates whole snapshots. Preserve the viewport rather than replaying deltas.
    LaunchedEffect(messages, attemptText, attemptKey) { preservePosition() }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().height(480.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(messages, key = { it.messageId }) { message ->
            androidx.compose.runtime.key(message.messageId) {
                // The role, not the status, decides how a line is drawn: Fairy messages keep
                // using the rich renderer whether they completed, were interrupted or failed.
                Column(
                    modifier = if (message.role == 2) Modifier.fillMaxWidth()
                        .fairyDialogPanel(active = false)
                        .padding(horizontal = FairyDialogPaddingH, vertical = FairyDialogPaddingV)
                    else Modifier,
                ) {
                    Text(
                        text = if (message.role == 1) "你" else "Fairy",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // An attachment-only message has no body to render, and is still a message.
                    if (message.text.isNotBlank()) {
                        MessageBody(remoteMessageFormat(message), message.text, onBeforeFormat = ::preservePosition,
                            onRichPresented = { if (message.messageId == messages.lastOrNull()?.messageId && attemptText == null) onFinalPresented() })
                    }
                    if (message.text.isNotBlank()) {
                        TextButton(onClick = { detail = message }) { Text("查看全文") }
                    }
                    if (message.attachments.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        AttachmentChips(message.attachments, downloadEnabled) { attachment ->
                            onDownload(message, attachment)
                        }
                    }
                    if (message.status == 3 || message.status == 4) {
                        Text(
                            text = if (message.status == 3) "已停止" else "回复失败",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (attemptText != null && attemptKey != null) {
            item(key = "attempt:${attemptKey.first}:${attemptKey.second}") {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxWidth().fairyDialogPanel(active = true)
                        .padding(horizontal = FairyDialogPaddingH, vertical = FairyDialogPaddingV),
                ) {
                // Still arriving, so it is shown exactly as it has been produced so far.
                MessageBody(remoteAttemptFormat(), attemptText, onBeforeFormat = ::preservePosition, foreground = LocalFairyTokens.current.textPrimary)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttachmentChips(
    attachments: List<RemoteChatAttachment>,
    downloadEnabled: Boolean,
    onDownload: (RemoteChatAttachment) -> Unit,
) {
    // Original name and original size, because that is what the backend recorded and what the
    // download will produce. No preview, no thumbnail, nothing derived from the bytes.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        attachments.forEach { attachment ->
            key(attachment.index) {
                ChatMessageFileChip(
                    text = "${attachment.name} · ${attachmentSizeLabel(attachment.bytes)}",
                    onClick = { if (downloadEnabled) onDownload(attachment) },
                    icon = {
                        Icon(
                            imageVector = HugeIcons.Download01,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
            }
        }
    }
}

/** Shared list/detail entry: immediate raw body, then a background-parsed complete document. */
@Composable
internal fun MessageBody(
    format: RemoteMessageFormat,
    text: String,
    onBeforeFormat: () -> Unit = {},
    onRichPresented: () -> Unit = {},
    foreground: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    val settings = me.rerere.rikkahub.ui.context.LocalSettings.current
    val base = MaterialTheme.typography.bodyMedium
    val style = base.copy(
        fontSize = base.fontSize * settings.displaySetting.fontSizeRatio,
        fontFamily = me.rerere.rikkahub.ui.theme.rememberChatFontFamily(settings.displaySetting),
    )
    val input by rememberUpdatedState(RemoteBodyInput(text, format))
    val beforeFormat by rememberUpdatedState(onBeforeFormat)
    var presentation by remember { mutableStateOf(RemoteBodyPresentation(text)) }
    LaunchedEffect(Unit) {
        remoteBodyPresentations(snapshotFlow { input }) { source ->
            withContext(Dispatchers.Default) { me.rerere.ui.markdown.parseMarkdown(source) }
        }.collect { next ->
            if (next.document != null) beforeFormat()
            presentation = next
        }
    }
    val document = presentation.document?.takeIf {
        format == RemoteMessageFormat.MARKDOWN && it.source == text
    }
    val richPresented by rememberUpdatedState(onRichPresented)
    LaunchedEffect(document) {
        if (document != null) {
            androidx.compose.runtime.withFrameNanos { }
            richPresented()
        }
    }
    if (document != null) {
        // Fairy's reply panel is dark in either app theme; use the existing dark rich-text palette inside it.
        CompositionLocalProvider(LocalDarkMode provides true) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MarkdownBlock(content = text, document = document, style = style.copy(color = LocalFairyTokens.current.textPrimary))
            }
        }
    } else {
        Text(text = text, style = style, color = if (format == RemoteMessageFormat.MARKDOWN) LocalFairyTokens.current.textPrimary else foreground)
    }
}
