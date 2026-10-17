package me.rerere.rikkahub.ui.identity

import me.rerere.rikkahub.data.remote.*
import me.rerere.ui.markdown.*
import org.junit.Assert.*
import org.junit.Test

class RemoteReadingTransitionTest {
    private val source = "Reading [the ending][ref].\n\n```kotlin\nval final = 42\n```\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n\$\$x^2\$\$\n\n[ref]: https://example.com/end"
    private val terminal = RemoteChatMessage("durable", 9, role = 2, status = 3, text = source)

    @Test fun `attempt row key spells the generation scoped attempt convention`() {
        assertEquals("attempt:gen-7:attempt-2", attemptRowKey("gen-7", "attempt-2"))
    }

    @Test fun `reader keeps same raw layout through terminal and gets parsed complete body after leaving`() {
        val state = RemoteTranscriptPresentation()
        val liveKey = attemptRowKey("generation", "live")
        val live = state.update(emptyList(), source, liveKey, emptySet(), true).single()
        val settled = state.update(listOf(terminal), null, null, setOf(live.key), false, liveKey to terminal.seq).single()
        assertEquals(live.key, settled.key)
        assertEquals(live.text, settled.text)
        assertTrue(settled.deferFormatting)
        val parsed = parseMarkdown(settled.text)
        assertNull(settled.documentToShow(parsed))
        val released = state.update(listOf(terminal), null, null, emptySet(), false, liveKey to terminal.seq).single()
        assertSame(parsed, released.documentToShow(parsed))
        assertTrue(parsed.blocks.any { it is MarkdownBlock.CodeBlock })
        assertTrue(parsed.blocks.any { it is MarkdownBlock.Table })
        assertTrue(parsed.blocks.filterIsInstance<MarkdownBlock.Paragraph>().any { it.content.any { node -> node is MarkdownInline.Math && node.display } })
        assertEquals(1, state.update(listOf(terminal), null, null, emptySet(), false, "live" to terminal.seq).size)
    }

    @Test fun `return to tail releases pending formatting while history never waits for scrolling`() {
        val state = RemoteTranscriptPresentation()
        val liveKey = attemptRowKey("generation", "live")
        val live = state.update(emptyList(), source, liveKey, emptySet(), true).single()
        assertTrue(state.update(listOf(terminal), null, null, setOf(live.key), false, liveKey to terminal.seq).single().deferFormatting)
        val resumed = state.update(listOf(terminal), null, null, setOf(live.key), true, liveKey to terminal.seq).single()
        assertFalse(resumed.deferFormatting)
        assertFalse(state.update(listOf(terminal), null, null, setOf(live.key), false, liveKey to terminal.seq).single().deferFormatting)
        val history = RemoteTranscriptPresentation().update(listOf(terminal), null, null, setOf("durable"), false).single()
        assertFalse(history.deferFormatting)
        assertEquals("durable", history.key)
    }
    @Test fun `formal reply before stream end and unrelated delivery retain exactly one settled reading row`() {
        val state = RemoteTranscriptPresentation()
        val liveKey = attemptRowKey("generation", "live")
        val old = RemoteChatMessage("old", 1, role = 2, status = 2, text = "Earlier")
        val live = state.update(listOf(old), source, liveKey, emptySet(), true).last()
        val delivery = RemoteChatMessage("delivery", 8, role = 2, status = 2, text = "Independent delivery")
        val overlap = state.update(listOf(old, delivery, terminal), source, liveKey, setOf(live.key), false)
        assertEquals(listOf("old", "delivery", "durable", liveKey), overlap.map { it.key })
        val rows = state.update(listOf(old, delivery, terminal), null, null, setOf(live.key), false, liveKey to terminal.seq)
        assertEquals(listOf("old", "delivery", liveKey), rows.map { it.key })
        assertTrue(rows.last().deferFormatting)
        assertEquals(source, rows.last().text)
    }

    @Test fun `stopped and failed terminal bodies retain final increment and release full formatting`() {
        for (status in listOf(3, 4)) {
            val state = RemoteTranscriptPresentation()
            val liveKey = attemptRowKey("generation", "live-$status")
            val live = state.update(emptyList(), source.dropLast(8), liveKey, emptySet(), true).single()
            val rows = state.update(listOf(terminal.copy(status = status)), null, null, setOf(live.key), false, liveKey to terminal.seq)
            assertEquals(source, rows.single().text)
            assertNull(rows.single().documentToShow(parseMarkdown(source)))
            val released = state.update(listOf(terminal.copy(status = status)), null, null, setOf(live.key), true, liveKey to terminal.seq).single()
            assertEquals(source, released.documentToShow(parseMarkdown(source))!!.source)
        }
    }

    @Test fun `same text delivery and new live attempt are never identified by content`() {
        val state = RemoteTranscriptPresentation()
        val first = attemptRowKey("generation", "first")
        val second = attemptRowKey("generation", "second")
        state.update(emptyList(), "", first, emptySet(), true)
        val delivery = terminal.copy(messageId = "delivery", seq = 8)
        val live = state.update(listOf(delivery), source, first, setOf(first), false)
        assertEquals(listOf("delivery", first), live.map { it.key })
        val next = state.update(listOf(delivery, terminal), "next", second, setOf(first), false,
            first to terminal.seq)
        assertEquals(listOf("delivery", first, second), next.map { it.key })
        assertTrue(next[1].deferFormatting)
        assertFalse(next[0].deferFormatting)
        val history = RemoteTranscriptPresentation().update(listOf(delivery, terminal), null, null,
            setOf("durable"), false, first to terminal.seq)
        assertEquals(listOf("delivery", "durable"), history.map { it.key })
        assertTrue(history.none { it.deferFormatting })
    }

}
