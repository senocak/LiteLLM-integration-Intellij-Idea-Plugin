package com.github.senocak.autocommit.http

import com.github.senocak.autocommit.settings.ApiConfiguration
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.HttpRequests
import java.io.IOException
import java.net.SocketTimeoutException

/** Request dialect. The gateway decides which one it speaks; see [CommitMessageClient.post]. */
private enum class WireFormat { CHAT, ANTHROPIC }

object CommitMessageClient {
    private const val READ_TIMEOUT_MS = HttpTimeouts.READ_MS
    // Some reasoning-capable gateway models need room to reason before returning the short
    // commit-message text. This remains an internal limit to keep Settings intentionally small.
    private const val MAX_OUTPUT_TOKENS = 2_048
    private val LOG = Logger.getInstance(CommitMessageClient::class.java)

    /**
     * Which dialect each gateway turned out to speak, so the fallback is probed at most
     * once per base URL per IDE session rather than on every generation.
     */
    private val resolvedFormats = java.util.concurrent.ConcurrentHashMap<String, WireFormat>()

    fun generateCommitMessage(configuration: ApiConfiguration, diff: String): String {
        val response = post(configuration, "Generate a commit message for the following Git diff:\n\n$diff")
        return GatewayClient.stripFences(extractMessage(response)).ifBlank {
            throw ApiException("Model returned an empty commit message.")
        }
    }

    fun testConnection(configuration: ApiConfiguration) {
        val response = post(configuration, "Connection test. Return only: OK")
        if (extractMessage(response).isBlank()) throw ApiException("Model returned an empty response.")
    }

    /**
     * Chat Completions is tried first: on a LiteLLM gateway it serves every model, including
     * the ones whose backend rejects Anthropic's `max_tokens` and demands
     * `max_completion_tokens`. Anthropic Messages is kept as a fallback purely so a non-LiteLLM
     * endpoint still works — it is used only when the chat route is absent (404/405), never to
     * paper over a real error such as 401 or 400.
     */
    private fun post(configuration: ApiConfiguration, userMessage: String): String {
        val preferred = resolvedFormats[configuration.baseUrl] ?: WireFormat.CHAT
        return try {
            send(configuration, userMessage, preferred).also { resolvedFormats[configuration.baseUrl] = preferred }
        } catch (exception: HttpRequests.HttpStatusException) {
            val routeMissing = exception.statusCode == 404 || exception.statusCode == 405
            if (!routeMissing || preferred != WireFormat.CHAT) {
                LOG.warn("LiteLLM Integration: API returned HTTP ${exception.statusCode}.")
                throw ApiException("API returned HTTP ${exception.statusCode}.")
            }
            LOG.info("LiteLLM Integration: no chat/completions route (HTTP ${exception.statusCode}); trying Anthropic messages.")
            try {
                send(configuration, userMessage, WireFormat.ANTHROPIC)
                    .also { resolvedFormats[configuration.baseUrl] = WireFormat.ANTHROPIC }
            } catch (fallback: HttpRequests.HttpStatusException) {
                LOG.warn("LiteLLM Integration: Anthropic fallback returned HTTP ${fallback.statusCode}.")
                throw ApiException("API returned HTTP ${fallback.statusCode}.")
            }
        }
    }

    private fun send(configuration: ApiConfiguration, userMessage: String, format: WireFormat): String {
        val url = if (format == WireFormat.CHAT) configuration.chatUrl else configuration.messagesUrl
        val payload = if (format == WireFormat.CHAT)
            GatewayClient.chatPayload(configuration.model, configuration.systemPrompt, userMessage, MAX_OUTPUT_TOKENS)
        else anthropicPayload(configuration, userMessage)

        LOG.info("LiteLLM Integration: API request payload: $payload")
        LOG.info("LiteLLM Integration: sending $format request to $url (model=${configuration.model}, apiKeyConfigured=${configuration.apiKey.isNotBlank()}).")
        val response = GatewayClient.post(
            configuration, url, payload,
            readTimeoutMs = READ_TIMEOUT_MS,
            anthropicVersion = format == WireFormat.ANTHROPIC,
        )
        LOG.info("LiteLLM Integration: API request completed successfully (responseChars=${response.length}).")
        LOG.info("LiteLLM Integration: API response: $response")
        return response
    }

    private fun anthropicPayload(configuration: ApiConfiguration, userMessage: String) = JsonObject().apply {
        addProperty("model", configuration.model)
        addProperty("max_tokens", MAX_OUTPUT_TOKENS)
        addProperty("stream", false)
        addProperty("system", configuration.systemPrompt)
        add("messages", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", userMessage)
            })
        })
    }

    private fun extractMessage(response: String): String {
        val root = try {
            JsonParser.parseString(response).asJsonObject
        } catch (exception: Exception) {
            LOG.debug("Configured API returned invalid JSON", exception)
            throw ApiException("API returned an invalid JSON response.", exception)
        }

        // The configured gateway returns a Messages response. The choices fallback also accepts
        // the normal non-streaming chat-completions shape without changing the request contract.
        val contentBlocks = root.getAsJsonArray("content")
        val blocks = contentBlocks?.toList().orEmpty()
        val blockTypes = blocks
            .filter { it.isJsonObject }
            .mapNotNull { it.asJsonObject.get("type")?.takeIf { value -> value.isJsonPrimitive }?.asString }
        if (contentBlocks != null) {
            LOG.info("LiteLLM Integration: API response content blocks=${blockTypes.ifEmpty { listOf("unknown") }}.")
        }

        blocks
            .asSequence()
            .filter { it.isJsonObject && it.asJsonObject.get("type")?.asString == "text" }
            .mapNotNull { it.asJsonObject.get("text")?.takeIf { value -> value.isJsonPrimitive }?.asString }
            .firstOrNull { it.isNotBlank() }
            ?.let { return it }

        root.getAsJsonArray("choices")
            ?.firstOrNull()?.asJsonObject?.getAsJsonObject("message")
            ?.get("content")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        if (blockTypes.any { it == "thinking" || it == "reasoning" }) {
            throw ApiException("Model returned reasoning but no commit message. Try the request again.")
        }
        throw ApiException("API response did not contain a commit message.")
    }

    fun userFacingMessage(exception: Exception): String = when (exception) {
        is SocketTimeoutException -> "LiteLLM Integration: The API request timed out."
        is ApiException -> "LiteLLM Integration: ${exception.message}"
        else -> "LiteLLM Integration: Failed to connect to the configured API."
    }
}

class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause)
