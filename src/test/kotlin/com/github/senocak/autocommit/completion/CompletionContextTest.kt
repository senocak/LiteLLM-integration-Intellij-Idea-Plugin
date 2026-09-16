package com.github.senocak.autocommit.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionContextTest {
    private fun ctx(text: String, offset: Int) = caretContext(text, offset, "kotlin", "Foo.kt")

    @Test
    fun `splits the file at the caret`() {
        val c = ctx("val a = 1\nval b = ", 18)
        assertEquals("val a = 1\nval b = ", c.prefix)
        assertEquals("", c.suffix)
    }

    @Test
    fun `keeps what follows the caret as the suffix`() {
        val c = ctx("fun f() {\n\n}\n", 10)
        assertEquals("fun f() {\n", c.prefix)
        assertEquals("\n}\n", c.suffix)
    }

    @Test
    fun `an offset outside the document is clamped rather than throwing`() {
        assertEquals("abc", ctx("abc", 999).prefix)
        assertEquals("abc", ctx("abc", -5).suffix)
    }

    @Test
    fun `an empty file produces an empty context`() {
        assertTrue(ctx("", 0).isEmpty)
    }

    @Test
    fun `a long prefix is trimmed on a line boundary, never mid-line`() {
        val text = (1..500).joinToString("\n") { "line$it" }
        val prefix = ctx(text, text.length).prefix
        // Cutting mid-identifier would read as a syntax error to the model.
        assertTrue("starts mid-line: ${prefix.take(20)}", prefix.startsWith("line"))
        assertTrue("kept too many lines", prefix.lines().size <= 61)
        assertTrue("lost the caret's own line", prefix.endsWith("line500"))
    }

    @Test
    fun `a single enormous line is capped by characters`() {
        val huge = "x".repeat(50_000)
        assertTrue(ctx(huge, huge.length).prefix.length <= 6_000)
    }

    @Test
    fun `the suffix is bounded too`() {
        val text = (1..500).joinToString("\n") { "line$it" }
        val suffix = ctx(text, 0).suffix
        assertTrue("kept too many lines", suffix.lines().size <= 21)
    }

    @Test
    fun `CRLF files keep their carriage returns intact`() {
        // Stripping them would change the text the model is asked to continue.
        val c = ctx("a\r\nb\r\n", 3)
        assertEquals("a\r\n", c.prefix)
    }
}
