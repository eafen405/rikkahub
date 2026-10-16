package me.rerere.rikkahub.ui.identity

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.remote.RemoteMessageFormat
import me.rerere.ui.markdown.MarkdownBlock
import me.rerere.ui.markdown.parseMarkdown
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemotePresentationTest {
    @Test fun `terminal only change parses entire source including reference definitions`() = runTest {
        val raw = "See [end][ref].\n\n```kotlin\nval x = 1\n```\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n\$\$x^2\$\$\n\n[ref]: https://example.com/final"
        val input = MutableStateFlow(RemoteBodyInput(raw, RemoteMessageFormat.PLAIN))
        val output = mutableListOf<RemoteBodyPresentation>()
        backgroundScope.launch { remoteBodyPresentations(input) { parseMarkdown(it) }.collect { output += it } }
        runCurrent()
        assertEquals(raw, output.last().text)
        assertNull(output.last().document)
        input.value = RemoteBodyInput(raw, RemoteMessageFormat.MARKDOWN)
        runCurrent()
        val document = output.last().document!!
        assertEquals(raw, document.source)
        assertTrue(document.blocks.any { it is MarkdownBlock.CodeBlock })
        assertTrue(document.blocks.any { it is MarkdownBlock.Table })
        assertTrue(document.blocks.filterIsInstance<MarkdownBlock.Paragraph>().any { paragraph -> paragraph.content.any { it is me.rerere.ui.markdown.MarkdownInline.Math && it.display } })
        val paragraph = document.blocks.first() as MarkdownBlock.Paragraph
        assertTrue(paragraph.content.any { it is me.rerere.ui.markdown.MarkdownInline.Link && it.destination == "https://example.com/final" })
    }

    @Test fun `late parse cannot replace latest terminal text and previous body stays readable`() = runTest {
        val blocked = CompletableDeferred<Unit>()
        val input = MutableStateFlow(RemoteBodyInput("old", RemoteMessageFormat.MARKDOWN))
        val output = mutableListOf<RemoteBodyPresentation>()
        backgroundScope.launch {
            remoteBodyPresentations(input) { text ->
                if (text == "old") blocked.await()
                parseMarkdown(text)
            }.collect { output += it }
        }
        runCurrent()
        assertEquals("old", output.last().text)
        input.value = RemoteBodyInput("last increment **kept**", RemoteMessageFormat.MARKDOWN)
        runCurrent()
        blocked.complete(Unit)
        runCurrent()
        assertEquals("last increment **kept**", output.last().document!!.source)
        assertTrue(output.none { it.document?.source == "old" })
        assertTrue(output.none { it.text.isEmpty() })
    }

    @Test fun `reconnect burst displays latest full snapshot without replaying obsolete snapshots`() = runTest {
        val input = MutableStateFlow(RemoteBodyInput("", RemoteMessageFormat.PLAIN))
        val output = mutableListOf<RemoteBodyPresentation>()
        backgroundScope.launch { remoteBodyPresentations(input) { parseMarkdown(it) }.collect { output += it } }
        runCurrent()
        repeat(512) { input.value = RemoteBodyInput("chunk-".repeat(it + 1), RemoteMessageFormat.PLAIN) }
        input.value = RemoteBodyInput("FINAL **whole** text", RemoteMessageFormat.MARKDOWN)
        runCurrent()
        assertEquals("FINAL **whole** text", output.last().document!!.source)
        assertEquals(listOf("", "FINAL **whole** text", "FINAL **whole** text"), output.map { it.text })
    }

    @Test fun `reading anchor follows same message after history prepends and never requests tail during user scroll`() {
        val reading = RemoteReadingPosition(followTail = false, key = "m2", index = 1, offset = 137)
        assertEquals(RemoteScrollTarget(3, 137), reading.target(listOf("older1", "older2", "m1", "m2", "m3")))
        assertNull(reading.copy(userScrolling = true).target(listOf("m1", "m2", "m3")))
        assertEquals(RemoteScrollTarget(2, Int.MAX_VALUE), RemoteReadingPosition().target(listOf("m1", "m2", "m3")))
    }
}
