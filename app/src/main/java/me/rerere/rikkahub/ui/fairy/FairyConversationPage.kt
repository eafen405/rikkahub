package me.rerere.rikkahub.ui.fairy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.remote.RemoteChatSnapshot
import me.rerere.rikkahub.data.remote.RemoteReceiveState
import me.rerere.rikkahub.data.remote.SessionsSnapshot
import me.rerere.rikkahub.ui.identity.IdentitySessionList
import me.rerere.rikkahub.ui.identity.RemoteChatPanel
import org.koin.compose.koinInject

@Composable
fun FairyConversationPage(
    sessionId: String?,
    onBack: () -> Unit,
    receive: RemoteReceiveState = koinInject(),
    onOpenConversation: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val sessions by receive.owned.snapshot.collectAsStateWithLifecycle()
    val selected by receive.selectedChat.collectAsStateWithLifecycle()
    val selectedChat = selected
    val chat = selectedChat?.snapshot?.collectAsStateWithLifecycle()?.value
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var opening by remember(sessionId) { mutableStateOf(true) }
    var failed by remember(sessionId) { mutableStateOf(false) }
    var retry by remember(sessionId) { mutableIntStateOf(0) }

    LaunchedEffect(sessionId, retry) {
        opening = true
        failed = false
        if (sessionId == null && retry > 0) receive.owned.refresh()
        val ready = receive.owned.snapshot.first { !it.loading }
        if (sessionId == null) {
            val target = ready.selectedId ?: ready.primarySessionId
            opening = false
            if (target != null) onOpenConversation(target) else drawer.open()
        } else {
            val alreadySelected = ready.failure == null && ready.selectedId == sessionId &&
                ready.sessions.any { it.id == sessionId }
            failed = !alreadySelected && !receive.openSession(sessionId)
            opening = false
        }
    }
    val settled = !opening && !failed && sessionId != null &&
        sessions.selectedId == sessionId && chat?.sessionId == sessionId
    val snapshot = chat.takeIf { settled }
    val window = snapshot?.fairyWindow() ?: FairyWindowState(presence = FairyPresence.CONNECTING)
    val tokens = LocalFairyTokens.current
    val failure = sessions.failure?.let {
        if (it == SessionsSnapshot.UNREACHABLE) stringResource(R.string.identity_unreachable) else it
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.widthIn(max = 320.dp),
                drawerContainerColor = tokens.panelOpaque,
                drawerContentColor = tokens.textPrimary,
            ) {
                IdentitySessionList(
                    state = receive.owned,
                    onOpen = { id ->
                        scope.launch {
                            drawer.close()
                            if (id != sessionId) onOpenConversation(id)
                            else if (!settled) retry++
                        }
                    },
                    onClose = { scope.launch { drawer.close() } },
                    onHome = onBack,
                    onSettings = { scope.launch { drawer.close(); onSettings() } },
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            FairyBackground(watermark = false)
            Box(
                Modifier.fillMaxSize().statusBarsPadding().padding(top = FairyWindowBarHeight)
                    .navigationBarsPadding().imePadding(),
            ) {
                if (settled && selectedChat != null) {
                    key(sessionId) {
                        RemoteChatPanel(
                            state = selectedChat,
                            onReconnect = receive::resumeObservation,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            if (opening) "正在打开会话…" else failure ?: "暂时无法打开此会话",
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.textMuted,
                        )
                        if (!opening) {
                            TextButton(onClick = { retry++ }) { Text("重试") }
                            TextButton(onClick = { scope.launch { drawer.open() } }) { Text("选择会话") }
                        }
                    }
                }
            }
            FairyWindowTopBar(
                window = window,
                subtitle = when {
                    opening -> "正在打开会话…"
                    !settled -> "会话不可用"
                    snapshot?.connecting == true -> "连接中…"
                    snapshot?.connected != true -> "连接已断开"
                    window.awaitingReply -> "正在思考…"
                    window.presence.isWorking -> "正在工作…"
                    snapshot.activity.status == 0 -> "同步中…"
                    else -> "已连接"
                },
                onNavigateBack = onBack,
                onOpenSessions = { scope.launch { drawer.open() } },
                moreMenuContent = { dismiss ->
                    DropdownMenuItem(text = { Text("首页") }, onClick = { dismiss(); onBack() })
                    DropdownMenuItem(text = { Text("设置") }, onClick = { dismiss(); onSettings() })
                },
            )
        }
    }
}

internal fun RemoteChatSnapshot.fairyWindow(): FairyWindowState {
    val working = activity.status == 2
    val hasOutput = !projection?.attempt?.text.isNullOrBlank()
    return FairyWindowState(
        presence = when {
            connecting -> FairyPresence.CONNECTING
            !connected -> FairyPresence.OFFLINE
            activity.status == 0 -> FairyPresence.CONNECTING
            working && hasOutput -> FairyPresence.SPEAKING
            working -> FairyPresence.THINKING
            else -> FairyPresence.IDLE
        },
        expanded = connected && projection != null && projection.messages.isEmpty() && !hasOutput,
        awaitingReply = working && 1 in activity.reasons && !hasOutput,
    )
}
