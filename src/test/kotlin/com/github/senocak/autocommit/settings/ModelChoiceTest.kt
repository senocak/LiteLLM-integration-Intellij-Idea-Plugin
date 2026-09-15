package com.github.senocak.autocommit.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

private val CLAUDE_KEY_MODELS = listOf("claude-haiku-4-5-20251001", "claude-opus-5", "claude-sonnet-5", "glm")
private val CODEX_KEY_MODELS = listOf("gpt-5.4", "gpt-5.6-terra", "gpt-6-astra", "kimi-k3")

class ModelChoiceTest {
    @Test
    fun `a listed selection survives a reload`() {
        val (items, chosen) = modelChoice(CLAUDE_KEY_MODELS, "claude-opus-5", allowUnlisted = false)
        assertEquals(CLAUDE_KEY_MODELS, items)
        assertEquals("claude-opus-5", chosen)
    }

    @Test
    fun `swapping the key drops a model the new key cannot serve`() {
        // The regression: the codex key does not serve claude-opus-5, so it must not linger
        // in the list or stay selected — that would only 403 later at generation time.
        val (items, chosen) = modelChoice(CODEX_KEY_MODELS, "claude-opus-5", allowUnlisted = false)
        assertFalse("claude-opus-5" in items)
        assertEquals(CODEX_KEY_MODELS, items)
        assertEquals("gpt-5.4", chosen)
    }

    @Test
    fun `an unknown list keeps the saved model so a failed fetch loses nothing`() {
        val (items, chosen) = modelChoice(emptyList(), "claude-opus-5", allowUnlisted = true)
        assertEquals(listOf("claude-opus-5"), items)
        assertEquals("claude-opus-5", chosen)
    }

    @Test
    fun `a saved model the gateway did not list is still offered while the list is unknown`() {
        // The dropdown is selection-only, so an item missing from the list cannot be chosen
        // back by hand — it has to be carried into the model explicitly.
        val (items, chosen) = modelChoice(CODEX_KEY_MODELS, "some-unlisted-model", allowUnlisted = true)
        assertEquals("some-unlisted-model", chosen)
        assertEquals(CODEX_KEY_MODELS + "some-unlisted-model", items)
    }

    @Test
    fun `no selection falls back to the first model`() {
        assertEquals("gpt-5.4", modelChoice(CODEX_KEY_MODELS, "", allowUnlisted = false).second)
    }

    @Test
    fun `nothing anywhere yields an empty selection rather than a crash`() {
        val (items, chosen) = modelChoice(emptyList(), "", allowUnlisted = true)
        assertEquals(emptyList<String>(), items)
        assertEquals("", chosen)
    }
}
