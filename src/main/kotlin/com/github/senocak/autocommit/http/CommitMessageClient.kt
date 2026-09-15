package com.github.senocak.autocommit.http

import com.github.senocak.autocommit.settings.ApiConfiguration
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.HttpRequests
import java.io.IOException
import java.net.SocketTimeoutException

object CommitMessageClient {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 60_000
    // Some reasoning-capable gateway models need room to reason before returning the short
    // commit-message text. This remains an internal limit to keep Settings intentionally small.
    private const val MAX_OUTPUT_TOKENS = 2_048
    private val LOG = Logger.getInstance(CommitMessageClient::class.java)

    fun generateCommitMessage(configuration: ApiConfiguration, diff: String): String {
        val response = post(configuration, "Generate a commit message for the following Git diff:\n\n$diff")
        return cleanup(extractMessage(response)).ifBlank {
            throw ApiException("Model returned an empty commit message.")
        }
    }

    fun testConnection(configuration: ApiConfiguration) {
        val response = post(configuration, "Connection test. Return only: OK")
        if (extractMessage(response).isBlank()) throw ApiException("Model returned an empty response.")
    }

    private fun post(configuration: ApiConfiguration, userMessage: String): String {
        val payload = JsonObject().apply {
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

        try {
            LOG.info("Custom Commit AI: API request payload: $payload")
            LOG.info("Custom Commit AI: sending API request (model=${configuration.model}, apiKeyConfigured=${configuration.apiKey.isNotBlank()}).")
            val response = HttpRequests.post(configuration.apiUrl, "application/json")
                .connectTimeout(CONNECT_TIMEOUT_MS)
                .readTimeout(READ_TIMEOUT_MS)
                .tuner { connection ->
                    connection.setRequestProperty("Accept", "application/json")
                    // This is the header contract used by the configured gateway. The key remains
                    // optional; when absent, no credential header is sent.
                    if (configuration.apiKey.isNotBlank()) connection.setRequestProperty("x-api-key", configuration.apiKey)
                    connection.setRequestProperty("anthropic-version", "2023-06-01")
                }
                .connect { request ->
                    request.connection.outputStream.writer(Charsets.UTF_8).use { it.write(payload.toString()) }
                    request.readString()
                }
            LOG.info("Custom Commit AI: API request completed successfully (responseChars=${response.length}).")
            LOG.info("Custom Commit AI: API response: $response")
            return response
        } catch (exception: HttpRequests.HttpStatusException) {
            LOG.warn("Custom Commit AI: API returned HTTP ${exception.statusCode}.")
            throw ApiException("API returned HTTP ${exception.statusCode}.")
        } catch (exception: IOException) {
            LOG.warn("Custom Commit AI: API request failed (${exception.javaClass.simpleName}).")
            throw ApiException("Failed to connect to the configured API.", exception)
        }
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
            LOG.info("Custom Commit AI: API response content blocks=${blockTypes.ifEmpty { listOf("unknown") }}.")
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

    private fun cleanup(message: String): String {
        val trimmed = message.trim()
        return if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            trimmed.removePrefix("```").removeSuffix("```").trim().lineSequence().dropWhile {
                it.equals("text", ignoreCase = true) || it.equals("markdown", ignoreCase = true)
            }.joinToString("\n").trim()
        } else trimmed
    }

    fun userFacingMessage(exception: Exception): String = when (exception) {
        is SocketTimeoutException -> "Custom Commit AI: The API request timed out."
        is ApiException -> "Custom Commit AI: ${exception.message}"
        else -> "Custom Commit AI: Failed to connect to the configured API."
    }
}

class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause)
