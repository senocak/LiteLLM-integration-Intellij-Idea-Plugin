package com.github.senocak.autocommit.http

import com.github.senocak.autocommit.completion.CaretContext
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A bad suggestion here is not a wrong answer in a chat window — it is text pasted into the
 * user's file the moment they press Tab. These are the model misbehaviours seen in practice.
 */
class CodeCompletionSanitizeTest {
    private fun context(prefix: String, suffix: String = "") =
        CaretContext(prefix, suffix, "kotlin", "Foo.kt")

    @Test
    fun `plain output passes through`() {
        assertEquals("a + b", sanitize("a + b", context("val sum = ")))
    }

    @Test
    fun `code fences are stripped`() {
        assertEquals("a + b", sanitize("```kotlin\na + b\n```", context("val sum = ")))
    }

    @Test
    fun `an echoed current line is not duplicated`() {
        // Without this, accepting would produce "val sum = val sum = a + b".
        assertEquals("a + b", sanitize("val sum = a + b", context("val sum = ")))
    }

    @Test
    fun `a prose preamble is dropped`() {
        assertEquals("a + b", sanitize("Here is the completion:\na + b", context("val sum = ")))
    }

    @Test
    fun `code lines ending in a colon are not mistaken for prose`() {
        // Regression: a "line ends with a colon" rule deleted the first line of every Python
        // block. Caught by running a real gpt-5.4-mini reply through this function.
        val python = CaretContext("def fib(n):\n", "", "Python", "u.py")
        val out = sanitize("    if n < 0:\n        raise ValueError(\"neg\")\n    return 0", python)
        assertEquals("    if n < 0:\n        raise ValueError(\"neg\")\n    return 0", out)

        assertEquals("    for x in xs:", sanitize("    for x in xs:", python))
        assertEquals("else:", sanitize("else:", python))
    }

    @Test
    fun `a mid-line completion never spans lines`() {
        // Ghost text rendered over the rest of an expression is unreadable.
        assertEquals("a + b", sanitize("a + b\nprintln(sum)\n", context("val sum = ")))
    }

    @Test
    fun `at the start of a line a block is kept but stops at a blank line`() {
        val out = sanitize("println(1)\nprintln(2)\n\nunrelated()", context("fun f() {\n    "))
        assertEquals("println(1)\nprintln(2)", out)
    }

    @Test
    fun `a suggestion identical to what already follows is discarded`() {
        assertEquals("", sanitize("}", context("fun f() {\n", suffix = "}\n")))
    }

    @Test
    fun `a trailing brace the file already has is dropped`() {
        // Observed live: completing a function body, gpt-5.4-mini returned "    return name\n}"
        // for a file whose next line is already "}". Accepting verbatim leaves a stray brace.
        val out = sanitize("    return name\n}", context("fun User.displayName(): String {\n", suffix = "}\n"))
        assertEquals("    return name", out)
    }

    @Test
    fun `a closing brace is kept when the file does not already have one`() {
        val out = sanitize("    return name\n}", context("fun User.displayName(): String {\n", suffix = ""))
        assertEquals("    return name\n}", out)
    }

    @Test
    fun `an empty or whitespace reply yields nothing`() {
        assertEquals("", sanitize("", context("val x = ")))
        assertEquals("", sanitize("   \n  ", context("val x = ")))
    }

    @Test
    fun `a bare fence with no language tag still unwraps`() {
        assertEquals("a + b", sanitize("```\na + b\n```", context("val sum = ")))
    }

    @Test
    fun `leading indentation survives`() {
        // Trimming the whole reply — correct for a commit message — would paste the suggestion
        // flush against the margin.
        val out = sanitize("```kotlin\n    return name\n```", context("fun f(): String {\n"))
        assertEquals("    return name", out)
        assertEquals("        deep()", sanitize("        deep()", context("if (x) {\n")))
    }
}
