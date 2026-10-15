package me.rerere.rikkahub.ui.identity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.remote.RemoteChatMessage
import me.rerere.rikkahub.data.remote.RemoteChatState
import me.rerere.rikkahub.data.remote.RemoteMessageFormat
import me.rerere.rikkahub.data.remote.SessionsSnapshot
import me.rerere.rikkahub.data.remote.remoteAttemptFormat
import me.rerere.rikkahub.data.remote.remoteMessageFormat
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock

@Composable
fun RemoteChatPanel(
    state: RemoteChatState,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot by state.snapshot.collectAsStateWithLifecycle()
    val projection = snapshot.projection

    // Keyed to the session so switching sessions never carries a draft across the boundary.
    var draft by remember(snapshot.sessionId) { mutableStateOf("") }
    val canSend = draft.isNotBlank() && !snapshot.sending && snapshot.pending == null

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

        snapshot.pending?.let { pending ->
            Spacer(Modifier.height(12.dp))
            PendingCommand(
                text = pending.text,
                acceptanceUnknown = pending.acceptanceUnknown,
                busy = snapshot.sending,
                onReconcile = state::reconcile,
                onRetry = state::retryPending,
            )
        }

        Spacer(Modifier.height(12.dp))
        Transcript(
            messages = projection?.messages.orEmpty(),
            attemptText = projection?.attempt?.text,
            attemptKey = projection?.let { it.generation to it.attempt?.attemptId },
        )

        Spacer(Modifier.height(12.dp))
        Composer(
            draft = draft,
            onDraftChange = { draft = it },
            canSend = canSend,
            replyStatus = projection?.replyStatus ?: 0,
            onSend = {
                state.send(draft)
                draft = ""
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
                    MessageBody(remoteMessageFormat(message), message.text)
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
    canSend: Boolean,
    replyStatus: Int,
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
