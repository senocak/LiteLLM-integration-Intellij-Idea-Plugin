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

data class ApiConfiguration(
    val apiUrl: String,
    val model: String,
    val apiKey: String,
    val systemPrompt: String,
) {
    fun validationError(): String? = when {
        apiUrl.isBlank() -> "Custom Commit AI: API URL is not configured."
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
    }

    fun configuration(): ApiConfiguration = ApiConfiguration(
        state.apiUrl.trim(),
        state.model.trim(),
        PasswordSafe.instance.get(CREDENTIAL_ATTRIBUTES)?.getPasswordAsString().orEmpty(),
        state.systemPrompt.trim(),
    )

    fun update(apiUrl: String, model: String, apiKey: String, systemPrompt: String) {
        state.apiUrl = apiUrl.trim()
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
