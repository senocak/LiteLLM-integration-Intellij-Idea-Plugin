package com.github.senocak.autocommit.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The plugin is told a URL and nothing else — it has to work out where /models and the
 * generation endpoint live. Settings saved before the model dropdown existed hold a full
 * ".../v1/messages", so that shape has to keep working without a migration.
 */
class ApiConfigurationTest {
    private fun base(url: String) = ApiConfiguration(url, "m", "k", "p").baseUrl

    @Test
    fun `endpoint suffixes are reduced to the gateway base`() {
        val expected = "https://host/v1"
        assertEquals(expected, base("https://host/v1/messages"))
        assertEquals(expected, base("https://host/v1/chat/completions"))
        assertEquals(expected, base("https://host/v1/responses"))
        assertEquals(expected, base("https://host/v1"))
    }

    @Test
    fun `trailing slashes and whitespace do not change the base`() {
        assertEquals("https://host/v1", base("  https://host/v1/messages/  "))
        assertEquals("https://host/v1", base("https://host/v1/"))
    }

    @Test
    fun `a bare host gains the v1 prefix`() {
        assertEquals("https://host/v1", base("https://host"))
        assertEquals("https://host/v1", base("https://host/"))
    }

    @Test
    fun `a gateway on a sub-path is left alone`() {
        // Could be LiteLLM mounted behind a path prefix; inventing /v1 there would break it.
        assertEquals("https://host/llm", base("https://host/llm"))
        assertEquals("https://host/llm/v1", base("https://host/llm/v1/messages"))
    }

    @Test
    fun `derived endpoints hang off the base`() {
        val configuration = ApiConfiguration("https://host/v1/messages", "m", "k", "p")
        assertEquals("https://host/v1/models", configuration.modelsUrl)
        assertEquals("https://host/v1/chat/completions", configuration.chatUrl)
        assertEquals("https://host/v1/messages", configuration.messagesUrl)
    }

    @Test
    fun `an empty url yields an empty base rather than a bogus one`() {
        assertEquals("", base(""))
        assertEquals("", base("   "))
    }

    @Test
    fun `normalisation is idempotent so it can run on every load and save`() {
        // This is what migrates an old full-endpoint setting in place: the value is normalised
        // on load and again on save, and must not drift on the second pass.
        listOf("https://host/v1/messages", "https://host", "https://host/llm/v1", "").forEach {
            val once = normalizeBaseUrl(it)
            assertEquals("stable for '$it'", once, normalizeBaseUrl(once))
        }
    }
}
