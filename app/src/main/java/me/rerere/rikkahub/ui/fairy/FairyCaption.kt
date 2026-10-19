package me.rerere.rikkahub.ui.fairy

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private const val CHAR_MS = 45L
private const val ACCEL_START_MS = 70f
private const val ACCEL_END_MS = 6f

/** How long a fully typed segment stays before the next one. */
const val CAPTION_HOLD_MS = 2500L

/**
 * One idle line, typed out segment by segment like the game's dialog box:
 * each segment types in, holds [CAPTION_HOLD_MS], then gives way to the next;
 * [onFinished] runs after the last one. A tap completes the segment being
 * typed, or moves on. Untyped characters are laid out but transparent, so
 * the box does not grow line by line. Reduced Motion shows the whole line
 * at once for the same total time.
 *
 * [compact] is the chat page's HUD strip under the window bar; otherwise it
 * is a dialog box with a "Fairy" name tab (console).
 */
@Composable
fun FairyCaption(
    line: FairyLine,
    compact: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalFairyTokens.current
    val animate = LocalAgoraMotionPolicy.current.allowContinuousMotion
    val finished by rememberUpdatedState(onFinished)
    var segment by remember(line) { mutableIntStateOf(0) }
    var shown by remember(line) { mutableIntStateOf(0) }
    val taps = remember(line) { Channel<Unit>(Channel.CONFLATED) }
    LaunchedEffect(line, animate) {
        if (!animate) {
            segment = -1
            delay(CAPTION_HOLD_MS * line.segments.size)
            finished()
            return@LaunchedEffect
        }
        line.segments.forEachIndexed { index, text ->
            segment = index
            shown = 0
            taps.tryReceive()
            for (i in 1..text.length) {
                if (taps.tryReceive().isSuccess) break
                shown = i
                val ms = if (line.accelerates(index)) {
                    ACCEL_START_MS + (ACCEL_END_MS - ACCEL_START_MS) * i / text.length
                } else {
                    CHAR_MS.toFloat()
                }
                delay(ms.toLong())
            }
            shown = text.length
            withTimeoutOrNull(CAPTION_HOLD_MS) { taps.receive() }
        }
        finished()
    }
    val full = if (segment < 0) line.segments.joinToString("\n") else line.segments[segment]
    val text = buildAnnotatedString {
        val cut = if (segment < 0) full.length else shown.coerceAtMost(full.length)
        append(full.substring(0, cut))
        withStyle(SpanStyle(color = Color.Transparent)) { append(full.substring(cut)) }
    }
    val tap = Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { taps.trySend(Unit) }
    val bodyStyle = MaterialTheme.typography.bodyMedium
    if (compact) {
        Row(
            modifier
                .fillMaxWidth()
                .fairyPanel(RoundedCornerShape(10.dp), tokens.panelOpaque.copy(alpha = 0.94f))
                .then(tap)
                .animateContentSize(tween(200))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                "FAIRY",
                fontFamily = tokens.labelFontFamily,
                fontSize = 10.sp,
                lineHeight = 18.sp,
                letterSpacing = 1.5.sp,
                color = tokens.fairyLink,
                modifier = Modifier.fairySlant(),
            )
            Spacer(Modifier.width(10.dp))
            Text(text, style = bodyStyle.copy(fontSize = 13.sp, lineHeight = 18.sp), color = tokens.textPrimary)
        }
        return
    }
    Box(modifier.fillMaxWidth().padding(top = 10.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(tokens.panelOpaque, RoundedCornerShape(14.dp))
                .fairyDialogPanel(active = segment >= 0 && shown < full.length)
                .then(tap)
                .animateContentSize(tween(200))
                .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 14.dp),
        ) {
            Text(text, style = bodyStyle.copy(fontSize = 15.sp, lineHeight = 21.sp), color = tokens.textPrimary)
        }
        // Name tab on the top edge, as in the game's dialog box.
        Text(
            "Fairy",
            fontFamily = tokens.titleFontFamily,
            fontWeight = FontWeight.Black,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            color = tokens.textPrimary,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = 18.dp, y = (-8).dp)
                .background(tokens.panelOpaque, RoundedCornerShape(6.dp))
                .fairyPanel(RoundedCornerShape(6.dp), tokens.pill)
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}
