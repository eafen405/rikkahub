package me.rerere.rikkahub.ui.fairy

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.selection.SelectionContainer
import me.rerere.rikkahub.data.remote.IdentityBackend
import me.rerere.rikkahub.data.remote.IdentityRefused
import me.rerere.rikkahub.data.remote.IdentityUnreachable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.rikkahub.data.remote.AccountSession
import me.rerere.rikkahub.ui.identity.RemoteDisplayPreferences
import me.rerere.rikkahub.ui.identity.RemoteReceiveSetup
import org.koin.compose.koinInject

/**
 * Settings as three collapsed groups rather than one flat list: the identity
 * this device is acting as, background reception, and the administrator's
 * operations, with sign-out at the bottom. Each group opens on tap and shows a
 * one-line summary while closed, so the page fits on one screen.
 */
@Composable
fun FairySettingsPage(
    onOpenGateway: () -> Unit,
    onSignedOut: () -> Unit,
    session: AccountSession = koinInject(),
    onBack: () -> Unit,
) {
    val tokens = LocalFairyTokens.current
    val identity by session.identity.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf<String?>("account") }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(tokens.bgTop, tokens.bgBottom))),
    ) {
        FairyBackground(watermark = false)
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(HugeIcons.ArrowLeft01, contentDescription = "返回")
                }
                Text(
                    text = "设置",
                    fontFamily = tokens.titleFontFamily,
                    fontWeight = FontWeight.Black,
                    fontSize = 26.sp,
                    color = tokens.textPrimary,
                )
            }
            Text(
                text = "SYSTEM CONFIGURATION",
                fontFamily = tokens.labelFontFamily,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                color = tokens.fairyLink.copy(alpha = 0.7f),
                modifier = Modifier.fairySlant(),
            )
            Spacer(Modifier.height(16.dp))

            FairySettingsGroup(
                title = "账号",
                tag = "ACCOUNT",
                summary = identity?.user?.let { "${it.name} · #${it.shortId}" } ?: "未登录",
                open = open == "account",
                onToggle = { open = if (open == "account") null else "account" },
            ) {
                identity?.let { who ->
                    GroupRow("身份", who.user.name)
                    GroupRow("编号", "#${who.user.shortId}")
                    GroupRow("权限", if (who.user.isAdmin) "管理员" else "代理人")
                }
                Spacer(Modifier.height(12.dp))
                SlantKey(
                    text = "退出登录",
                    tag = "SIGN OUT",
                    primary = false,
                    onClick = {
                        scope.launch {
                            session.signOut()
                            onSignedOut()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                )
            }

            FairySettingsGroup(
                title = "后台接收",
                tag = "UPLINK",
                summary = "通知、后台运行与权限",
                open = open == "receive",
                onToggle = { open = if (open == "receive") null else "receive" },
            ) {
                RemoteReceiveSetup()
            }

            FairySettingsGroup(
                title = "显示",
                tag = "DISPLAY",
                summary = "聊天字体与字号",
                open = open == "display",
                onToggle = { open = if (open == "display") null else "display" },
            ) {
                RemoteDisplayPreferences()
            }

            if (identity?.user?.isAdmin == true) {
                FairySettingsGroup(
                    title = "管理",
                    tag = "ADMIN",
                    summary = "共用模型、网关配置与邀请码",
                    open = open == "admin",
                    onToggle = { open = if (open == "admin") null else "admin" },
                ) {
                    GroupRow("网关", "服务提供方、模型与语音识别", onClick = onOpenGateway)
                    InviteAction(session)
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = "HOLLOW DEEP DIVE SYSTEM · BUILD 1",
                fontFamily = tokens.labelFontFamily,
                fontSize = 9.sp,
                letterSpacing = 1.5.sp,
                color = tokens.textMuted.copy(alpha = 0.6f),
                modifier = Modifier.fairySlant(),
            )
        }
    }
}

/** A collapsible group: slanted tag and title, summary while closed. */
@Composable
internal fun FairySettingsGroup(
    title: String,
    tag: String,
    summary: String,
    open: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val tokens = LocalFairyTokens.current
    Column(
        Modifier
            .padding(bottom = 12.dp)
            .fillMaxWidth()
            .fairyPanel(tokens.panelShape)
            .fairyHudCorners(arm = 7.dp)
            .animateContentSize(tween(200))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                fontFamily = tokens.titleFontFamily,
                fontWeight = FontWeight.Black,
                fontSize = 16.sp,
                color = tokens.textPrimary,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = tag,
                fontFamily = tokens.labelFontFamily,
                fontSize = 9.sp,
                letterSpacing = 1.2.sp,
                color = tokens.textMuted.copy(alpha = 0.65f),
                modifier = Modifier.fairySlant(),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (open) "−" else "+",
                fontFamily = tokens.labelFontFamily,
                fontSize = 18.sp,
                color = tokens.fairyLink,
            )
        }
        if (!open) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textMuted,
                maxLines = 1,
            )
        } else {
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** One label/value row inside a group; [onClick] makes the whole row a command. */
@Composable
private fun GroupRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    val tokens = LocalFairyTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(
                    remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ) else Modifier,
            )
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = tokens.textMuted)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = tokens.textPrimary, maxLines = 1)
    }
}

@Composable
private fun InviteAction(session: AccountSession, backend: IdentityBackend = koinInject()) {
    val scope = rememberCoroutineScope()
    var invite by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    TextButton(enabled = !busy, onClick = {
        val token = session.token ?: return@TextButton
        busy = true
        failure = null
        scope.launch {
            try {
                invite = backend.createInvite(token)
            } catch (refused: IdentityRefused) {
                failure = refused.message ?: refused.code
            } catch (unreachable: IdentityUnreachable) {
                failure = "无法连接，请稍后重试"
            } finally {
                busy = false
            }
        }
    }) { Text(if (busy) "正在生成…" else "生成邀请码") }
    invite?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.titleMedium) } }
    failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
