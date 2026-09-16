package com.github.senocak.autocommit.completion

import com.github.senocak.autocommit.http.CodeCompletionClient
import com.github.senocak.autocommit.http.CommitMessageClient
import com.github.senocak.autocommit.settings.CustomCommitAiSettings
import com.intellij.codeInsight.inline.completion.DebouncedInlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Copilot-style ghost text backed by the configured LiteLLM gateway.
 *
 * Extends [DebouncedInlineCompletionProvider] rather than debouncing by hand: the platform
 * cancels the in-flight coroutine as soon as the user types again, so a request is only ever
 * sent once typing actually pauses.
 *
 * Latency is the honest limitation here. A gateway round trip is roughly 1-3s against a fast
 * model and far worse against a reasoning one, where most of the budget is spent thinking before
 * a single token is emitted. Choosing a small, fast completion model matters more than any
 * tuning in this class.
 */
class LiteLlmInlineCompletionProvider : DebouncedInlineCompletionProvider() {
    override val id = InlineCompletionProviderID("LiteLlmInlineCompletionProvider")

    /**
     * Long enough that ordinary typing never triggers a request, short enough to feel like a
     * pause rather than a wait. The network round trip dwarfs this either way.
     *
     * An explicit invocation skips the wait entirely — the user has already decided.
     */
    override suspend fun getDebounceDelay(request: InlineCompletionRequest): Duration =
        if (request.event is InlineCompletionEvent.DirectCall) Duration.ZERO else 400.milliseconds

    /**
     * Called on the EDT for every typing event, so it deliberately reads only the non-secret
     * preferences: pulling the key from Password Safe here trips the platform's slow-operation
     * assertion and adds credential-store latency to every keystroke.
     */
    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        val preferences = CustomCommitAiSettings.getInstance().completionPreferences()
        if (!preferences.configured) return false
        // An explicit invocation is always honoured, so the shortcut still works with the
        // automatic suggestions switched off.
        return when (event) {
            is InlineCompletionEvent.DirectCall -> true
            else -> preferences.enabled
        }
    }

    override suspend fun getSuggestionDebounced(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val configuration = CustomCommitAiSettings.getInstance().configuration()
        if (!configuration.completionReady) {
            CompletionStatus.off()
            return InlineCompletionSuggestion.Empty
        }

        // Document and PsiFile may only be touched under a read action.
        val context = readAction {
            caretContext(
                text = request.document.text,
                offset = request.endOffset,
                language = request.file.language.id,
                path = request.file.name,
            )
        }
        if (context.isEmpty) return InlineCompletionSuggestion.Empty

        CompletionStatus.running()
        val startedAt = System.currentTimeMillis()
        val completion = try {
            // HttpRequests is blocking, so it must not run on the coroutine's default dispatcher.
            withContext(Dispatchers.IO) {
                CodeCompletionClient.complete(configuration, configuration.completionModel, context)
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // The user typed on. Not a failure, and reporting it as one would make the widget
            // flicker red through ordinary typing.
            CompletionStatus.done(System.currentTimeMillis() - startedAt, empty = true)
            throw cancellation
        } catch (exception: Exception) {
            val reason = CommitMessageClient.userFacingMessage(exception)
            // Still no balloon per keystroke — the status bar carries it instead.
            CompletionStatus.failed(System.currentTimeMillis() - startedAt, reason)
            LOG.warn("LiteLLM Integration: inline completion failed - $reason", exception)
            return InlineCompletionSuggestion.Empty
        }

        CompletionStatus.done(System.currentTimeMillis() - startedAt, empty = completion.isBlank())
        if (completion.isBlank()) return InlineCompletionSuggestion.Empty
        return InlineCompletionSingleSuggestion.build { emit(InlineCompletionGrayTextElement(completion)) }
    }

    private companion object {
        private val LOG = Logger.getInstance(LiteLlmInlineCompletionProvider::class.java)
    }
}
