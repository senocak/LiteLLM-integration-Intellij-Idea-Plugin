package com.github.senocak.autocommit.chat

import com.github.senocak.autocommit.http.ChatTurn

enum class Role { USER, ASSISTANT }

/** The latest provider request outcome, shown independently from the conversation bubbles. */
sealed interface ProviderStatus {
    data object Ready : ProviderStatus
    data object Sending : ProviderStatus
    data class Succeeded(val elapsedMillis: Long) : ProviderStatus
    data class Failed(val message: String) : ProviderStatus
    data class Stopped(val elapsedMillis: Long) : ProviderStatus
}

/** A selectable, in-memory conversation retained by the project chat service. */
data class ChatConversationSummary(
    val id: Long,
    val title: String,
    val active: Boolean,
)

/**
 * One message in the transcript.
 *
 * [content] is mutable on the assistant side only in the sense that the service replaces the
 * message as tokens arrive; instances themselves stay immutable so the UI can compare the
 * previous and current text to decide what actually needs repainting.
 */
data class ChatMessage(
    val role: Role,
    val content: String,
    /** The model id requested for this turn; absent for user messages and old transcripts. */
    val model: String? = null,
    /** Model id returned by LiteLLM, which can differ when the gateway routes an alias. */
    val providerModel: String? = null,
    /** True while this message is still being streamed, so the UI can show a caret and a Stop. */
    val streaming: Boolean = false,
    /** Set when the request failed; rendered in place of the answer. */
    val error: String? = null,
)

/** The subset the gateway is sent: roles and text, with nothing about rendering state. */
fun List<ChatMessage>.toTurns(): List<ChatTurn> = filter { it.error == null && it.content.isNotBlank() }
    .map { ChatTurn(if (it.role == Role.USER) ChatTurn.USER else ChatTurn.ASSISTANT, it.content) }
