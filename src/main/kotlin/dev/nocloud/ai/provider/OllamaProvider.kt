package dev.nocloud.ai.provider

import dev.nocloud.ai.core.ChatMessage
import dev.nocloud.ai.core.LlmRequest
import dev.nocloud.ai.core.LlmResponse
import dev.nocloud.ai.core.ModelInfo
import dev.nocloud.ai.core.ProviderConfig
import dev.nocloud.ai.core.ProviderKind
import dev.nocloud.ai.core.ToolCall
import dev.nocloud.ai.http.HttpClients
import dev.nocloud.ai.http.errorText
import dev.nocloud.ai.http.forEachLine
import dev.nocloud.ai.http.readText
import dev.nocloud.ai.util.Json
import dev.nocloud.ai.util.parseJsonElement
import dev.nocloud.ai.util.parseJsonObject
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Talks to a local [Ollama](https://ollama.com) server.
 *
 * Uses Ollama's native API rather than its OpenAI-compatible shim, because the native
 * endpoint exposes `/api/tags` for model discovery and streams NDJSON instead of SSE.
 */
class OllamaProvider : LlmProvider {

    override val kind: ProviderKind = ProviderKind.OLLAMA

    override suspend fun listModels(config: ProviderConfig): List<ModelInfo> {
        val client = HttpClients.create()
        try {
            val response = client.get("${config.normalizedBaseUrl}/api/tags") {
                config.apiKey?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
            }
            if (!response.status.isSuccess()) {
                throw IllegalStateException("Cannot reach Ollama at ${config.normalizedBaseUrl} - ${response.errorText()}")
            }
            val root = parseJsonObject(response.readText())
            val models = root["models"]?.jsonArray.orEmpty()
            return models.mapNotNull { element ->
                val obj = element.jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNullSafe()
                    ?: obj["model"]?.jsonPrimitive?.contentOrNullSafe()
                    ?: return@mapNotNull null
                ModelInfo(id = name, displayName = name)
            }.distinctBy { it.id }.sortedBy { it.id }
        } finally {
            client.close()
        }
    }

    override suspend fun complete(
        config: ProviderConfig,
        request: LlmRequest,
        onDelta: suspend (String) -> Unit,
    ): LlmResponse {
        val body = buildJsonObject {
            put("model", config.model)
            put("stream", true)
            put("messages", buildJsonArray {
                request.systemPrompt?.takeIf { it.isNotBlank() }?.let {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", it)
                    })
                }
                request.messages.forEach { add(it.toOllamaMessage()) }
            })
            if (request.tools.isNotEmpty()) {
                put(
                    "tools",
                    buildJsonArray { request.tools.forEach { add(ToolPayloads.openAiStyle(it)) } },
                )
            }
            put(
                "options",
                buildJsonObject {
                    put("temperature", config.temperature)
                    put("num_ctx", config.contextSize)
                    put("num_predict", config.maxTokens)
                },
            )
        }

        val client = HttpClients.create()
        try {
            val response = client.post("${config.normalizedBaseUrl}/api/chat") {
                contentType(ContentType.Application.Json)
                config.apiKey?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
                setBody(body.toString())
            }
            if (!response.status.isSuccess()) {
                throw IllegalStateException("Ollama request failed - ${response.errorText()}")
            }

            val text = StringBuilder()
            val toolCalls = mutableListOf<ToolCall>()
            val streamFilter = ToolCallStreamFilter(request.tools.map { it.name }.toSet())

            response.forEachLine { line ->
                val chunk = parseJsonElement(line) as? JsonObject ?: return@forEachLine
                val message = chunk["message"]?.jsonObject ?: return@forEachLine

                message["content"]?.jsonPrimitive?.contentOrNullSafe()?.let { delta ->
                    if (delta.isNotEmpty()) {
                        text.append(delta)
                        streamFilter.accept(delta).text.takeIf { it.isNotEmpty() }?.let { onDelta(it) }
                    }
                }

                message["tool_calls"]?.jsonArray?.forEachIndexed { index, element ->
                    val fn = element.jsonObject["function"]?.jsonObject ?: return@forEachIndexed
                    val name = fn["name"]?.jsonPrimitive?.contentOrNullSafe() ?: return@forEachIndexed
                    val rawArgs = fn["arguments"]
                    val argumentsText = when (rawArgs) {
                        is JsonPrimitive -> rawArgs.content
                        is JsonObject -> rawArgs.toString()
                        else -> "{}"
                    }
                    toolCalls += ToolCall(
                        id = "call_${name}_$index",
                        name = name,
                        arguments = argumentsText,
                    )
                }
            }

            // Ollama normally returns a structured tool call, but some models answer
            // with the call written out as text instead.
            val final = streamFilter.finish()
            final.pending.takeIf { it.isNotEmpty() }?.let { onDelta(it) }

            val resolvedCalls = if (toolCalls.isNotEmpty()) toolCalls else final.toolCalls
            val finalText = if (toolCalls.isNotEmpty()) text.toString() else final.text

            return LlmResponse(text = finalText, toolCalls = resolvedCalls)
        } finally {
            client.close()
        }
    }

    /**
     * `POST {base}/api/generate` with `raw: true`, so Ollama does not wrap the prompt
     * in the model's chat template.
     */
    override suspend fun completeFim(
        config: ProviderConfig,
        prompt: String,
        stopTokens: List<String>,
        maxTokens: Int,
    ): String {
        val body = buildJsonObject {
            put("model", config.model)
            put("prompt", prompt)
            put("stream", false)
            put("raw", true)
            put(
                "options",
                buildJsonObject {
                    put(
                        "stop",
                        kotlinx.serialization.json.JsonArray(stopTokens.map { kotlinx.serialization.json.JsonPrimitive(it) }),
                    )
                    put("num_predict", maxTokens)
                    put("temperature", 0.0)
                    put("top_p", 0.95)
                },
            )
        }

        val client = HttpClients.create()
        try {
            val response = client.post("${config.normalizedBaseUrl}/api/generate") {
                contentType(ContentType.Application.Json)
                config.apiKey?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
                setBody(body.toString())
            }
            if (!response.status.isSuccess()) {
                throw IllegalStateException("Ollama completion failed - ${response.errorText()}")
            }
            val root = parseJsonObject(response.readText())
            return root["response"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
        } finally {
            client.close()
        }
    }

    private fun ChatMessage.toOllamaMessage(): JsonObject = buildJsonObject {
        put("role", openAiRole())
        put("content", content)
        if (toolCalls.isNotEmpty()) {
            put(
                "tool_calls",
                buildJsonArray {
                    toolCalls.forEach { call ->
                        add(
                            buildJsonObject {
                                put(
                                    "function",
                                    buildJsonObject {
                                        put("name", call.name)
                                        put("arguments", ToolPayloads.argumentsAsObject(call.arguments))
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
    }
}

/** Reads a string value, tolerating JSON nulls. */
internal fun JsonPrimitive.contentOrNullSafe(): String? = if (this is kotlinx.serialization.json.JsonNull) null else content
