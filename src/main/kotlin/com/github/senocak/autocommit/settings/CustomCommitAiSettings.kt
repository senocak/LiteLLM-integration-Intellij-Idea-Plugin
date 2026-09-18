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

/** Inline-completion settings that carry no secret, so they cost nothing to read on the EDT. */
data class CompletionPreferences(
    val baseUrl: String,
    val model: String,
    val enabled: Boolean,
) {
    /** Everything except the key; the key is checked later, off the EDT. */
    val configured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()
}

/** Chat's non-secret settings, safe to read while constructing the tool-window UI on the EDT. */
data class ChatPreferences(
    val baseUrl: String,
    val model: String,
)

data class ApiConfiguration(
    val apiUrl: String,
    val model: String,
    val apiKey: String,
    val systemPrompt: String,
    /**
     * Separate from [model] because the two have opposite priorities: a commit message is worth
     * waiting seconds for, an inline suggestion is not. Blank means completion is unconfigured.
     */
    val completionModel: String = "",
    val completionEnabled: Boolean = true,
) {
    /** Everything inline completion needs before it is worth sending a request. */
    val completionReady: Boolean
        get() = apiUrl.isNotBlank() && apiKey.isNotBlank() && completionModel.isNotBlank()

    /** The chat toolbar starts with the commit model; a chat can choose another model there. */
    val effectiveChatModel: String get() = model

    val baseUrl: String get() = normalizeBaseUrl(apiUrl)

    val modelsUrl: String get() = "$baseUrl/models"
    val chatUrl: String get() = "$baseUrl/chat/completions"
    val messagesUrl: String get() = "$baseUrl/messages"

    fun validationError(): String? = when {
        apiUrl.isBlank() -> "LiteLLM Integration: API base URL is not configured."
        model.isBlank() -> "LiteLLM Integration: Model is not configured."
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
        var completionModel: String = ""
        var completionEnabled: Boolean = true
    }

    private var state = State()

    /**
     * Password Safe reads are flagged as slow operations on the EDT, and inline completion asks
     * whether it is enabled on every keystroke. Caching the key keeps that off the hot path; it
     * is only ever written through [update], which invalidates it.
     */
    @Volatile
    private var cachedApiKey: String? = null

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
        apiKey(),
        state.systemPrompt.trim(),
        state.completionModel.trim(),
        state.completionEnabled,
    )

    /**
     * The settings inline completion can check without touching the credential store — cheap
     * enough to call on the EDT for every keystroke, which is exactly what happens.
     */
    fun completionPreferences(): CompletionPreferences = CompletionPreferences(
        baseUrl = normalizeBaseUrl(state.apiUrl),
        model = state.completionModel.trim(),
        enabled = state.completionEnabled,
    )

    /** The model that should be visible before the asynchronous `/models` request finishes. */
    fun chatPreferences(): ChatPreferences = ChatPreferences(
        baseUrl = normalizeBaseUrl(state.apiUrl),
        model = state.model.trim(),
    )

    private fun apiKey(): String = cachedApiKey ?: PasswordSafe.instance
        .get(CREDENTIAL_ATTRIBUTES)?.getPasswordAsString().orEmpty()
        .also { cachedApiKey = it }

    fun update(
        apiUrl: String,
        model: String,
        apiKey: String,
        systemPrompt: String,
        completionModel: String,
        completionEnabled: Boolean,
    ) {
        state.apiUrl = normalizeBaseUrl(apiUrl)
        state.model = model.trim()
        state.systemPrompt = systemPrompt.trim().ifBlank { DEFAULT_PROMPT }
        state.completionModel = completionModel.trim()
        state.completionEnabled = completionEnabled
        cachedApiKey = apiKey.trim()
        PasswordSafe.instance.set(
            CREDENTIAL_ATTRIBUTES,
            apiKey.trim().takeIf { it.isNotEmpty() }?.let { Credentials(CREDENTIAL_USER, it) }
        )
    }

    companion object {
        private const val CREDENTIAL_USER = "api-key"

        // Deliberately still the old product name: this string is the Password Safe lookup key.
        // Renaming it to match the plugin would point at an empty entry and silently lose the
        // stored API key.
        private val CREDENTIAL_ATTRIBUTES = CredentialAttributes(
            generateServiceName("Custom Commit AI", "API Key"),
            CREDENTIAL_USER,
        )

        fun getInstance(): CustomCommitAiSettings = ApplicationManager.getApplication().getService(CustomCommitAiSettings::class.java)
    }
}
