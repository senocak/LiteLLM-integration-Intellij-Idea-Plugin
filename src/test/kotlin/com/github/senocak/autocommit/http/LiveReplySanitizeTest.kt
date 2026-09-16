package com.github.senocak.autocommit.http

import com.github.senocak.autocommit.completion.CaretContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replies captured verbatim from gpt-5.4-mini through the real completion prompt, run back
 * through the real [sanitize]. Guards the end of the pipeline that unit fixtures only guess at:
 * what the model actually says, not what we imagine it says.
 */
class LiveReplySanitizeTest {
    @Test
    fun `live reply 1 from gpt-5_4-mini is insertable`() {
        val out = sanitize("a + b\n", CaretContext("fun add(a: Int, b: Int): Int {\n    return ", "", "kotlin", "Foo.kt"))
        println("case 1 -> " + out.replace("\n", "\\n"))
        assertTrue("empty", out.isNotBlank())
        assertFalse("duplicated the caret line", out.startsWith(CaretContext("fun add(a: Int, b: Int): Int {\n    return ", "", "", "").prefix.substringAfterLast('\n').takeIf { it.isNotBlank() } ?: "\u0000"))
        val suffixHead = "".trimStart().substringBefore('\n').trim()
        if (suffixHead.isNotBlank()) assertFalse("re-closed a block the file already closes", out.trimEnd().endsWith("\n" + suffixHead))
    }

    @Test
    fun `live reply 2 from gpt-5_4-mini is insertable`() {
        val out = sanitize("    return name\n}", CaretContext("data class User(val id: Long, val name: String)\n\nfun User.displayName(): String {\n", "}\n", "kotlin", "User.kt"))
        println("case 2 -> " + out.replace("\n", "\\n"))
        assertTrue("empty", out.isNotBlank())
        assertFalse("duplicated the caret line", out.startsWith(CaretContext("data class User(val id: Long, val name: String)\n\nfun User.displayName(): String {\n", "", "", "").prefix.substringAfterLast('\n').takeIf { it.isNotBlank() } ?: "\u0000"))
        val suffixHead = "}\n".trimStart().substringBefore('\n').trim()
        if (suffixHead.isNotBlank()) assertFalse("re-closed a block the file already closes", out.trimEnd().endsWith("\n" + suffixHead))
    }

    @Test
    fun `live reply 3 from gpt-5_4-mini is insertable`() {
        val out = sanitize("    if n < 0:\n        raise ValueError(\"n must be non-negative\")\n    a, b = 0, 1\n    for _ in range(n):\n        a, b = b, a + b\n    return a", CaretContext("def fibonacci(n):\n    \"\"\"Return the nth Fibonacci number.\"\"\"\n", "", "Python", "util.py"))
        println("case 3 -> " + out.replace("\n", "\\n"))
        assertTrue("empty", out.isNotBlank())
        assertFalse("duplicated the caret line", out.startsWith(CaretContext("def fibonacci(n):\n    \"\"\"Return the nth Fibonacci number.\"\"\"\n", "", "", "").prefix.substringAfterLast('\n').takeIf { it.isNotBlank() } ?: "\u0000"))
        val suffixHead = "".trimStart().substringBefore('\n').trim()
        if (suffixHead.isNotBlank()) assertFalse("re-closed a block the file already closes", out.trimEnd().endsWith("\n" + suffixHead))
    }
}
