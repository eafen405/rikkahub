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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.data.remote.RemoteChatSnapshot
import me.rerere.rikkahub.data.remote.RemoteChatState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.rememberUserSettingsState
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import org.json.JSONObject

/**
 * Release-shaped measurement activity.
 *
 * It drives the production receive state ([RemoteChatState]) and the production transcript, fed by
 * a replayable synthetic backend, so a measurement includes projection merge, message ordering, row
 * construction, Markdown parsing and text layout rather than only the renderer. It exists only in
 * the `perf` variant; the shipping release build does not contain it.
 */
class PresentationBenchmarkActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val frameNanos = mutableListOf<Long>()

    /** Backend emit to the receive state publishing the snapshot. */
    private val stateNanos = mutableListOf<Long>()

    /** Backend emit to the first frame drawn after that snapshot was committed by Compose. */
    private val frameLatencyNanos = mutableListOf<Long>()

    private var observed = 0
    private var latestApplied = -1L
    private var startHeap = 0L
    private var terminalRichMs = -1.0

    /** Completed once the terminal rich document has been drawn, or its wait has expired. */
    private val terminalPresented = CompletableDeferred<Boolean>()

    /** Completed once the workload window may open: after the first content is on screen. */
    private val windowOpen = CompletableDeferred<Unit>()

    private val frames = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        frameNanos += metrics.getMetric(FrameMetrics.TOTAL_DURATION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addOnFrameMetricsAvailableListener(frames, Handler(Looper.getMainLooper()))
        val scenario = intent.getStringExtra(SCENARIO) ?: "stream"
        val runId = intent.getStringExtra(RUN_ID)
        val unit = assets.open(PresentationWorkload.WORKLOAD_FILE).bufferedReader().use { it.readText() }
        val workload = PresentationWorkload.of(scenario, unit)
        val backend = SyntheticChatBackend(workload, unit)
        val state = RemoteChatState(
            backend = backend,
            scope = scope,
            store = InMemoryChatSyncStore(),
            endpoint = "http://perf.invalid",
            retryDelayMillis = 3_600_000,
        )
        setContent {
            var summary by remember { mutableStateOf("运行中，可上下滚动。") }
            val settings by rememberUserSettingsState()
            val navigator = remember { Navigator(mutableListOf()) }
            CompositionLocalProvider(LocalSettings provides settings, LocalNavController provides navigator) {
                RikkahubTheme {
                    val snapshot by state.snapshot.collectAsStateWithLifecycle()

                    // Watch every snapshot the receive state publishes. A version is counted once;
                    // the frame after it is the presentation of that exact version.
                    LaunchedEffect(Unit) {
                        var lastVersion = -1L
                        state.snapshot.collect { value ->
                            val projection = value.projection ?: return@collect
                            if (projection.version == lastVersion) return@collect
                            lastVersion = projection.version
                            val emitted = backend.emittedAt(projection.version) ?: return@collect
                            // The snapshot flow is conflated, so a version skipped here was superseded
                            // before it could be presented; only what gets a frame counts as observed.
                            observed++
                            stateNanos += SystemClock.elapsedRealtimeNanos() - emitted
                            latestApplied = projection.version
                            withFrameNanos { }
                            frameLatencyNanos += SystemClock.elapsedRealtimeNanos() - emitted
                        }
                    }

                    LaunchedEffect(Unit) {
                        // Let the activity window draw once, then clear startup frames so the
                        // workload window contains only frames this workload caused.
                        withFrameNanos { }
                        delay(STARTUP_SETTLE_MILLIS)
                        frameNanos.clear()
                        stateNanos.clear()
                        frameLatencyNanos.clear()
                        observed = 0
                        startHeap = usedHeap()
                        windowOpen.complete(Unit)
                        state.bind("perf-token", backend.sessionId, 1)
                    }

                    LaunchedEffect(Unit) {
                        windowOpen.await()
                        backend.awaitCompletion()
                        // The history workload carries no reply, so there is no terminal document to
                        // wait for; waiting would park the idle transcript inside the window. The
                        // other workloads wait for their terminal document to reach a frame, and a
                        // deadline that expires is reported as a miss, never as a small number.
                        if (workload.replyChars > 0) {
                            withTimeoutOrNull(TERMINAL_TIMEOUT_MILLIS) { terminalPresented.await() }
                        } else {
                            // Let the rendered history settle so the frame sample covers its layout.
                            delay(HISTORY_SETTLE_MILLIS)
                        }
                        summary = writeResult(scenario, workload, backend, state.snapshot.value, runId)
                        if (runId != null) finish()
                    }

                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("呈现测量 / $scenario", style = MaterialTheme.typography.titleMedium)
                            Text(summary, style = MaterialTheme.typography.bodySmall)
                            RemoteTranscript(
                                messages = snapshot.projection?.messages.orEmpty(),
                                attemptText = snapshot.projection?.attempt?.text,
                                attemptKey = snapshot.projection?.let { it.generation to it.attempt?.attemptId },
                                downloadEnabled = false,
                                onDownload = { _, _ -> },
                                settledAttempt = snapshot.projection?.let { projection ->
                                    projection.settledAttempt?.let {
                                        attemptRowKey(projection.generation, it.attemptId) to it.seq
                                    }
                                },
                                onFinalPresented = {
                                    if (!terminalPresented.isCompleted && backend.terminalEmitNanos > 0) {
                                        terminalRichMs =
                                            (SystemClock.elapsedRealtimeNanos() - backend.terminalEmitNanos) / 1_000_000.0
                                        terminalPresented.complete(true)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun writeResult(
        scenario: String,
        workload: PresentationWorkload,
        backend: SyntheticChatBackend,
        snapshot: RemoteChatSnapshot,
        runId: String?,
    ): String {
        val refreshBudget = 1_000_000_000.0 / windowManager.defaultDisplay.refreshRate
        val emitted = backend.emitCount
        // The newest snapshot is applied when the version the receive state committed is the last
        // one the backend emitted. Anything else is a backlog, not a skip count.
        val newestApplied = emitted > 0 && latestApplied == emitted.toLong()
        val result = JSONObject().apply {
            put("scenario", scenario)
            put("runId", runId)
            put("buildType", "perf")
            put("historyMessages", workload.historyMessages)
            put("replyChars", workload.replyChars)
            put("chunkChars", workload.chunkChars)
            put("intervalMillis", workload.intervalMillis)
            put("received", emitted)
            put("observed", observed)
            put("superseded", emitted - observed)
            put("latestVersionApplied", newestApplied)
            put("latestAppliedVersion", latestApplied)
            put("latestEmittedVersion", emitted)
            put("unappliedAtEnd", if (newestApplied) 0 else 1)
            put("terminalPresented", if (workload.replyChars > 0) terminalRichMs >= 0 else null)
            put("terminalRichPresentedMs", if (workload.replyChars > 0) terminalRichMs else null)
            put("receiveToStateP50Ms", percentile(stateNanos, 0.50))
            put("receiveToStateP95Ms", percentile(stateNanos, 0.95))
            put("receiveToFrameP50Ms", percentile(frameLatencyNanos, 0.50))
            put("receiveToFrameP95Ms", percentile(frameLatencyNanos, 0.95))
            put("frameP95Ms", percentile(frameNanos, 0.95))
            put("frameCount", frameNanos.size)
            put("overRefreshBudgetRatio", frameNanos.count { it > refreshBudget }.toDouble() / frameNanos.size.coerceAtLeast(1))
            put("consumedThrough", snapshot.consumedThrough)
            put("initialized", snapshot.initialized)
            put("failure", snapshot.failure)
            put("heapStartBytes", startHeap)
            put("heapEndBytes", usedHeap())
        }
        // One file per run: a later run of the same scenario must never be mistaken for this one,
        // and a reader on the device does not have to parse the log ring.
        val name = if (runId == null) "presentation-$scenario.json" else "presentation-$scenario-$runId.json"
        val file = java.io.File(filesDir, name)
        file.writeText(result.toString(2))
        Log.i(LOG_TAG, result.toString())
        return "完成：${file.name}；同步写入 logcat $LOG_TAG。"
    }

    override fun onDestroy() {
        window.removeOnFrameMetricsAvailableListener(frames)
        scope.cancel()
        super.onDestroy()
    }

    private fun usedHeap(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    private fun percentile(values: List<Long>, quantile: Double): Double =
        if (values.isEmpty()) 0.0 else values.sorted()[((values.size - 1) * quantile).toInt()] / 1_000_000.0

    private companion object {
        const val LOG_TAG = "OurFairyPresentation"
        const val SCENARIO = "scenario"
        const val RUN_ID = "runId"
        const val STARTUP_SETTLE_MILLIS = 500L

        /**
         * How long a terminal document may take to reach the screen before it is reported as not
         * presented. It is a reporting deadline, not a performance claim.
         */
        const val TERMINAL_TIMEOUT_MILLIS = 60_000L

        /** How long the reply-less history workload is given to lay out before it reports. */
        const val HISTORY_SETTLE_MILLIS = 2_000L
    }
}
