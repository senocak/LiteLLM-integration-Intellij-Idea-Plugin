package com.github.senocak.autocommit.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transcript re-parses a reply on every flush while it streams, so the partial states matter
 * as much as the finished one. These are the shapes that actually arrive mid-response.
 */
class MarkdownBlocksTest {
    @Test
    fun `plain prose is a single block`() {
        assertEquals(listOf(Block.Prose("Just some text.")), parseBlocks("Just some text."))
    }

    @Test
    fun `a fenced block is separated from the prose around it`() {
        val blocks = parseBlocks("Before\n```kotlin\nval x = 1\n```\nAfter")
        assertEquals(
            listOf(
                Block.Prose("Before"),
                Block.Code("kotlin", "val x = 1", closed = true),
                Block.Prose("After"),
            ),
            blocks,
        )
    }

    @Test
    fun `an unterminated fence is still a code block`() {
        // The streaming case: the model has opened a fence and is still writing inside it. If this
        // came back as prose the block would flip styles the moment the closing fence arrived.
        val blocks = parseBlocks("Here:\n```python\ndef f():")
        assertEquals(
            listOf(Block.Prose("Here:"), Block.Code("python", "def f():", closed = false)),
            blocks,
        )
    }

    @Test
    fun `a fence with no language tag is still code`() {
        assertEquals(listOf(Block.Code("", "plain", closed = true)), parseBlocks("```\nplain\n```"))
    }

    @Test
    fun `a longer fence survives backticks in the body`() {
        // This is what an attached selection containing Markdown looks like.
        val blocks = parseBlocks("````md\nuse ```kotlin here\n````")
        assertEquals(listOf(Block.Code("md", "use ```kotlin here", closed = true)), blocks)
    }

    @Test
    fun `an empty code block does not swallow the rest of the message`() {
        val blocks = parseBlocks("```js\n```\ndone")
        assertEquals(listOf(Block.Code("js", "", closed = true), Block.Prose("done")), blocks)
    }

    @Test
    fun `html in prose is escaped rather than rendered`() {
        // Model output is untrusted markup as far as a JEditorPane is concerned.
        val html = inlineHtml("<script>alert(1)</script>")
        assertTrue(html, html.contains("&lt;script&gt;"))
        assertTrue(html, !html.contains("<script>"))
    }

    @Test
    fun `inline marks become tags`() {
        assertTrue(inlineHtml("**bold**").contains("<b>bold</b>"))
        assertTrue(inlineHtml("call `foo()` now").contains("<code>foo()</code>"))
        assertTrue(inlineHtml("- one").contains("<li>one</li>"))
    }

    @Test
    fun `emphasis marks inside inline code stay literal`() {
        // Without the placeholder pass, `a*b*c` would come back with an italic in the middle of
        // what is supposed to be verbatim code.
        val html = inlineHtml("`a*b*c`")
        assertTrue(html, html.contains("<code>a*b*c</code>"))
        assertTrue(html, !html.contains("<i>"))
    }
}
