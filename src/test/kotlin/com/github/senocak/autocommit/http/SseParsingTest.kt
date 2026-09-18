package com.github.senocak.autocommit.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * A stream is mostly lines that are not answer text: heartbeats, blank separators, a first chunk
 * that only announces the role. Treating any of those as a failure would abort a working response,
 * so each one has to be recognised and skipped.
 */
class SseParsingTest {
    @Test
    fun `a content delta is extracted`() {
        val line = """data: {"choices":[{"delta":{"content":"Hello"}}]}"""
        assertEquals(SseEvent.Delta("Hello"), parseSseLine(line))
    }

    @Test
    fun `the done sentinel ends the stream`() {
        assertEquals(SseEvent.Done, parseSseLine("data: [DONE]"))
    }

    @Test
    fun `blank lines and comments are skipped`() {
        assertEquals(SseEvent.Ignore, parseSseLine(""))
        assertEquals(SseEvent.Ignore, parseSseLine("   "))
        // Gateways send comment lines as keep-alives on an idle connection.
        assertEquals(SseEvent.Ignore, parseSseLine(": ping"))
    }

    @Test
    fun `the role-only opening chunk carries no text`() {
        val line = """data: {"choices":[{"delta":{"role":"assistant"}}]}"""
        assertEquals(SseEvent.Ignore, parseSseLine(line))
    }

    @Test
    fun `an explicit null content is not text`() {
        val line = """data: {"choices":[{"delta":{"content":null}}]}"""
        assertEquals(SseEvent.Ignore, parseSseLine(line))
    }

    @Test
    fun `a truncated or malformed frame is skipped rather than fatal`() {
        assertEquals(SseEvent.Ignore, parseSseLine("""data: {"choices":[{"delta":"""))
        assertEquals(SseEvent.Ignore, parseSseLine("event: message"))
    }

    @Test
    fun `the final usage-only chunk carries no text`() {
        val line = """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}"""
        assertEquals(SseEvent.Ignore, parseSseLine(line))
    }

    @Test
    fun `whitespace in the content is preserved`() {
        // Indentation inside a streamed code block arrives as its own delta; trimming it here
        // would flatten every snippet against the margin.
        val line = """data: {"choices":[{"delta":{"content":"    indented"}}]}"""
        assertEquals(SseEvent.Delta("    indented"), parseSseLine(line))
    }

    @Test
    fun `an error delivered mid-stream is raised rather than swallowed`() {
        // A gateway that fails after the 200 reports it in a data frame, not an HTTP status.
        val line = """data: {"error":{"message":"rate limit exceeded"}}"""
        val thrown = assertThrows(ApiException::class.java) { parseSseLine(line) }
        assertEquals("rate limit exceeded", thrown.message)
    }
}
