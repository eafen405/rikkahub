package me.rerere.rikkahub.ui.fairy

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.R

/**
 * The boot-log header, verbatim from the in-game terminal (including its
 * "Insitute" spelling). It types out once per process on the sign-in page and
 * then folds into the three status lines below.
 */
val HddBootHeader = listOf(
    "// Copyright(C) A.K.A Hollow Deep Dive System",
    "// Led by Helios Research Insitute",
    "// Author: BW",
    "// deep_dive_prototype_v4.h",
    "////////////////////////////////////////////",
    "#ifndef DEEP_DIVE_PROTOTYPE_V4_H",
    "#define DEEP_DIVE_PROTOTYPE_V4_H",
    "#include <MAP>",
    "#include \"helios_signal.h\"",
    "namespace Hollow",
    "{",
    "  class DeepDivePrototypeV4 : public DeepDiveBase",
    "  {",
    "    private:",
    "      HELIOS::BangbooHandle m_handle;",
    "      HChessboardMap<EHDomainType.EHSensorType> m_crossDomainSensorMap;",
    "      HSensorContainer<VisualSensor> m_visualSensorContainer;",
    "    public:",
    "      virtual void RegisterBangbooHandle(HollowSignal signal) override;",
    "      HCRESULT SyncVisualSensor(const HMatrics matWorld, HLINT hollowIndex);",
    "  }",
    "}",
)

/** Status lines the terminal folds into once the header has been typed. */
private val HddReadyLines = listOf(
    "> deep_dive_prototype_v4.h ........ LOADED",
    "> HOLLOW DEEP DIVE SYSTEM ......... STANDBY",
    "> AWAITING PROXY CREDENTIALS",
)

private const val TYPE_CHARS_PER_MS = 0.45f
private val HddKeywords = Regex(
    "#ifndef|#define|#include|\\bnamespace\\b|\\bclass\\b|\\bpublic\\b|\\bprivate\\b|\\bvirtual\\b|\\bvoid\\b|\\boverride\\b|\\bconst\\b",
)

/** Once per process: later visits to the sign-in page skip straight to the form. */
private object HddBootLog {
    var played = false
}

/**
 * The boot-log pane on the sign-in page: an inset terminal glass with scanlines
 * over it, typing [HddBootHeader] once per cold start (tap to skip; Reduced
 * Motion shows the finished frame), then folding into the status lines.
 *
 * [onBooted] reports that the form below may appear.
 */
@Composable
fun FairyHddBoot(
    modifier: Modifier = Modifier,
    onBooted: () -> Unit,
) {
    val tokens = LocalFairyTokens.current
    val still = !LocalAgoraMotionPolicy.current.allowContinuousMotion
    val total = remember { HddBootHeader.sumOf { it.length + 1 } }
    var typed by remember { mutableIntStateOf(if (still || HddBootLog.played) total else 0) }
    val booted = typed >= total
    LaunchedEffect(booted) {
        if (booted) {
            if (!still) HddBootLog.played = true
            onBooted()
            return@LaunchedEffect
        }
        val start = withFrameMillis { it } - (typed / TYPE_CHARS_PER_MS).toLong()
        while (typed < total) {
            typed = withFrameMillis { now -> ((now - start) * TYPE_CHARS_PER_MS).toInt().coerceAtMost(total) }
        }
    }
    val comment = tokens.textMuted
    val keyword = tokens.fairyLink
    val text = remember(typed, booted) {
        if (booted) {
            terminalText(HddReadyLines, comment, keyword, ready = true)
        } else {
            var left = typed
            val lines = buildList {
                for (line in HddBootHeader) {
                    if (left <= 0) break
                    add(line.take(left))
                    left -= line.length + 1
                }
            }
            terminalText(lines, comment, keyword, ready = false)
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, tokens.fairyBlue.copy(alpha = 0.5f), tokens.screenShape)
            .drawBehind {
                drawRoundRect(tokens.screenEdge.copy(alpha = 0.92f), cornerRadius = CornerRadius(14.dp.toPx()))
                // Scanlines over the terminal glass.
                val step = 2.dp.toPx()
                var y = 0f
                while (y < size.height) {
                    drawRect(Color.White.copy(alpha = 0.035f), Offset(0f, y), Size(size.width, 1f))
                    y += step
                }
            }
            .fairyHudCorners()
            .clickable(enabled = !booted, interactionSource = null, indication = null) { typed = total }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(text, fontFamily = HddMonoFamily, fontSize = 10.5.sp, lineHeight = 15.sp, softWrap = false)
        if (!booted) {
            Text(
                text = "TAP TO SKIP",
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                color = tokens.textMuted.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.End).padding(top = 6.dp),
            )
        }
    }
}

/** Mono terminal text: comments muted, keywords in [keyword], a block cursor at the end. */
private fun terminalText(lines: List<String>, comment: Color, keyword: Color, ready: Boolean): AnnotatedString =
    buildAnnotatedString {
        val body = Color(0xFFD6E2FA)
        lines.forEachIndexed { i, line ->
            if (i > 0) append('\n')
            when {
                ready -> withStyle(SpanStyle(color = if (line.endsWith("CREDENTIALS")) keyword else body)) { append(line) }
                line.startsWith("//") -> withStyle(SpanStyle(color = comment)) { append(line) }
                else -> {
                    val start = length
                    withStyle(SpanStyle(color = body)) { append(line) }
                    HddKeywords.findAll(line).forEach {
                        addStyle(SpanStyle(color = keyword, fontWeight = FontWeight.Bold), it.range.first + start, it.range.last + 1 + start)
                    }
                }
            }
        }
        withStyle(SpanStyle(color = keyword)) { append(" █") }
    }

/**
 * The app already ships JetBrains Mono; the boot log reads as a terminal in
 * it rather than pulling a second mono face into the apk.
 */
val HddMonoFamily: FontFamily = FontFamily(
    androidx.compose.ui.text.font.Font(R.font.jetbrains_mono),
)
