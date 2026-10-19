package me.rerere.rikkahub.ui.fairy

import android.os.SystemClock
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.remote.RemoteReceiveState
import org.koin.compose.koinInject

private const val FIRST_LINE_MS = 45_000L
private const val NEXT_LINE_MIN_MS = 60_000L
private const val NEXT_LINE_MAX_MS = 90_000L
private const val SLEEP_AT_MS = 180_000L
private const val TICK_MS = 500L

/**
 * The console: Fairy's large live screen over the HDD background, an honest
 * readout of who is signed in and which session is primary, and the two ways
 * on — sessions and settings. Fairy follows the finger anywhere on the page
 * (observational only; touches still reach the keys), says a line after 45 s,
 * and dozes off stand-by after three idle minutes.
 */
@Composable
fun FairyConsolePage(
    onOpen: (Screen) -> Unit,
    session: me.rerere.rikkahub.data.remote.AccountSession = koinInject(),
    receive: RemoteReceiveState = koinInject(),
) {
    val tokens = LocalFairyTokens.current
    val context = LocalContext.current
    val memory = remember { FairyUiMemory(context) }
    val eye = remember { FairyEyeController() }
    val identity by session.identity.collectAsStateWithLifecycle()
    val owned by receive.owned.snapshot.collectAsStateWithLifecycle()
    var caption by remember { mutableStateOf<FairyLine?>(null) }
    var asleep by remember { mutableStateOf(false) }
    var lastTouch by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var nextLineAt by remember { mutableLongStateOf(FIRST_LINE_MS) }
    var powerOn by remember { mutableIntStateOf(if (FairyColdStart.claim()) 1 else 0) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val speaks = LocalConfiguration.current.locales[0].language == "zh"
    val running = lifecycle.isAtLeast(Lifecycle.State.STARTED)

    LaunchedEffect(running, speaks, asleep) {
        if (!running || !speaks || asleep) {
            caption = null
            return@LaunchedEffect
        }
        while (true) {
            delay(TICK_MS)
            val idle = SystemClock.uptimeMillis() - lastTouch
            when {
                idle >= SLEEP_AT_MS && caption == null -> {
                    asleep = true
                    eye.asleep = true
                    caption = memory.nextLine(FairyLines.sleepPool, 0)
                    return@LaunchedEffect
                }

                idle >= nextLineAt && caption == null -> {
                    caption = memory.nextLine(FairyLines.homePool, FairyLines.HOME_AVOID)
                    nextLineAt = idle + NEXT_LINE_MIN_MS + (0L..(NEXT_LINE_MAX_MS - NEXT_LINE_MIN_MS)).random()
                }
            }
        }
    }
    val origin = remember { floatArrayOf(0f, 0f) }
    val primary = owned.primarySessionId
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(tokens.bgTop, tokens.bgBottom))),
    ) {
        FairyBackground()
        Column(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned {
                    origin[0] = it.positionInRoot().x
                    origin[1] = it.positionInRoot().y
                }
                .pointerInput(eye) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            val touch = event.changes.firstOrNull { it.pressed }
                            eye.lookAt(touch?.let { Offset(origin[0] + it.position.x, origin[1] + it.position.y) })
                            if (touch != null) {
                                lastTouch = SystemClock.uptimeMillis()
                                nextLineAt = FIRST_LINE_MS
                                if (asleep) {
                                    asleep = false
                                    eye.wake(confused = true)
                                }
                            }
                        }
                    }
                }
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
        ) {
            Text(
                text = "HOLLOW DEEP DIVE",
                fontFamily = tokens.titleFontFamily,
                fontWeight = FontWeight.Black,
                fontSize = 30.sp,
                lineHeight = 36.sp,
                color = tokens.textPrimary,
            )
            Text(
                text = "DEEP DIVE CONSOLE",
                fontFamily = tokens.labelFontFamily,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                color = tokens.fairyLink.copy(alpha = 0.7f),
                modifier = Modifier.fairySlant(),
            )
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().zIndex(1f)) {
                FairyScreen(
                    presence = FairyPresence.IDLE,
                    eyeSize = 112.dp,
                    hud = true,
                    eye = eye,
                    hudStatus = if (asleep) "SLEEP" else null,
                    modifier = Modifier.fillMaxWidth().height(208.dp),
                )
                if (asleep) {
                    Text(
                        "TAP TO WAKE",
                        fontFamily = tokens.labelFontFamily,
                        fontSize = 11.sp,
                        letterSpacing = 1.5.sp,
                        color = tokens.fairyLink.copy(alpha = 0.85f),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).fairySlant(),
                    )
                }
                caption?.let { line ->
                    Box(Modifier.align(Alignment.BottomCenter)) {
                        FairyCaption(line, compact = false, onFinished = { caption = null })
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .fairyPanel(tokens.panelShape)
                    .fairyHudCorners()
                    .padding(horizontal = 18.dp, vertical = 6.dp),
            ) {
                identity?.let { who ->
                    Readout("PROXY ID", "身份") { ReadoutValue(who.user.name) }
                    Readout("SHORT ID", "编号") { ReadoutValue("#${who.user.shortId}") }
                    Readout("ROLE", "权限") {
                        ReadoutValue(if (who.user.isAdmin) "ADMIN" else "AGENT")
                    }
                }
                Readout("SESSION", "主会话") {
                    ReadoutValue(primary ?: "—")
                }
                Readout("SESSIONS", "会话数") {
                    ReadoutValue(owned.sessions.size.toString())
                }
            }
            Spacer(Modifier.height(24.dp))
            SlantKey(
                text = "进入会话",
                tag = "SESSIONS",
                primary = true,
                onClick = {
                    val id = owned.selectedId ?: owned.primarySessionId
                    onOpen(id?.let(Screen::Conversation) ?: Screen.Sessions)
                },
                modifier = Modifier.fillMaxWidth().height(58.dp),
            )
            Spacer(Modifier.height(12.dp))
            SlantKey(
                text = "设置",
                tag = "SETTINGS",
                primary = false,
                onClick = { onOpen(Screen.Settings) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            )
        }
        FairyCrtPowerOn(powerOn)
    }
}

/** One HUD readout row: a fixed-width slanted tag, then the value. */
@Composable
private fun Readout(tag: String, label: String, value: @Composable () -> Unit) {
    val tokens = LocalFairyTokens.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = tag,
            fontFamily = tokens.labelFontFamily,
            fontSize = 9.sp,
            letterSpacing = 1.sp,
            color = tokens.textMuted.copy(alpha = 0.75f),
            modifier = Modifier.width(84.dp).fairySlant(),
        )
        value()
    }
}

@Composable
private fun ReadoutValue(text: String) {
    val tokens = LocalFairyTokens.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = tokens.textPrimary,
        maxLines = 1,
    )
}

/** A slanted console key: the label, a small English tag, and a press-tinted fill. */
@Composable
fun SlantKey(
    text: String,
    tag: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalFairyTokens.current
    val shape = FairySlantShape(slant = 12.dp, corner = 8.dp)
    val interaction = remember { MutableInteractionSource() }
    val fill = if (primary) tokens.primary else tokens.pill
    val label = if (primary) tokens.onPrimary else tokens.textPrimary
    Box(
        modifier
            .background(fill, shape)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = text,
                fontFamily = tokens.titleFontFamily,
                fontWeight = FontWeight.Black,
                fontSize = 16.sp,
                color = label,
            )
            Text(
                text = tag,
                fontFamily = tokens.labelFontFamily,
                fontSize = 9.sp,
                letterSpacing = 1.2.sp,
                color = label.copy(alpha = 0.6f),
                modifier = Modifier.fairySlant(),
            )
        }
    }
}
