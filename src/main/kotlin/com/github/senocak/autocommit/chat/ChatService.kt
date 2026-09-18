package com.github.senocak.autocommit.chat

import com.github.senocak.autocommit.http.ChatClient
import com.github.senocak.autocommit.http.CommitMessageClient
import com.github.senocak.autocommit.settings.CustomCommitAiSettings
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owns one conversation per project: the transcript, the in-flight request, and the fan-out to
 * whichever panel is showing it.
 *
 * It lives in a service rather than in the panel for two reasons — the conversation survives the
 * tool window being hidden and rebuilt, and the editor right-click action has something to hand
 * an attached selection to without reaching for a Swing component.
 *
 * Every mutation of [messages] happens on the EDT, which is what makes the plain list safe here.
 */
@Service(Service.Level.PROJECT)
class ChatService(private val project: Project, private val scope: CoroutineScope) {
    interface Listener {
        /** The transcript changed. Always called on the EDT. */
        fun onTranscriptChanged(messages: List<ChatMessage>)

        /** Text the editor action wants placed in the input box. Always called on the EDT. */
        fun onInputRequested(text: String) = Unit

        /** The request state for the compact provider indicator in the tool-window toolbar. */
        fun onProviderStatusChanged(status: ProviderStatus) = Unit

        /** The available conversations and the one currently displayed. */
        fun onConversationsChanged(conversations: List<ChatConversationSummary>) = Unit
    }

    private class Conversation(val id: Long) {
        val messages = mutableListOf<ChatMessage>()
        var status: ProviderStatus = ProviderStatus.Ready
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val conversations = mutableListOf(Conversation(0))
    private var activeConversationId = 0L
    private var nextConversationId = 1L
    private val activeConversation: Conversation get() = conversations.first { it.id == activeConversationId }
    private val messages: MutableList<ChatMessage> get() = activeConversation.messages
    private var job: Job? = null
    /** Separates a freshly cleared conversation from callbacks queued by its predecessor. */
    private var generation = 0
    private val providerStatus: ProviderStatus get() = activeConversation.status

    val transcript: List<ChatMessage> get() = messages.toList()
    val conversationSummaries: List<ChatConversationSummary>
        get() = conversations.map { conversation ->
            ChatConversationSummary(conversation.id, conversationTitle(conversation), conversation.id == activeConversationId)
        }

    val busy: Boolean get() = job?.isActive == true

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onTranscriptChanged(transcript)
        listener.onProviderStatusChanged(providerStatus)
        listener.onConversationsChanged(conversationSummaries)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * Sends [text] as a new user turn.
     *
     * @param model the id chosen in the panel's picker. Blank falls back to the configured
     *   commit model, which is the chat picker's initial selection.
     */
    fun send(text: String, model: String) {
        if (busy || text.isBlank()) return

        messages += ChatMessage(Role.USER, text.trim())
        messages += ChatMessage(Role.ASSISTANT, "", streaming = true)
        val index = messages.lastIndex
        val history = messages.dropLast(1).toTurns()
        val requestGeneration = generation
        publish()
        publishConversations()
        setProviderStatus(ProviderStatus.Sending)

        job = scope.launch {
            val startedAt = System.nanoTime()
            // Resolving the configuration reads the API key out of Password Safe, which must not
            // happen on the EDT — the platform flags it as a slow operation.
            val configuration = withContext(Dispatchers.IO) { CustomCommitAiSettings.getInstance().configuration() }
            val chosen = model.ifBlank { configuration.effectiveChatModel }
            val problem = "LiteLLM Integration: API base URL is not configured."
                .takeIf { configuration.apiUrl.isBlank() }
                ?: "LiteLLM Integration: No chat model is configured.".takeIf { chosen.isBlank() }
            if (problem != null) {
                withContext(NonCancellable + Dispatchers.EDT) {
                    markFailed(requestGeneration, index, problem)
                    setProviderStatus(ProviderStatus.Failed(problem), requestGeneration)
                }
                return@launch
            }

            // The picker can change immediately after Send. Store the value resolved for this
            // request on the reply itself, so old and new model answers remain distinguishable.
            withContext(Dispatchers.EDT) { setResponseModel(requestGeneration, index, chosen) }

            LOG.info("LiteLLM Integration: chat request in '${project.name}' (model=$chosen, turns=${history.size}).")
            val answer = StringBuilder()
            var failure: String? = null
            var stopped = false
            // Coalesce tokens: a fast model emits them quicker than Swing can lay out an HTML
            // pane, and repainting per token makes the panel stutter and fight the scrollbar.
            var lastFlush = 0L
            try {
                withContext(Dispatchers.IO) {
                    ChatClient.send(
                        configuration, chosen, history,
                        shouldContinue = { isActive },
                        onDelta = { delta ->
                            answer.append(delta)
                            val now = System.currentTimeMillis()
                            if (now - lastFlush >= FLUSH_INTERVAL_MS) {
                                lastFlush = now
                                val snapshot = answer.toString()
                                scope.launch(Dispatchers.EDT) { appendStreamed(requestGeneration, index, snapshot) }
                            }
                        },
                        onProviderModel = { providerModel ->
                            scope.launch(Dispatchers.EDT) {
                                setProviderModel(requestGeneration, index, providerModel)
                            }
                        }
                    )
                }
            } catch (_: CancellationException) {
                // Stop was pressed, or the project is closing. Deliberately swallowed: the
                // partial answer is worth keeping, and the finaliser below has to run to clear
                // the streaming flag.
                stopped = true
            } catch (exception: Exception) {
                failure = CommitMessageClient.userFacingMessage(exception)
                LOG.warn("LiteLLM Integration: chat request failed - $failure", exception)
            }

            // NonCancellable so Stop still leaves the transcript in a settled state rather than
            // a message stuck mid-stream.
            withContext(NonCancellable + Dispatchers.EDT) {
                val elapsedMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND
                when {
                    stopped -> {
                        settle(requestGeneration, index, answer.toString())
                        setProviderStatus(ProviderStatus.Stopped(elapsedMillis), requestGeneration)
                    }
                    failure != null -> {
                        if (answer.isEmpty()) markFailed(requestGeneration, index, failure)
                        else settle(requestGeneration, index, answer.toString())
                        setProviderStatus(ProviderStatus.Failed(failure), requestGeneration)
                    }
                    else -> {
                        settle(requestGeneration, index, answer.toString())
                        setProviderStatus(ProviderStatus.Succeeded(elapsedMillis), requestGeneration)
                    }
                }
            }
        }
    }

    /** Abandons the in-flight response, keeping whatever has arrived so far. */
    fun stop() {
        job?.cancel()
    }

    /** Starts a blank conversation while retaining the current one in this project's chat history. */
    fun newConversation() {
        abandonActiveRequest()
        val conversation = Conversation(nextConversationId++)
        conversations += conversation
        activeConversationId = conversation.id
        publish()
        setProviderStatus(ProviderStatus.Ready)
        publishConversations()
    }

    /** Displays a previous conversation without discarding the one currently on screen. */
    fun selectConversation(id: Long) {
        if (id == activeConversationId || conversations.none { it.id == id }) return
        abandonActiveRequest()
        activeConversationId = id
        publish()
        setProviderStatus(providerStatus)
        publishConversations()
    }

    /** Removes every message from the current chat but keeps its entry in the Chats menu. */
    fun clearCurrentConversation() {
        abandonActiveRequest()
        messages.clear()
        activeConversation.status = ProviderStatus.Ready
        publish()
        setProviderStatus(ProviderStatus.Ready)
        publishConversations()
    }

    /** Removes the current chat from history and displays a neighbouring chat, or a blank one. */
    fun deleteCurrentConversation() {
        abandonActiveRequest()
        val index = conversations.indexOfFirst { it.id == activeConversationId }
        if (index < 0) return
        conversations.removeAt(index)
        if (conversations.isEmpty()) {
            val conversation = Conversation(nextConversationId++)
            conversations += conversation
            activeConversationId = conversation.id
        } else {
            activeConversationId = conversations.getOrElse(index) { conversations.last() }.id
        }
        publish()
        setProviderStatus(providerStatus)
        publishConversations()
    }

    private fun abandonActiveRequest() {
        // A cancelled HTTP read may not return until the gateway emits the next frame. Bump the
        // generation so its queued callbacks cannot rewrite whichever conversation is shown next.
        generation++
        val last = messages.lastOrNull()
        if (last?.streaming == true) messages[messages.lastIndex] = last.copy(streaming = false)
        job?.cancel()
        job = null
        if (last?.streaming == true) activeConversation.status = ProviderStatus.Stopped(0)
    }

    /** Used by the editor action to put an attached selection into the panel's input box. */
    fun requestInput(text: String) {
        scope.launch(Dispatchers.EDT) { listeners.forEach { it.onInputRequested(text) } }
    }

    /**
     * A throttled flush. Guarded on the message still being in flight: these are queued from the
     * IO thread and can land after the response has already settled, which would otherwise
     * rewind a finished answer to an earlier partial one.
     */
    private fun appendStreamed(requestGeneration: Int, index: Int, content: String) {
        if (requestGeneration != generation) return
        val current = messages.getOrNull(index) ?: return
        if (!current.streaming) return
        messages[index] = current.copy(content = content)
        publish()
    }

    private fun settle(requestGeneration: Int, index: Int, content: String) {
        if (requestGeneration != generation) return
        val current = messages.getOrNull(index) ?: return   // cleared while the response was in flight
        messages[index] = current.copy(content = content, streaming = false)
        publish()
    }

    private fun setResponseModel(requestGeneration: Int, index: Int, model: String) {
        if (requestGeneration != generation) return
        val current = messages.getOrNull(index) ?: return
        messages[index] = current.copy(model = model)
        publish()
    }

    private fun setProviderModel(requestGeneration: Int, index: Int, model: String) {
        if (requestGeneration != generation) return
        val current = messages.getOrNull(index) ?: return
        messages[index] = current.copy(providerModel = model)
        publish()
    }

    private fun markFailed(requestGeneration: Int, index: Int, reason: String) {
        if (requestGeneration != generation) return
        val current = messages.getOrNull(index) ?: return
        messages[index] = current.copy(streaming = false, error = reason)
        publish()
    }

    private fun publish() {
        val snapshot = transcript
        listeners.forEach { it.onTranscriptChanged(snapshot) }
    }

    private fun publishConversations() {
        val snapshot = conversationSummaries
        listeners.forEach { it.onConversationsChanged(snapshot) }
    }

    private fun setProviderStatus(status: ProviderStatus, requestGeneration: Int? = null) {
        if (requestGeneration != null && requestGeneration != generation) return
        activeConversation.status = status
        listeners.forEach { it.onProviderStatusChanged(status) }
    }

    private fun conversationTitle(conversation: Conversation): String {
        val firstQuestion = conversation.messages.firstOrNull { it.role == Role.USER }?.content
            ?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        return firstQuestion.takeIf { it.isNotBlank() }?.take(48)?.let {
            if (firstQuestion.length > it.length) "$it…" else it
        } ?: "New chat"
    }

    companion object {
        /** ~16 repaints a second: fast enough to read as streaming, slow enough not to thrash. */
        private const val FLUSH_INTERVAL_MS = 60L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private val LOG = Logger.getInstance(ChatService::class.java)

        fun getInstance(project: Project): ChatService = project.service()
    }
}
