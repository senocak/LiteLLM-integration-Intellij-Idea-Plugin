package com.github.senocak.autocommit.http

import com.github.senocak.autocommit.settings.ApiConfiguration
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.HttpRequests
import java.io.IOException

/**
 * Reads the model list the configured gateway is willing to serve.
 *
 * LiteLLM scopes GET /models to the presented key, so this is not a catalogue of
 * everything the gateway hosts — it is exactly what this key may reach. That is what makes
 * a single key field sufficient: swapping the key swaps the list.
 */
object ModelCatalog {
    private const val MODELS_READ_TIMEOUT_MS = 15_000
    private val LOG = Logger.getInstance(ModelCatalog::class.java)

    fun fetch(configuration: ApiConfiguration): List<String> {
        if (configuration.baseUrl.isBlank()) throw ApiException("API URL is not configured.")
        val url = configuration.modelsUrl

        val response = try {
            LOG.info("LiteLLM Integration: fetching models from $url.")
            HttpRequests.request(url)
                .connectTimeout(HttpTimeouts.CONNECT_MS)
                // Deliberately shorter than the generation timeout: listing models is a cheap
                // lookup, and this runs while someone is sitting in the Settings dialog.
                .readTimeout(MODELS_READ_TIMEOUT_MS)
                .tuner { connection ->
                    connection.setRequestProperty("Accept", "application/json")
                    // Either header is accepted; x-api-key keeps one contract with the
                    // generation call, which some gateways key their routing off.
                    if (configuration.apiKey.isNotBlank()) {
                        connection.setRequestProperty("x-api-key", configuration.apiKey)
                        connection.setRequestProperty("Authorization", "Bearer ${configuration.apiKey}")
                    }
                }
                .readString()
        } catch (exception: HttpRequests.HttpStatusException) {
            LOG.warn("LiteLLM Integration: model list returned HTTP ${exception.statusCode}.")
            throw ApiException("Could not list models: HTTP ${exception.statusCode}.")
        } catch (exception: IOException) {
            LOG.warn("LiteLLM Integration: model list request failed (${exception.javaClass.simpleName}).")
            throw ApiException("Could not reach $url to list models.", exception)
        }

        val models = parse(response)
        if (models.isEmpty()) throw ApiException("The gateway returned an empty model list.")
        LOG.info("LiteLLM Integration: gateway offers ${models.size} model(s).")
        return models
    }

    private fun parse(response: String): List<String> {
        val root = try {
            JsonParser.parseString(response).asJsonObject
        } catch (exception: Exception) {
            throw ApiException("The model list response was not valid JSON.", exception)
        }
        // OpenAI/LiteLLM shape: {"data":[{"id":"..."}],"object":"list"}
        return root.getAsJsonArray("data")?.toList().orEmpty()
            .filter { it.isJsonObject }
            .mapNotNull { it.asJsonObject.get("id")?.takeIf { id -> id.isJsonPrimitive }?.asString }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }
}
