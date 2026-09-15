package com.github.senocak.autocommit.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

private const val DEFAULT_PROMPT = """Generate a concise Git commit message from the provided Git diff.

Rules:
Return only the commit message.
Do not use Markdown code fences.
Do not add explanations.
Prefer Conventional Commits format when appropriate.
Keep the subject concise."""

private val ENDPOINT_SUFFIXES = listOf("/chat/completions", "/messages", "/responses", "/completions")

/**
 * Reduces whatever was typed to the gateway root that every endpoint hangs off.
 *
 * The plugin knows only that it is talking to a LiteLLM-compatible gateway, never which one,
 * so it derives `/models`, `/chat/completions` and `/messages` itself rather than being told
 * one of them. A trailing endpoint segment is dropped, and `/v1` is added only when no path
 * was given at all. Idempotent, so it is safe to apply to an already-normalised value — which
 * is what quietly migrates settings saved back when this field held a full endpoint URL.
 */
internal fun normalizeBaseUrl(url: String): String {
    val trimmed = url.trim().trimEnd('/')
    if (trimmed.isEmpty()) return trimmed
    val withoutEndpoint = ENDPOINT_SUFFIXES
        .firstOrNull { trimmed.endsWith(it, ignoreCase = true) }
        ?.let { trimmed.dropLast(it.length) }
        ?: trimmed
    val base = withoutEndpoint.trimEnd('/')
    // "https://host" has no path to hang /models off; "https://host/anything" might be a
    // gateway mounted on a sub-path, so it is left exactly as given.
    return if (base.substringAfter("://", "").contains('/')) base else "$base/v1"
}

data class ApiConfiguration(
    val apiUrl: String,
    val model: String,
    val apiKey: String,
    val systemPrompt: String,
) {
    val baseUrl: String get() = normalizeBaseUrl(apiUrl)

    val modelsUrl: String get() = "$baseUrl/models"
    val chatUrl: String get() = "$baseUrl/chat/completions"
    val messagesUrl: String get() = "$baseUrl/messages"

    fun validationError(): String? = when {
        apiUrl.isBlank() -> "Custom Commit AI: API base URL is not configured."
        model.isBlank() -> "Custom Commit AI: Model is not configured."
        else -> null
    }
}

@State(name = "CustomCommitAiSettings", storages = [Storage("customCommitAi.xml")])
@Service(Service.Level.APP)
class CustomCommitAiSettings : PersistentStateComponent<CustomCommitAiSettings.State> {
    class State {
        var apiUrl: String = ""
        var model: String = ""
        var systemPrompt: String = DEFAULT_PROMPT
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
        // Settings written before this field became a base URL hold a full endpoint such as
        // ".../v1/messages". Normalising on load migrates them in place, so the Settings page
        // shows the base the plugin actually uses rather than a path it ignores.
        this.state.apiUrl = normalizeBaseUrl(this.state.apiUrl)
    }

    fun configuration(): ApiConfiguration = ApiConfiguration(
        normalizeBaseUrl(state.apiUrl),
        state.model.trim(),
        PasswordSafe.instance.get(CREDENTIAL_ATTRIBUTES)?.getPasswordAsString().orEmpty(),
        state.systemPrompt.trim(),
    )

    fun update(apiUrl: String, model: String, apiKey: String, systemPrompt: String) {
        state.apiUrl = normalizeBaseUrl(apiUrl)
        state.model = model.trim()
        state.systemPrompt = systemPrompt.trim().ifBlank { DEFAULT_PROMPT }
        PasswordSafe.instance.set(
            CREDENTIAL_ATTRIBUTES,
            apiKey.trim().takeIf { it.isNotEmpty() }?.let { Credentials(CREDENTIAL_USER, it) }
        )
    }

    companion object {
        private const val CREDENTIAL_USER = "api-key"
        private val CREDENTIAL_ATTRIBUTES = CredentialAttributes(
            generateServiceName("Custom Commit AI", "API Key"),
            CREDENTIAL_USER,
        )

        fun getInstance(): CustomCommitAiSettings = ApplicationManager.getApplication().getService(CustomCommitAiSettings::class.java)
    }
}
