package me.rerere.rikkahub.ui.identity

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.rememberUserSettingsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import me.rerere.rikkahub.data.remote.RemoteChatMessage
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import org.json.JSONObject

/** Offline synthetic workloads using the production transcript/body renderer, available in release APKs. */
class PresentationBenchmarkActivity : ComponentActivity() {
    private val frameNanos = mutableListOf<Long>()
    private val latencyNanos = mutableListOf<Long>()
    private var applied = 0
    private var received = 0
    private var lastApplied = 0
    private var pending = 0
    private val frames = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        frameNanos += metrics.getMetric(FrameMetrics.TOTAL_DURATION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addOnFrameMetricsAvailableListener(frames, Handler(Looper.getMainLooper()))
        val scenario = intent.getStringExtra("scenario") ?: "stream"
        val unit = assets.open("presentation/workload.md").bufferedReader().use { it.readText() }
        val longText = List(64) { unit }.joinToString("\n")
        val history = List(200) { index ->
            RemoteChatMessage("history-$index", index.toLong(), role = if (index % 2 == 0) 1 else 2,
                status = 2, text = "历史 ${index + 1}\n" + unit.take(240))
        }
        setContent {
            val settings by rememberUserSettingsState()
            val navigator = remember { Navigator(mutableListOf()) }
            CompositionLocalProvider(LocalSettings provides settings, LocalNavController provides navigator) { RikkahubTheme {
                var messages by remember { mutableStateOf(if (scenario == "history") history else emptyList()) }
                var attempt by remember { mutableStateOf<String?>(null) }
                var revision by remember { mutableStateOf(0) }
                var receivedAt by remember { mutableStateOf(0L) }
                val terminalReady = remember { CompletableDeferred<Unit>() }
                val terminalTiming = remember { doubleArrayOf(0.0, 0.0) }
                var summary by remember { mutableStateOf("运行中，可上下滚动。") }
                val displayedRevision = revision
                val displayedReceivedAt = receivedAt
                LaunchedEffect(displayedRevision) {
                    if (displayedRevision == 0) return@LaunchedEffect
                    withFrameNanos { }
                    latencyNanos += SystemClock.elapsedRealtimeNanos() - displayedReceivedAt
                    applied++
                    lastApplied = displayedRevision
                    pending = received - lastApplied
                }
                LaunchedEffect(scenario) {
                    delay(500)
                    frameNanos.clear() // exclude startup/initial composition from the workload window.
                    val startHeap = usedHeap()
                    fun accept(text: String?, final: Boolean = false) {
                        receivedAt = SystemClock.elapsedRealtimeNanos()
                        received++
                        revision = received
                        attempt = text.takeUnless { final }
                        if (final) { terminalTiming[0] = receivedAt.toDouble(); messages = listOf(RemoteChatMessage("final", 201, role = 2, status = 2, text = text.orEmpty())) }
                    }
                    when (scenario) {
                        "history" -> accept(null)
                        "single" -> accept(longText, final = true)
                        "format" -> accept(unit, final = true)
                        "burst" -> {
                            // One reconnect batch: complete cumulative snapshots, then a terminal-only change.
                            for (end in 128..longText.length step 128) accept(longText.take(end))
                            accept(longText)
                            accept(longText, final = true)
                        }
                        else -> {
                            for (end in 128..longText.length step 128) {
                                accept(longText.take(end))
                                delay(20)
                            }
                            accept(longText)
                            delay(20)
                            accept(longText, final = true)
                        }
                    }
                    if (scenario != "history") terminalReady.await()
                    delay(1000) // capture a settled tail after terminal layout.
                    val result = JSONObject().apply {
                        put("scenario", scenario)
                        put("runId", intent.getStringExtra("runId"))
                        put("sourceUtf16Chars", longText.length)
                        put("received", received)
                        put("applied", applied)
                        put("superseded", received - applied)
                        put("unappliedVersionsAtEnd", pending)
                        put("terminalRichPresentedMs", terminalTiming[1])
                        put("latestPendingSnapshots", if (pending == 0) 0 else 1)
                        put("receiveToNextFrameP50Ms", percentile(latencyNanos, 0.50))
                        put("receiveToNextFrameP95Ms", percentile(latencyNanos, 0.95))
                        put("frameP95Ms", percentile(frameNanos, 0.95))
                        put("frameCount", frameNanos.size)
                        val refreshBudget = 1_000_000_000.0 / (display?.refreshRate ?: 60f)
                        put("overRefreshBudgetRatio", frameNanos.count { it > refreshBudget }.toDouble() / frameNanos.size.coerceAtLeast(1))
                        put("heapStartBytes", startHeap)
                        put("heapEndBytes", usedHeap())
                    }
                    val file = java.io.File(filesDir, "presentation-$scenario.json")
                    file.writeText(result.toString(2))
                    summary = "完成：${file.name}；结果同步写入 logcat OurFairyPresentation。"
                    Log.i("OurFairyPresentation", result.toString())
                    if (intent.hasExtra("runId")) finish()
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("呈现测量 / $scenario", style = MaterialTheme.typography.titleMedium)
                        Text(summary, style = MaterialTheme.typography.bodySmall)
                        RemoteTranscript(messages, attempt, "benchmark" to "tail", false,
                            onDownload = { _, _ -> },
                            onFinalPresented = {
                                terminalTiming[1] = (SystemClock.elapsedRealtimeNanos() - terminalTiming[0]) / 1_000_000.0
                                terminalReady.complete(Unit)
                            },
                        )
                    }
                }
            }
        } }
    }

    override fun onDestroy() {
        window.removeOnFrameMetricsAvailableListener(frames)
        super.onDestroy()
    }

    private fun usedHeap(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    private fun percentile(values: List<Long>, quantile: Double): Double =
        if (values.isEmpty()) 0.0 else values.sorted()[((values.size - 1) * quantile).toInt()] / 1_000_000.0
}
