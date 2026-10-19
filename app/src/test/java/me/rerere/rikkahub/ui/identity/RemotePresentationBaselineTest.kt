package me.rerere.rikkahub.ui.identity

import java.io.File
import me.rerere.ui.markdown.parseMarkdown
import org.junit.Assert.assertEquals
import org.junit.Test

/** Host parsing baseline only; neither Android frames nor a device performance acceptance test. */
class RemotePresentationBaselineTest {
    /**
     * The one fixed workload asset. It lives in the perf source set because only the measurement
     * build ships it; this test reads the same file from the repository, so the host baseline and
     * the device workload can never drift apart.
     */
    @Test fun `record fixed complete document parsing baseline`() {
        val unit = File("src/perf/assets/presentation/workload.md").readText()
        val source = List(64) { unit }.joinToString("\n")
        repeat(2) { parseMarkdown(source) }
        val timings = List(7) {
            val started = System.nanoTime()
            val document = parseMarkdown(source)
            assertEquals(source, document.source)
            (System.nanoTime() - started) / 1_000_000.0
        }.sorted()
        println("PRESENTATION_HOST_BASELINE chars=${source.length} samples=${timings.joinToString(",")} medianMs=${timings[3]} p95Ms=${timings[6]}")
    }
}
