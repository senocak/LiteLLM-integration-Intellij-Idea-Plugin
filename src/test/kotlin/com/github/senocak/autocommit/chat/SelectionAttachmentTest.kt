package com.github.senocak.autocommit.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionAttachmentTest {
    @Test
    fun `a multi-line selection carries its file and range`() {
        val attached = fencedSelection("Foo.kt", 12, 30, "kt", "fun foo() = bar()")
        assertEquals("Foo.kt:12-30\n```kt\nfun foo() = bar()\n```", attached)
    }

    @Test
    fun `a single-line selection names one line, not a range`() {
        val attached = fencedSelection("Foo.kt", 7, 7, "kt", "val x = 1")
        assertTrue(attached, attached.startsWith("Foo.kt:7\n"))
    }

    @Test
    fun `a selection containing a fence gets a longer wrapper`() {
        // Three backticks inside the code would close a three-backtick wrapper early, and the
        // model would read the remainder as prose.
        val attached = fencedSelection("README.md", 1, 3, "md", "example:\n```kotlin\nx\n```")
        assertTrue(attached, attached.contains("````md\n"))
        assertTrue(attached, attached.endsWith("\n````"))
        assertTrue(attached, attached.contains("```kotlin"))
    }

    @Test
    fun `an unknown language leaves the tag empty rather than guessing`() {
        val attached = fencedSelection("notes", 1, 2, "", "hello")
        assertEquals("notes:1-2\n```\nhello\n```", attached)
    }

    @Test
    fun `trailing blank lines in the selection are dropped`() {
        val attached = fencedSelection("Foo.kt", 1, 2, "kt", "val x = 1\n\n\n")
        assertEquals("Foo.kt:1-2\n```kt\nval x = 1\n```", attached)
    }
}
