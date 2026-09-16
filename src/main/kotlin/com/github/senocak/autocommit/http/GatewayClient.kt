package com.github.senocak.autocommit.http

import com.github.senocak.autocommit.settings.ApiConfiguration
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.HttpRequests
import java.io.IOException

internal object HttpTimeouts {
    const val CONNECT_MS = 10_000
    const val READ_MS = 60_000
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
                    if (configuration.apiKey.isNotBlank()) {
                        connection.setRequestProperty("x-api-key", configuration.apiKey)
                        connection.setRequestProperty("Authorization", "Bearer ${configuration.apiKey}")
                    }
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
     * Builds the OpenAI Chat Completions body. Two details are not interchangeable with the
     * Anthropic one: the cap must be `max_completion_tokens`, because some backends reject
     * `max_tokens` outright, and the system prompt must be a message — a top-level `system`
     * property is refused as an unpermitted extra input.
     */
    fun chatPayload(model: String, systemPrompt: String, userMessage: String, maxTokens: Int) = JsonObject().apply {
        addProperty("model", model)
        addProperty("max_completion_tokens", maxTokens)
        addProperty("stream", false)
        add("messages", JsonArray().apply {
            if (systemPrompt.isNotBlank()) {
                add(JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", systemPrompt)
                })
            }
            add(JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", userMessage)
            })
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
