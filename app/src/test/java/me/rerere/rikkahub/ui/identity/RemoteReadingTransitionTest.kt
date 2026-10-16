package me.rerere.rikkahub.ui.identity

import me.rerere.rikkahub.data.remote.*
import me.rerere.ui.markdown.*
import org.junit.Assert.*
import org.junit.Test

class RemoteReadingTransitionTest {
    private val source = "Reading [the ending][ref].\n\n```kotlin\nval final = 42\n```\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n\$\$x^2\$\$\n\n[ref]: https://example.com/end"
    private val terminal = RemoteChatMessage("durable", 9, role = 2, status = 3, text = source)

    @Test fun `reader keeps same raw layout through terminal and gets parsed complete body after leaving`() {
        val state = RemoteTranscriptPresentation()
        val live = state.update(emptyList(), source, "live", emptySet(), true).single()
        val settled = state.update(listOf(terminal), null, null, setOf(live.key), false, live.key to terminal.seq).single()
        assertEquals(live.key, settled.key)
        assertEquals(live.text, settled.text)
        assertTrue(settled.deferFormatting)
        val parsed = parseMarkdown(settled.text)
        assertNull(settled.documentToShow(parsed))
        val released = state.update(listOf(terminal), null, null, emptySet(), false, "live" to terminal.seq).single()
        assertSame(parsed, released.documentToShow(parsed))
        assertTrue(parsed.blocks.any { it is MarkdownBlock.CodeBlock })
        assertTrue(parsed.blocks.any { it is MarkdownBlock.Table })
        assertTrue(parsed.blocks.filterIsInstance<MarkdownBlock.Paragraph>().any { it.content.any { node -> node is MarkdownInline.Math && node.display } })
        assertEquals(1, state.update(listOf(terminal), null, null, emptySet(), false, "live" to terminal.seq).size)
    }

    @Test fun `return to tail releases pending formatting while history never waits for scrolling`() {
        val state = RemoteTranscriptPresentation()
        val live = state.update(emptyList(), source, "live", emptySet(), true).single()
        assertTrue(state.update(listOf(terminal), null, null, setOf(live.key), false, live.key to terminal.seq).single().deferFormatting)
        val resumed = state.update(listOf(terminal), null, null, setOf(live.key), true, live.key to terminal.seq).single()
        assertFalse(resumed.deferFormatting)
        assertFalse(state.update(listOf(terminal), null, null, setOf(live.key), false, live.key to terminal.seq).single().deferFormatting)
        val history = RemoteTranscriptPresentation().update(listOf(terminal), null, null, setOf("durable"), false).single()
        assertFalse(history.deferFormatting)
        assertEquals("durable", history.key)
    }
    @Test fun `formal reply before stream end and unrelated delivery retain exactly one settled reading row`() {
        val state = RemoteTranscriptPresentation()
        val old = RemoteChatMessage("old", 1, role = 2, status = 2, text = "Earlier")
        val live = state.update(listOf(old), source, "live", emptySet(), true).last()
        val delivery = RemoteChatMessage("delivery", 8, role = 2, status = 2, text = "Independent delivery")
        val overlap = state.update(listOf(old, delivery, terminal), source, "live", setOf(live.key), false)
        assertEquals(listOf("old", "delivery", "durable", "live"), overlap.map { it.key })
        val rows = state.update(listOf(old, delivery, terminal), null, null, setOf(live.key), false, live.key to terminal.seq)
        assertEquals(listOf("old", "delivery", "live"), rows.map { it.key })
        assertTrue(rows.last().deferFormatting)
        assertEquals(source, rows.last().text)
    }

    @Test fun `stopped and failed terminal bodies retain final increment and release full formatting`() {
        for (status in listOf(3, 4)) {
            val state = RemoteTranscriptPresentation()
            val live = state.update(emptyList(), source.dropLast(8), "live-$status", emptySet(), true).single()
            val rows = state.update(listOf(terminal.copy(status = status)), null, null, setOf(live.key), false, live.key to terminal.seq)
            assertEquals(source, rows.single().text)
            assertNull(rows.single().documentToShow(parseMarkdown(source)))
            val released = state.update(listOf(terminal.copy(status = status)), null, null, setOf(live.key), true, live.key to terminal.seq).single()
            assertEquals(source, released.documentToShow(parseMarkdown(source))!!.source)
        }
    }

    @Test fun `same text delivery and new live attempt are never identified by content`() {
        val state = RemoteTranscriptPresentation()
        state.update(emptyList(), "", "generation:first", emptySet(), true)
        val delivery = terminal.copy(messageId = "delivery", seq = 8)
        val live = state.update(listOf(delivery), source, "generation:first", setOf("generation:first"), false)
        assertEquals(listOf("delivery", "generation:first"), live.map { it.key })
        val next = state.update(listOf(delivery, terminal), "next", "generation:second", setOf("generation:first"), false,
            "generation:first" to terminal.seq)
        assertEquals(listOf("delivery", "generation:first", "generation:second"), next.map { it.key })
        assertTrue(next[1].deferFormatting)
        assertFalse(next[0].deferFormatting)
        val history = RemoteTranscriptPresentation().update(listOf(delivery, terminal), null, null,
            setOf("durable"), false, "generation:first" to terminal.seq)
        assertEquals(listOf("delivery", "durable"), history.map { it.key })
        assertTrue(history.none { it.deferFormatting })
    }

}
