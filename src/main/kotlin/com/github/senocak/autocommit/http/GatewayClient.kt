package com.github.senocak.autocommit.http

import com.github.senocak.autocommit.settings.ApiConfiguration
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.HttpRequests
import java.io.IOException

internal object HttpTimeouts {
    const val CONNECT_MS = 10_000
    const val READ_MS = 60_000
}

/** One turn of a conversation, in the only two roles the plugin ever sends. */
data class ChatTurn(val role: String, val content: String) {
    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
    }
}

/**
 * The bits every gateway call shares: the POST itself, the credential headers, and the tidying
 * models need afterwards. Commit-message generation and inline completion differ only in their
 * payload and their tolerance for latency, so everything else lives here rather than twice.
 */
internal object GatewayClient {
    private val LOG = Logger.getInstance(GatewayClient::class.java)

    /**
     * @throws HttpRequests.HttpStatusException on a non-2xx response — deliberately not wrapped,
     *   because callers branch on the status code (the chat/Anthropic fallback keys off 404/405).
     */
    fun post(
        configuration: ApiConfiguration,
        url: String,
        payload: JsonObject,
        readTimeoutMs: Int = HttpTimeouts.READ_MS,
        anthropicVersion: Boolean = false,
    ): String {
        try {
            return HttpRequests.post(url, "application/json")
                .connectTimeout(HttpTimeouts.CONNECT_MS)
                .readTimeout(readTimeoutMs)
                .tuner { connection ->
                    connection.setRequestProperty("Accept", "application/json")
                    // The key stays optional; with none configured, no credential header is sent.
                    // Both header styles are accepted by LiteLLM, and sending the one each dialect
                    // expects keeps a stricter upstream happy.
                    applyCredentials(connection, configuration)
                    if (anthropicVersion) connection.setRequestProperty("anthropic-version", "2023-06-01")
                }
                .connect { request ->
                    request.connection.outputStream.writer(Charsets.UTF_8).use { it.write(payload.toString()) }
                    request.readString()
                }
        } catch (exception: IOException) {
            if (exception is HttpRequests.HttpStatusException) throw exception
            LOG.warn("LiteLLM Integration: request to $url failed (${exception.javaClass.simpleName}).")
            throw ApiException("Failed to connect to the configured API.", exception)
        }
    }

    /**
     * POSTs and hands back each response line as it arrives, for Server-Sent Events.
     *
     * Two settings are load-bearing and easy to lose: gzip is switched off because a compressed
     * body is buffered by the decoder and delivered in lumps, which is exactly what streaming is
     * meant to avoid, and the Accept header must name the event stream or some gateways answer
     * with a single buffered JSON object instead.
     *
     * @param onLine receives every raw line, blank ones included. Returning false stops reading
     *   and closes the connection — that is how cancellation gets out of the loop.
     * @throws HttpRequests.HttpStatusException on a non-2xx response, unwrapped, so callers can
     *   branch on 404/405 to fall back to the buffered route.
     */
    fun postStreaming(
        configuration: ApiConfiguration,
        url: String,
        payload: JsonObject,
        readTimeoutMs: Int,
        onLine: (String) -> Boolean,
    ) {
        try {
            HttpRequests.post(url, "application/json")
                .connectTimeout(HttpTimeouts.CONNECT_MS)
                .readTimeout(readTimeoutMs)
                .gzip(false)
                .accept("text/event-stream")
                .tuner { connection -> applyCredentials(connection, configuration) }
                .connect { request ->
                    request.connection.outputStream.writer(Charsets.UTF_8).use { it.write(payload.toString()) }
                    val reader = request.reader
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (!onLine(line)) break
                    }
                }
        } catch (exception: IOException) {
            if (exception is HttpRequests.HttpStatusException) throw exception
            LOG.warn("LiteLLM Integration: streaming request to $url failed (${exception.javaClass.simpleName}).")
            throw ApiException("Failed to connect to the configured API.", exception)
        }
    }

    /**
     * Credential headers only. Accept is deliberately left to the caller: the streaming route
     * must ask for `text/event-stream`, and setting it here would overwrite that from the tuner,
     * which runs after the builder.
     */
    private fun applyCredentials(connection: java.net.URLConnection, configuration: ApiConfiguration) {
        if (configuration.apiKey.isNotBlank()) {
            connection.setRequestProperty("x-api-key", configuration.apiKey)
            connection.setRequestProperty("Authorization", "Bearer ${configuration.apiKey}")
        }
    }

    /**
     * Builds the OpenAI Chat Completions body. Two details are not interchangeable with the
     * Anthropic one: the cap must be `max_completion_tokens`, because some backends reject
     * `max_tokens` outright, and the system prompt must be a message — a top-level `system`
     * property is refused as an unpermitted extra input.
     */
    fun chatPayload(model: String, systemPrompt: String, userMessage: String, maxTokens: Int) =
        chatPayload(model, systemPrompt, listOf(ChatTurn(ChatTurn.USER, userMessage)), maxTokens, stream = false)

    /** The multi-turn form chat needs; the single-message overload above is the one-shot case of it. */
    fun chatPayload(
        model: String,
        systemPrompt: String,
        turns: List<ChatTurn>,
        maxTokens: Int,
        stream: Boolean,
    ) = JsonObject().apply {
        addProperty("model", model)
        addProperty("max_completion_tokens", maxTokens)
        addProperty("stream", stream)
        add("messages", JsonArray().apply {
            if (systemPrompt.isNotBlank()) {
                add(JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", systemPrompt)
                })
            }
            turns.forEach { turn ->
                add(JsonObject().apply {
                    addProperty("role", turn.role)
                    addProperty("content", turn.content)
                })
            }
        })
    }

    /** Models wrap output in fences despite being told not to; strip them without touching the rest. */
    fun stripFences(message: String): String {
        val trimmed = message.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed.removePrefix("```").removeSuffix("```").trim()
            .lineSequence()
            // A fence often opens with a bare language tag on its own line.
            .dropWhile { it.isBlank() || it.trim().matches(LANGUAGE_TAG) }
            .joinToString("\n")
            .trim()
    }

    private val LANGUAGE_TAG = Regex("^[A-Za-z0-9+#._-]{1,20}$")
}

/** What a single line of an SSE response turned out to mean. */
internal sealed interface SseEvent {
    /** A piece of the answer. Never blank — an empty delta is reported as [Ignore]. */
    data class Delta(val text: String, val model: String? = null) : SseEvent

    /** Some gateways announce the resolved model before they send any response text. */
    data class Model(val model: String) : SseEvent

    /** The `[DONE]` sentinel: the model has finished. */
    data object Done : SseEvent

    /** Heartbeat, blank separator, unrecognised field, or a chunk carrying no text. */
    data object Ignore : SseEvent
}

/**
 * Interprets one raw line of a Chat Completions event stream.
 *
 * Kept as a pure function over a string so the awkward cases can be tested without a gateway,
 * and there are several: comment lines used as keep-alives, a first chunk that announces only
 * `{"role":"assistant"}` with no content, an explicit `"content": null`, and — on an overloaded
 * gateway — a truncated line that is not valid JSON at all. None of those are failures, so each
 * one has to be skipped rather than allowed to abort the stream.
 */
internal fun parseSseLine(line: String): SseEvent {
    val trimmed = line.trim()
    // Blank lines separate events; a line starting with ':' is a comment, which is what gateways
    // send to keep an idle connection open.
    if (trimmed.isEmpty() || trimmed.startsWith(":")) return SseEvent.Ignore
    if (!trimmed.startsWith("data:")) return SseEvent.Ignore

    val data = trimmed.removePrefix("data:").trim()
    if (data == "[DONE]") return SseEvent.Done
    if (data.isEmpty()) return SseEvent.Ignore

    val root = try {
        JsonParser.parseString(data).asJsonObject
    } catch (_: Exception) {
        return SseEvent.Ignore
    }

    // An error delivered mid-stream arrives as a normal data frame rather than an HTTP status,
    // so it would otherwise be silently swallowed and look like an empty answer.
    root.getAsJsonObject("error")
        ?.get("message")?.takeIf { it.isJsonPrimitive }?.asString
        ?.let { throw ApiException(it) }

    val delta = root.getAsJsonArray("choices")
        ?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        ?.getAsJsonObject("delta")
        ?.get("content")?.takeIf { it.isJsonPrimitive }?.asString
        .orEmpty()

    val model = root.get("model")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    return when {
        delta.isNotEmpty() -> SseEvent.Delta(delta, model)
        model != null -> SseEvent.Model(model)
        else -> SseEvent.Ignore
    }
}
