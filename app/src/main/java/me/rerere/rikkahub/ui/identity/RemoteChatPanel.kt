package me.rerere.rikkahub.ui.identity

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.Image01
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.hugeicons.stroke.Stop
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.remote.RemoteAttachmentDraft
import me.rerere.rikkahub.data.remote.RemoteChatAttachment
import me.rerere.rikkahub.data.remote.RemoteChatState
import me.rerere.rikkahub.data.remote.SessionsSnapshot
import me.rerere.rikkahub.ui.fairy.LocalFairyTokens
import me.rerere.rikkahub.ui.fairy.fairyPanel

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

    var speechBusy by remember(snapshot.attachmentEpoch) { mutableStateOf(false) }
    var speechError by remember(snapshot.attachmentEpoch) { mutableStateOf<String?>(null) }
    var showTasks by remember(snapshot.attachmentEpoch) { mutableStateOf(false) }
    val drafts = snapshot.attachments
    // Draft editing is frozen while a command is unresolved: the pending record already holds
    // this message's text and upload ids, and changing the draft would contradict a resend.
    val frozen = !snapshot.storageReady || snapshot.sending || snapshot.pending != null || speechBusy
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

    if (showTasks) {
        DisposableEffect(state) { onDispose { state.closeTaskOutput() } }
        ModalBottomSheet(
            onDismissRequest = { showTasks = false },
            containerColor = LocalFairyTokens.current.panelOpaque,
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                RemoteTaskCards(
                    tasks = projection?.tasks.orEmpty(),
                    detail = snapshot.taskDetail,
                    connected = snapshot.connected,
                    onOpen = state::observeTaskOutput,
                    onClose = state::closeTaskOutput,
                    onStop = state::stopTask,
                )
            }
        }
    }
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (!snapshot.connected || snapshot.failure != null || projection?.error != null) {
            ConnectionHeader(
                connected = snapshot.connected,
                connecting = snapshot.connecting,
                failure = snapshot.failure,
                backendError = projection?.error,
                unreachable = unreachable,
                onReconnect = onReconnect,
            )
        }
        if (snapshot.failure != null && snapshot.syncing) {
            TextButton(onClick = state::retrySync) { Text("重试同步") }
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

        if (!projection?.tasks.isNullOrEmpty()) {
            TextButton(onClick = { showTasks = true }) { Text("任务 · ${projection.tasks.size}") }
        }
        key(snapshot.attachmentEpoch) {
            RemoteTranscript(
                messages = projection?.messages.orEmpty(),
                attemptText = projection?.attempt?.text,
                attemptKey = projection?.let { it.generation to it.attempt?.attemptId },
                settledAttempt = projection?.settledAttempt?.let { attemptRowKey(projection.generation, it.attemptId) to it.seq },
                modifier = Modifier.weight(1f),
                downloadEnabled = !snapshot.downloading,
                onDownload = { message, attachment ->
                    if (!snapshot.downloading) {
                        pendingDownload = PendingDownload(snapshot.attachmentEpoch, message.messageId, attachment)
                        saveAs.launch(attachment.name)
                    }
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        Composer(
            draft = draft,
            onDraftChange = { draft = it },
            attachments = drafts,
            frozen = frozen,
            canSend = canSend,
            replyStatus = projection?.replyStatus ?: 0,
            importError = importError ?: speechError,
            onAddFiles = { imagesOnly ->
                selectionEpoch = snapshot.attachmentEpoch
                picker.launch(arrayOf(if (imagesOnly) "image/*" else "*/*"))
            },
            speechInput = {
                RemoteSpeechInput(
                    state = state,
                    epoch = snapshot.attachmentEpoch,
                    enabled = !frozen,
                    readDraft = { draft },
                    writeDraft = { draft = it },
                    onBusy = { speechBusy = it },
                    onError = { speechError = it },
                )
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
            Text(text = text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
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
    onAddFiles: (imagesOnly: Boolean) -> Unit,
    speechInput: @Composable () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onRetryAttachment: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val running = replyStatus == 1 || replyStatus == 2
    val tokens = LocalFairyTokens.current
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).fairyPanel(tokens.panelShape).padding(4.dp)) {
        if (attachments.isNotEmpty()) {
            Column(Modifier.heightIn(max = 140.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                attachments.forEach { attachment ->
                    key(attachment.id) {
                        AttachmentDraftRow(attachment, frozen,
                            onRemove = { onRemoveAttachment(attachment.id) },
                            onRetry = { onRetryAttachment(attachment.id) })
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = !frozen) {
                    Icon(HugeIcons.Add01, contentDescription = "添加附件", modifier = Modifier.size(22.dp))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false },
                    containerColor = tokens.panelOpaque) {
                    DropdownMenuItem(text = { Text("图片") },
                        leadingIcon = { Icon(HugeIcons.Image01, contentDescription = null) },
                        onClick = { menuOpen = false; onAddFiles(true) })
                    DropdownMenuItem(text = { Text("文件") },
                        leadingIcon = { Icon(HugeIcons.File01, contentDescription = null) },
                        onClick = { menuOpen = false; onAddFiles(false) })
                }
            }
            TextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("发送消息…") },
                enabled = !frozen,
                maxLines = 5,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
            speechInput()
            if (running && !canSend) {
                IconButton(onClick = onStop, enabled = replyStatus != 2) {
                    Icon(HugeIcons.Stop, contentDescription = if (replyStatus == 2) "停止中…" else "停止回复",
                        modifier = Modifier.size(22.dp))
                }
            } else {
                IconButton(onClick = onSend, enabled = canSend) {
                    Icon(HugeIcons.ArrowUp02, contentDescription = "发送消息", modifier = Modifier.size(22.dp))
                }
            }
        }
        importError?.let { Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
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
