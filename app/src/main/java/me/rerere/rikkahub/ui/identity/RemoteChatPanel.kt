package me.rerere.rikkahub.ui.identity

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.remote.RemoteAttachmentDraft
import me.rerere.rikkahub.data.remote.RemoteChatAttachment
import me.rerere.rikkahub.data.remote.RemoteChatMessage
import me.rerere.rikkahub.data.remote.RemoteChatState
import me.rerere.rikkahub.data.remote.RemoteMessageFormat
import me.rerere.rikkahub.data.remote.SessionsSnapshot
import me.rerere.rikkahub.data.remote.remoteAttemptFormat
import me.rerere.rikkahub.data.remote.remoteMessageFormat
import me.rerere.rikkahub.ui.components.message.ChatMessageFileChip
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock

/**
 * A save target carries no usable media type of its own, so the download is written as opaque
 * bytes under the original display name. The declared MIME stays the backend's fact.
 */
private const val SAVE_AS_MIME = "application/octet-stream"

/** What a download click captured before the system file chooser took over the screen. */
private data class PendingDownload(
    val epoch: Long,
    val messageId: String,
    val attachment: RemoteChatAttachment,
)

@Composable
fun RemoteChatPanel(
    state: RemoteChatState,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot by state.snapshot.collectAsStateWithLifecycle()
    val projection = snapshot.projection
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Keyed to the session so switching sessions never carries a draft across the boundary.
    var draft by remember(snapshot.attachmentEpoch) { mutableStateOf("") }
    var importError by remember(snapshot.attachmentEpoch) { mutableStateOf<String?>(null) }
    // Read before the chooser opens, not after it closes: the epoch a selection belongs to is
    // the one from the conversation the user was looking at when they chose the files.
    var selectionEpoch by remember(snapshot.attachmentEpoch) { mutableStateOf<Long?>(null) }
    var pendingDownload by remember(snapshot.attachmentEpoch) { mutableStateOf<PendingDownload?>(null) }

    val drafts = snapshot.attachments
    // Draft editing is frozen while a command is unresolved: the pending record already holds
    // this message's text and upload ids, and changing the draft would contradict a resend.
    val frozen = !snapshot.storageReady || snapshot.sending || snapshot.pending != null
    val uploadsSettled = drafts.all { it.uploadId != null && !it.uploading }
    // Text may be empty as long as files are attached; neither may be empty.
    val canSend = snapshot.storageReady && !frozen && uploadsSettled &&
        (draft.isNotBlank() || drafts.isNotEmpty())

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val epoch = selectionEpoch
        selectionEpoch = null
        if (epoch == null || uris.isEmpty()) return@rememberLauncherForActivityResult
        importError = null
        scope.launch {
            var rejected: String? = null
            for (uri in uris) {
                val imported = try {
                    importAttachment(context, uri)
                } catch (cancelled: CancellationException) {
                    throw cancelled // the helper already deleted its partial copy
                } catch (failure: Exception) {
                    rejected = failure.message ?: "所选文件无法导入"
                    null
                } ?: continue
                // A switch while the chooser was open leaves this epoch stale; the state then
                // deletes the copy instead of uploading it into the other account's session.
                state.addAttachment(epoch, imported.file, imported.name, imported.mime)
            }
            if (state.snapshot.value.attachmentEpoch == epoch) importError = rejected
        }
    }

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(SAVE_AS_MIME)) { target ->
        val captured = pendingDownload.also { pendingDownload = null } ?: return@rememberLauncherForActivityResult
        if (target == null) return@rememberLauncherForActivityResult
        scope.launch {
            // The state streams into this temp file, checks the account and session it was
            // captured under, and only then asks for the bytes to be handed to the chooser.
            val destination = createAttachmentTempFile(context)
            state.downloadAttachment(captured.epoch, captured.messageId, captured.attachment, destination) { file ->
                exportAttachment(context, target, file)
            }
        }
    }

    // Resolved here so the helpers below stay ordinary composables rather than reaching for
    // string resources from inside message rendering.
    val unreachable = stringResource(R.string.identity_unreachable)

    Column(modifier = modifier.fillMaxWidth()) {
        ConnectionHeader(
            connected = snapshot.connected,
            connecting = snapshot.connecting,
            failure = snapshot.failure,
            backendError = projection?.error,
            unreachable = unreachable,
            onReconnect = onReconnect,
        )

        if (snapshot.syncing) {
            Text("正在补齐并保存同步进度…", style = MaterialTheme.typography.bodySmall)
        }
        if (snapshot.failure != null && snapshot.syncing) {
            OutlinedButton(onClick = state::retrySync) { Text("重试同步") }
        }

        snapshot.pending?.let { pending ->
            Spacer(Modifier.height(12.dp))
            PendingCommand(
                text = pending.text,
                attachmentCount = pending.uploadIds.size,
                acceptanceUnknown = pending.acceptanceUnknown,
                busy = snapshot.sending,
                onReconcile = state::reconcile,
                onRetry = state::retryPending,
            )
        }

        snapshot.attachmentNotice?.let { notice ->
            Spacer(Modifier.height(8.dp))
            Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (snapshot.downloading) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("正在下载附件…", style = MaterialTheme.typography.bodySmall)
            }
        }

        RemoteTaskCards(
            tasks = projection?.tasks.orEmpty(),
            detail = snapshot.taskDetail,
            connected = snapshot.connected,
            onOpen = state::observeTaskOutput,
            onClose = state::closeTaskOutput,
            onStop = state::stopTask,
        )

        Spacer(Modifier.height(12.dp))
        Transcript(
            messages = projection?.messages.orEmpty(),
            attemptText = projection?.attempt?.text,
            attemptKey = projection?.let { it.generation to it.attempt?.attemptId },
            downloadEnabled = !snapshot.downloading,
            onDownload = { message, attachment ->
                if (!snapshot.downloading) {
                    pendingDownload = PendingDownload(snapshot.attachmentEpoch, message.messageId, attachment)
                    saveAs.launch(attachment.name)
                }
            },
        )

        Spacer(Modifier.height(12.dp))
        Composer(
            draft = draft,
            onDraftChange = { draft = it },
            attachments = drafts,
            frozen = frozen,
            canSend = canSend,
            replyStatus = projection?.replyStatus ?: 0,
            importError = importError,
            onAddFiles = {
                selectionEpoch = snapshot.attachmentEpoch
                picker.launch(arrayOf("*/*"))
            },
            onRemoveAttachment = { state.removeAttachment(it) },
            onRetryAttachment = { state.retryAttachment(it) },
            onSend = {
                // Only the state knows whether the command was accepted; a refused send keeps
                // the text so nothing the user typed disappears behind an error.
                if (state.send(draft)) draft = ""
            },
            onStop = state::stop,
        )
    }
}

@Composable
private fun ConnectionHeader(
    connected: Boolean,
    connecting: Boolean,
    failure: String?,
    backendError: String?,
    unreachable: String,
    onReconnect: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    connecting -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("连接中…", style = MaterialTheme.typography.titleSmall)
                    }

                    connected -> Text("已连接", style = MaterialTheme.typography.titleSmall)

                    else -> Text("连接已断开", style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.weight(1f))
                if (!connected && !connecting) {
                    OutlinedButton(onClick = onReconnect) { Text("重新连接") }
                }
            }

            // Backend failures are reported as the backend phrased them; only the transport
            // sentinel, which is not text a user wrote or a server wrote, gets local wording.
            listOfNotNull(failure, backendError).forEach { message ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (message == SessionsSnapshot.UNREACHABLE) unreachable else message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun Transcript(
    messages: List<RemoteChatMessage>,
    attemptText: String?,
    attemptKey: Pair<String, String?>?,
    downloadEnabled: Boolean,
    onDownload: (RemoteChatMessage, RemoteChatAttachment) -> Unit,
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

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        messages.forEach { message ->
            key(message.messageId) {
                // The role, not the status, decides how a line is drawn: Fairy messages keep
                // using the rich renderer whether they completed, were interrupted or failed.
                Column {
                    Text(
                        text = if (message.role == 1) "你" else "Fairy",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // An attachment-only message has no body to render, and is still a message.
                    if (message.text.isNotBlank()) {
                        MessageBody(remoteMessageFormat(message), message.text)
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
            key("attempt", attemptKey.first, attemptKey.second) {
                // Still arriving, so it is shown exactly as it has been produced so far.
                MessageBody(remoteAttemptFormat(), attemptText)
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

@Composable
private fun MessageBody(format: RemoteMessageFormat, text: String) {
    if (format == RemoteMessageFormat.MARKDOWN) {
        MarkdownBlock(content = text)
    } else {
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PendingCommand(
    text: String,
    attachmentCount: Int,
    acceptanceUnknown: Boolean,
    busy: Boolean,
    onReconcile: () -> Unit,
    onRetry: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("待确认发送", style = MaterialTheme.typography.titleSmallEmphasized)
            Spacer(Modifier.height(8.dp))
            // The pending record survives a restart, so the count comes from the persisted
            // upload ids rather than from whatever the draft area still happens to hold.
            if (attachmentCount > 0) {
                Text("含 $attachmentCount 个附件", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
            }
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))

            if (acceptanceUnknown) {
                Text(
                    text = "接纳结果尚未确认，请先查询或使用原请求重试",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onReconcile, enabled = !busy) { Text("查询接纳结果") }
                    FilledTonalButton(onClick = onRetry, enabled = !busy) { Text("重试原请求") }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("发送中…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    attachments: List<RemoteAttachmentDraft>,
    frozen: Boolean,
    canSend: Boolean,
    replyStatus: Int,
    importError: String?,
    onAddFiles: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onRetryAttachment: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val running = replyStatus == 1 || replyStatus == 2

    Column {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("消息") },
            enabled = !frozen,
        )
        Spacer(Modifier.height(8.dp))
        AttachmentDrafts(
            attachments = attachments,
            frozen = frozen,
            importError = importError,
            onAdd = onAddFiles,
            onRemove = onRemoveAttachment,
            onRetry = onRetryAttachment,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(onClick = onSend, enabled = canSend) { Text("发送") }
            if (running) {
                // Stopping is a request the backend settles on its own; the button says so rather
                // than pretending the reply is already halted.
                OutlinedButton(onClick = onStop) {
                    Text(if (replyStatus == 2) "停止中…" else "停止回复")
                }
            }
        }
    }
}

@Composable
private fun AttachmentDrafts(
    attachments: List<RemoteAttachmentDraft>,
    frozen: Boolean,
    importError: String?,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onRetry: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        attachments.forEach { draft ->
            key(draft.id) {
                AttachmentDraftRow(
                    draft = draft,
                    frozen = frozen,
                    onRemove = { onRemove(draft.id) },
                    onRetry = { onRetry(draft.id) },
                )
            }
        }

        OutlinedButton(onClick = onAdd, enabled = !frozen) {
            Icon(
                imageVector = HugeIcons.File01,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("添加文件")
        }

        importError?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun AttachmentDraftRow(
    draft: RemoteAttachmentDraft,
    frozen: Boolean,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
) {
    val status = when {
        draft.failure != null -> "上传失败：${draft.failure}"
        draft.uploading -> "上传中…"
        draft.uploadId != null -> "已就绪"
        else -> "等待上传"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = HugeIcons.File01,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = draft.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "$status · ${attachmentSizeLabel(draft.bytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (draft.failure != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (draft.failure != null) {
                TextButton(onClick = onRetry, enabled = !frozen) {
                    Icon(
                        imageVector = HugeIcons.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("重试")
                }
            }
            TextButton(onClick = onRemove, enabled = !frozen) {
                Icon(
                    imageVector = HugeIcons.Delete02,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("移除")
            }
        }
    }
}
