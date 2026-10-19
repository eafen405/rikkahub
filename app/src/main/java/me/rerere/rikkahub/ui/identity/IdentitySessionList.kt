package me.rerere.rikkahub.ui.identity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.remote.OwnedSessionState
import me.rerere.rikkahub.data.remote.SessionsSnapshot
import me.rerere.rikkahub.ui.fairy.LocalFairyTokens

@Composable
internal fun IdentitySessionList(
    state: OwnedSessionState,
    onOpen: (String) -> Unit,
    onClose: () -> Unit,
    onHome: () -> Unit,
    onSettings: () -> Unit,
) {
    val snapshot by state.snapshot.collectAsStateWithLifecycle()
    val tokens = LocalFairyTokens.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("会话", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = state::refresh, enabled = !snapshot.loading) {
                Icon(HugeIcons.Refresh, contentDescription = "刷新会话", modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onClose) {
                Icon(HugeIcons.Cancel01, contentDescription = "关闭会话列表", modifier = Modifier.size(20.dp))
            }
        }
        TextButton(
            enabled = !snapshot.loading,
            onClick = {
                scope.launch {
                    state.create()
                    val created = state.snapshot.first { !it.loading }
                    if (created.failure == null) created.selectedId?.let(onOpen)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(HugeIcons.Add01, contentDescription = null, modifier = Modifier.size(20.dp))
            Text("新建会话", modifier = Modifier.padding(start = 8.dp))
        }
        if (snapshot.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        snapshot.failure?.let {
            Text(
                if (it == SessionsSnapshot.UNREACHABLE) stringResource(R.string.identity_unreachable) else it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (snapshot.sessions.isEmpty() && !snapshot.loading) {
                item { Text("暂无会话", modifier = Modifier.padding(16.dp), color = tokens.textMuted) }
            }
            itemsIndexed(snapshot.sessions, key = { _, session -> session.id }) { index, session ->
                val selected = session.id == snapshot.selectedId
                Column(
                    Modifier.fillMaxWidth()
                        .background(if (selected) tokens.pill else tokens.panelOpaque, tokens.panelShape)
                        .semantics { this.selected = selected }
                        .clickable(enabled = !snapshot.loading) { onOpen(session.id) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Text(if (session.primary) "主会话" else "会话 ${index + 1}", style = MaterialTheme.typography.titleSmall)
                    Text(session.id, style = MaterialTheme.typography.labelSmall, color = tokens.textMuted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onHome) { Text("首页") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onSettings) { Text("设置") }
        }
    }
}
