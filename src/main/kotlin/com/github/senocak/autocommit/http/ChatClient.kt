package com.github.senocak.autocommit.http

import com.github.senocak.autocommit.settings.ApiConfiguration
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.HttpRequests
import java.util.concurrent.ConcurrentHashMap

/**
 * Conversational calls for the chat tool window.
 *
 * Differs from the other two clients in the two ways that matter: it sends the whole conversation
 * rather than one prompt, and it streams, because a chat answer is long enough that waiting for
 * the last token before showing the first is the difference between usable and not.
 */
object ChatClient {
    /** Room for a real explanation with code in it, unlike the 2k a commit subject needs. */
    private const val MAX_OUTPUT_TOKENS = 4_096

    /** Generous on purpose: a long answer from a reasoning model legitimately takes minutes. */
    private const val READ_TIMEOUT_MS = 120_000

    private val LOG = Logger.getInstance(ChatClient::class.java)

    private val SYSTEM_PROMPT = """
        You are a concise coding assistant embedded in the IntelliJ IDEA editor.

        Rules:
        Answer the question directly; skip preamble and restating the question.
        Put every piece of code in a Markdown fence tagged with its language.
        Keep prose short — the answer is read in a narrow side panel.
        When the user attaches code, assume the question is about that code.
    """.trimIndent()

    /**
     * Whether each gateway turned out to support streaming, so a gateway without it is probed
     * once per base URL per IDE session rather than on every message. Mirrors the dialect cache
     * in [CommitMessageClient].
     */
    private val streamingSupported = ConcurrentHashMap<String, Boolean>()

    /**
     * Sends [history] and returns the complete reply, calling [onDelta] with each fragment as it
     * arrives.
     *
     * @param shouldContinue polled between fragments; returning false abandons the response and
     *   returns whatever has arrived so far. This is how Stop works — the partial answer is kept
     *   rather than thrown away.
     */
    fun send(
        configuration: ApiConfiguration,
        model: String,
        history: List<ChatTurn>,
        shouldContinue: () -> Boolean,
        onDelta: (String) -> Unit,
        onProviderModel: (String) -> Unit = {},
    ): String {
        if (streamingSupported[configuration.baseUrl] == false) {
            return buffered(configuration, model, history, onDelta, onProviderModel)
        }

        val answer = StringBuilder()
        try {
            val payload = GatewayClient.chatPayload(model, SYSTEM_PROMPT, history, MAX_OUTPUT_TOKENS, stream = true)
            LOG.info("LiteLLM Integration: streaming chat request to ${configuration.chatUrl} (model=$model, turns=${history.size}).")
            GatewayClient.postStreaming(configuration, configuration.chatUrl, payload, READ_TIMEOUT_MS) { line ->
                if (!shouldContinue()) return@postStreaming false
                when (val event = parseSseLine(line)) {
                    is SseEvent.Delta -> {
                        event.model?.let(onProviderModel)
                        answer.append(event.text)
                        onDelta(event.text)
                        true
                    }
                    is SseEvent.Model -> {
                        onProviderModel(event.model)
                        true
                    }
                    SseEvent.Done -> false
                    SseEvent.Ignore -> true
                }
            }
        } catch (exception: HttpRequests.HttpStatusException) {
            // 404/405 means no chat route at all; 400 is how a gateway that serves the route but
            // refuses `stream: true` usually says so. Both are worth one buffered retry — but only
            // if nothing has been shown yet, or the retry would duplicate text already on screen.
            val worthRetrying = exception.statusCode in RETRYABLE_STATUSES && answer.isEmpty()
            if (!worthRetrying) {
                LOG.warn("LiteLLM Integration: chat request returned HTTP ${exception.statusCode}.")
                throw ApiException("API returned HTTP ${exception.statusCode}.")
            }
            LOG.info("LiteLLM Integration: streaming rejected (HTTP ${exception.statusCode}); falling back to a buffered reply.")
            streamingSupported[configuration.baseUrl] = false
            return buffered(configuration, model, history, onDelta, onProviderModel)
        }

        // A stream that closed without producing a single token is not a working stream, whatever
        // the status code said. Retry buffered once and remember, so this costs one round trip.
        if (answer.isEmpty() && shouldContinue() && streamingSupported[configuration.baseUrl] == null) {
            LOG.info("LiteLLM Integration: stream produced no content; falling back to a buffered reply.")
            streamingSupported[configuration.baseUrl] = false
            return buffered(configuration, model, history, onDelta, onProviderModel)
        }

        streamingSupported.putIfAbsent(configuration.baseUrl, true)
        return answer.toString()
    }

    /** The non-streaming route, delivered to [onDelta] as a single fragment. */
    private fun buffered(
        configuration: ApiConfiguration,
        model: String,
        history: List<ChatTurn>,
        onDelta: (String) -> Unit,
        onProviderModel: (String) -> Unit,
    ): String {
        val payload = GatewayClient.chatPayload(model, SYSTEM_PROMPT, history, MAX_OUTPUT_TOKENS, stream = false)
        val response = try {
            GatewayClient.post(configuration, configuration.chatUrl, payload, READ_TIMEOUT_MS)
        } catch (exception: HttpRequests.HttpStatusException) {
            LOG.warn("LiteLLM Integration: buffered chat request returned HTTP ${exception.statusCode}.")
            throw ApiException("API returned HTTP ${exception.statusCode}.")
        }

        val reply = extractReply(response)
        if (reply.content.isBlank()) throw ApiException("Model returned an empty response.")
        reply.model?.let(onProviderModel)
        onDelta(reply.content)
        return reply.content
    }

    private data class Reply(val content: String, val model: String?)

    private fun extractReply(response: String): Reply {
        val root = try {
            JsonParser.parseString(response).asJsonObject
        } catch (exception: Exception) {
            throw ApiException("API returned an invalid JSON response.", exception)
        }
        val content = root.getAsJsonArray("choices")
            ?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?.getAsJsonObject("message")
            ?.get("content")?.takeIf { it.isJsonPrimitive }?.asString
            .orEmpty()
        val model = root.get("model")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
        return Reply(content, model)
    }

    private val RETRYABLE_STATUSES = setOf(400, 404, 405)
}
