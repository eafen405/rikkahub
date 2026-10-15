package me.rerere.rikkahub.ui.identity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import me.rerere.rikkahub.data.remote.RemoteChatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.remote.AccountSession
import me.rerere.rikkahub.data.remote.OwnedSessionState
import me.rerere.rikkahub.data.remote.SessionBackend
import me.rerere.rikkahub.data.remote.SessionsSnapshot
import org.koin.compose.koinInject
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource

/**
 * Your own remote sessions: what you have, which one you are standing in, and
 * how to start another or walk back to your main one.
 *
 * This page reads the account from [AccountSession] and nothing else — it holds
 * no identity of its own. Keying the page state to that credential is what makes
 * a sign-out or a new sign-in empty this page immediately, rather than leaving
 * the last account's list on screen.
 */
@Composable
fun IdentitySessionsPage(
    onBack: () -> Unit,
    session: AccountSession = koinInject(),
    backend: SessionBackend = koinInject(),
    chat: RemoteChatState = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val token = session.token

    // Keyed to the credential, not merely reset by it: a different account gets
    // different state, so the previous account's snapshot cannot even be shown
    // for the frame before the new credential is bound.
    val state = remember(token, backend) { OwnedSessionState(backend, scope) }

    LaunchedEffect(state) {
        state.bind(token)
        if (token != null) state.refresh()
    }

    val snapshot by state.snapshot.collectAsStateWithLifecycle()
    val chatSnapshot by chat.snapshot.collectAsStateWithLifecycle()
    LaunchedEffect(token, snapshot.selectedId) { chat.bind(token, snapshot.selectedId, session.user?.shortId) }
    DisposableEffect(chat) { onDispose { chat.detach() } }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Top,
        ) {
            Text(
                text = stringResource(R.string.sessions_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.sessions_explain),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))
            SelectedSessionCard(
                selectedId = snapshot.selectedId,
                loading = snapshot.loading,
            )

            if (token != null && snapshot.selectedId != null && chatSnapshot.sessionId == snapshot.selectedId) {
                Spacer(Modifier.height(16.dp))
                RemoteChatPanel(
                    state = chat,
                    onReconnect = { chat.bind(token, snapshot.selectedId, session.user?.shortId) },
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { state.refresh() },
                    enabled = token != null && !snapshot.loading,
                ) {
                    Text(stringResource(R.string.sessions_refresh_action))
                }
                Button(
                    onClick = { state.create() },
                    enabled = token != null && !snapshot.loading,
                ) {
                    Text(stringResource(R.string.sessions_create_action))
                }
            }

            snapshot.failure?.let { message ->
                Spacer(Modifier.height(12.dp))
                Text(
                    // A failure that is not text the backend wrote is shown with
                    // the same localized copy the sign-in pages already use.
                    text = if (message == SessionsSnapshot.UNREACHABLE) {
                        stringResource(R.string.identity_unreachable)
                    } else {
                        message
                    },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (token == null) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.sessions_signed_out),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.sessions_list_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))

            if (snapshot.sessions.isEmpty()) {
                Text(
                    text = stringResource(R.string.sessions_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            snapshot.sessions.forEach { owned ->
                val isSelected = owned.id == snapshot.selectedId
                Spacer(Modifier.height(8.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.sessions_row_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = owned.id,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (owned.primary) {
                            Text(
                                text = stringResource(R.string.sessions_row_primary),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        FilledTonalButton(
                            onClick = { state.select(owned.id) },
                            enabled = !snapshot.loading && !isSelected,
                        ) {
                            Text(stringResource(R.string.sessions_select_action))
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            OutlinedButton(
                onClick = { state.returnToPrimary() },
                enabled = token != null && !snapshot.loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.sessions_return_primary_action))
            }

            Spacer(Modifier.height(32.dp))
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.sessions_back_action))
            }
        }
    }
}

/**
 * The backend's selected session identifier; chat is rendered from the shared remote projection.
 */
@Composable
private fun SelectedSessionCard(selectedId: String?, loading: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.sessions_selected_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = selectedId ?: stringResource(R.string.sessions_selected_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (loading) {
                Spacer(Modifier.height(12.dp))
                CircularProgressIndicator()
            }
        }
    }
}
