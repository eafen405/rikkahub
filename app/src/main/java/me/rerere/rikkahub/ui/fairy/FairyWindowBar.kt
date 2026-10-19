package me.rerere.rikkahub.ui.fairy

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Menu01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkahub.R

/** Window-bar content height below the status bar inset. */
val FairyWindowBarHeight = 60.dp

/** Grace period before a finished response folds the screen back. */
private const val RESPONSE_COLLAPSE_DELAY_MS = 500L

/**
 * True while Fairy works before her reply shows text and for a short grace
 * period after, so a brief status flicker does not fold the screen.
 */
@Composable
private fun rememberFairyResponding(awaitingReply: Boolean): Boolean {
    var responding by remember { mutableStateOf(awaitingReply) }
    LaunchedEffect(awaitingReply) {
        if (awaitingReply) {
            responding = true
        } else {
            delay(RESPONSE_COLLAPSE_DELAY_MS)
            responding = false
        }
    }
    return responding
}

/**
 * Fairy's window bar: an opaque bgTop strip with a 1 dp hairline bottom edge
 * (content never shows through), holding back arrow, the 64x44 dp mini
 * screen, the Fairy nameplate with a slanted status subtitle, and the
 * frameless more menu. The screen opens below the bar at full width (16 dp
 * margins, 140 dp high, 88 dp eye, HUD overlay) while
 * [FairyWindowState.expanded] (empty session) or while Fairy analyzes before
 * her reply shows text (see [rememberFairyResponding]). It floats over the message
 * list on an opaque backdrop that fades out below it, and folds back over
 * 350 ms; Reduced Motion switches instantly.
 */
@Composable
fun FairyWindowTopBar(
    window: FairyWindowState,
    subtitle: String?,
    subtitleLeading: (@Composable () -> Unit)? = null,
    subtitleTag: String? = null,
    onNavigateBack: () -> Unit,
    moreMenuContent: (@Composable ColumnScope.(dismiss: () -> Unit) -> Unit)? = null,
    onOpenSessions: (() -> Unit)? = null,
) {
    val tokens = LocalFairyTokens.current
    val allowSpatial = LocalAgoraMotionPolicy.current.allowSpatialTransitions
    var moreMenuOpen by remember { mutableStateOf(false) }
    val windowSpec = tween<IntSize>(350, easing = FastOutSlowInEasing)
    val fadeSpec = tween<Float>(350, easing = FastOutSlowInEasing)
    val responding = rememberFairyResponding(window.awaitingReply)
    val screenOpen = window.expanded || responding
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(tokens.panelOpaque)
                .drawBehind {
                    val stroke = 1.dp.toPx()
                    drawRect(
                        tokens.hairline,
                        topLeft = Offset(0f, size.height - stroke),
                        size = Size(size.width, stroke),
                    )
                }
                .statusBarsPadding()
                .height(FairyWindowBarHeight),
        ) {
            CompositionLocalProvider(LocalContentColor provides tokens.textPrimary) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(start = 4.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onOpenSessions ?: onNavigateBack, modifier = Modifier.size(48.dp)) {
                        Icon(
                            if (onOpenSessions != null) HugeIcons.Menu01 else HugeIcons.ArrowLeft01,
                            contentDescription = if (onOpenSessions != null) "切换会话" else stringResource(R.string.back),
                            tint = tokens.textPrimary,
                        )
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !screenOpen,
                        enter = if (allowSpatial) fadeIn(fadeSpec) + expandHorizontally(windowSpec) else EnterTransition.None,
                        exit = if (allowSpatial) fadeOut(fadeSpec) + shrinkHorizontally(windowSpec) else ExitTransition.None,
                    ) {
                        Row {
                            Spacer(Modifier.width(4.dp))
                            FairyScreen(
                                presence = window.presence,
                                eyeSize = 36.dp,
                                speechPulse = window.speechPulse,
                                eye = window.eye,
                                modifier = Modifier.size(width = 64.dp, height = 44.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(verticalArrangement = Arrangement.Center) {
                        Text(
                            text = stringResource(R.string.fairy_nameplate),
                            fontFamily = tokens.titleFontFamily,
                            fontWeight = FontWeight.Black,
                            fontSize = 18.sp,
                            lineHeight = 22.sp,
                            color = tokens.textPrimary,
                            maxLines = 1,
                        )
                        if (subtitle != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                subtitleLeading?.invoke()
                                Text(
                                    text = subtitle,
                                    fontFamily = tokens.titleFontFamily,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp,
                                    color = tokens.textMuted,
                                    maxLines = 1,
                                    modifier = Modifier.fairySlant(),
                                )
                                // Decorative English tag, only when the subtitle
                                // itself is not English (it would repeat it).
                                if (subtitleTag != null && subtitle.any { it.code > 0x7F }) {
                                    Text(
                                        text = subtitleTag,
                                        fontFamily = tokens.labelFontFamily,
                                        fontSize = 9.sp,
                                        lineHeight = 14.sp,
                                        letterSpacing = 1.sp,
                                        color = tokens.textMuted.copy(alpha = 0.7f),
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (moreMenuContent != null) {
                        Box {
                            IconButton(onClick = { moreMenuOpen = true }, modifier = Modifier.size(48.dp)) {
                                Icon(
                                    HugeIcons.MoreVertical,
                                    contentDescription = stringResource(R.string.menu),
                                    modifier = Modifier.size(24.dp),
                                    tint = tokens.textPrimary,
                                )
                            }
                            DropdownMenu(
                                expanded = moreMenuOpen,
                                onDismissRequest = { moreMenuOpen = false },
                                shape = tokens.panelShape,
                                containerColor = tokens.panelOpaque,
                                border = BorderStroke(1.dp, tokens.hairline),
                                tonalElevation = 0.dp,
                            ) {
                                moreMenuContent { moreMenuOpen = false }
                            }
                        }
                    }
                }
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = screenOpen,
            enter = if (allowSpatial) fadeIn(fadeSpec) + expandVertically(windowSpec) else EnterTransition.None,
            exit = if (allowSpatial) fadeOut(fadeSpec) + shrinkVertically(windowSpec) else ExitTransition.None,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to tokens.panelOpaque,
                            0.8f to tokens.panelOpaque,
                            1f to tokens.panelOpaque.copy(alpha = 0f),
                        ),
                    ),
            ) {
                FairyScreen(
                    presence = window.presence,
                    eyeSize = 88.dp,
                    speechPulse = window.speechPulse,
                    hud = true,
                    eye = window.eye,
                    modifier = Modifier
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp)
                        .fillMaxWidth()
                        .height(140.dp),
                )
            }
        }
    }
}
