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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Talks to a local [llama.cpp](https://github.com/ggml-org/llama.cpp) `llama-server`.
 *
 * The server exposes an OpenAI-compatible `/v1/chat/completions` endpoint, which is used
 * for chat and tool calling. Model discovery uses `/v1/models`, falling back to the
 * configured model name when the build does not advertise a model list.
 */
class LlamaCppProvider : LlmProvider {

    override val kind: ProviderKind = ProviderKind.LLAMA_CPP

    override suspend fun listModels(config: ProviderConfig): List<ModelInfo> {
        val client = HttpClients.create()
        try {
            val response = client.get("${config.normalizedBaseUrl}/v1/models") {
                config.apiKey?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
            }
            if (!response.status.isSuccess()) {
                // Older llama-server builds do not expose /v1/models; fall back to the
                // model the user configured so the UI is still usable.
                return config.model.takeIf { it.isNotBlank() }
                    ?.let { listOf(ModelInfo(it)) }
                    .orEmpty()
            }
            val root = parseJsonObject(response.readText())
            val data = root["data"]?.jsonArray.orEmpty()
            val models = data.mapNotNull { element ->
                val obj = element.jsonObject
                obj["id"]?.jsonPrimitive?.contentOrNullSafe()?.let { ModelInfo(id = it, displayName = it) }
            }
            if (models.isNotEmpty()) {
                return models.distinctBy { it.id }.sortedBy { it.id }
            }
            return config.model.takeIf { it.isNotBlank() }?.let { listOf(ModelInfo(it)) }.orEmpty()
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
            put("temperature", config.temperature)
            put("max_tokens", config.maxTokens)
            put(
                "messages",
                buildJsonArray {
                    request.systemPrompt?.takeIf { it.isNotBlank() }?.let {
                        add(buildJsonObject {
                            put("role", "system")
                            put("content", it)
                        })
                    }
                    request.messages.forEach { add(it.toOpenAiMessage()) }
                },
            )
            if (request.tools.isNotEmpty()) {
                put(
                    "tools",
                    buildJsonArray { request.tools.forEach { add(ToolPayloads.openAiStyle(it)) } },
                )
            }
            if (config.disableThinking) {
                put("chat_template_kwargs", buildJsonObject { put("enable_thinking", false) })
            }
        }

        val client = HttpClients.create()
        try {
            val response = client.post("${config.normalizedBaseUrl}/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                config.apiKey?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
                setBody(body.toString())
            }
            if (!response.status.isSuccess()) {
                throw IllegalStateException("llama.cpp request failed - ${response.errorText()}")
            }

            val text = StringBuilder()
            val calls = LinkedHashMap<Int, ToolCallBuilder>()
            val streamFilter = ToolCallStreamFilter(request.tools.map { it.name }.toSet())

            response.forEachLine { rawLine ->
                val line = rawLine.trim()
                if (!line.startsWith("data:")) return@forEachLine
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty() || payload == "[DONE]") return@forEachLine

                val chunk = parseJsonElement(payload) as? JsonObject ?: return@forEachLine
                val choice = chunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@forEachLine

                choice["delta"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNullSafe()?.let { delta ->
                    if (delta.isNotEmpty()) {
                        text.append(delta)
                        // Hold back text that could still turn out to be a tool call.
                        streamFilter.accept(delta).text.takeIf { it.isNotEmpty() }?.let { onDelta(it) }
                    }
                }

                // Streaming tool calls arrive as fragments keyed by index.
                choice["delta"]?.jsonObject?.get("tool_calls")?.jsonArray?.forEach { element ->
                    val obj = element.jsonObject
                    val index = obj["index"]?.jsonPrimitive?.contentOrNullSafe()?.toIntOrNull() ?: 0
                    val fn = obj["function"]?.jsonObject
                    calls.getOrPut(index) { ToolCallBuilder() }.apply {
                        obj["id"]?.jsonPrimitive?.contentOrNullSafe()?.let { id = it }
                        fn?.get("name")?.jsonPrimitive?.contentOrNullSafe()?.let { name = it }
                        fn?.get("arguments")?.jsonPrimitive?.contentOrNullSafe()?.let { arguments.append(it) }
                    }
                }

                // Non-streaming shape: the full message carries the tool calls.
                choice["message"]?.jsonObject?.get("tool_calls")?.jsonArray?.forEachIndexed { index, element ->
                    val obj = element.jsonObject
                    val fn = obj["function"]?.jsonObject ?: return@forEachIndexed
                    calls.getOrPut(index) { ToolCallBuilder() }.apply {
                        obj["id"]?.jsonPrimitive?.contentOrNullSafe()?.let { id = it }
                        fn["name"]?.jsonPrimitive?.contentOrNullSafe()?.let { name = it }
                        fn["arguments"]?.let { raw ->
                            when (raw) {
                                is kotlinx.serialization.json.JsonPrimitive -> {
                                    if (arguments.isEmpty()) arguments.append(raw.content)
                                }

                                is JsonObject -> {
                                    if (arguments.isEmpty()) arguments.append(raw.toString())
                                }

                                else -> Unit
                            }
                        }
                    }
                }
            }

            val structuredCalls = calls.entries
                .sortedBy { it.key }
                .map { (index, builder) ->
                    ToolCall(
                        id = builder.id ?: "call_${builder.name ?: "tool"}_$index",
                        name = builder.name.orEmpty(),
                        arguments = builder.arguments.toString().ifBlank { "{}" },
                    )
                }
                .filter { it.name.isNotBlank() }

            // Some servers never return a structured tool call, so fall back to
            // recovering it from the text the model produced.
            val final = streamFilter.finish()
            final.pending.takeIf { it.isNotEmpty() }?.let { onDelta(it) }

            val toolCalls = if (structuredCalls.isNotEmpty()) structuredCalls else final.toolCalls
            val finalText = if (structuredCalls.isNotEmpty()) text.toString() else final.text

            return LlmResponse(text = finalText, toolCalls = toolCalls)
        } finally {
            client.close()
        }
    }

    /**
     * `POST {base}/v1/completions` - the OpenAI-compatible text completion endpoint.
     * Only the prompt is sent; the FIM markers are already baked into it.
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
            put("max_tokens", maxTokens)
            put("temperature", 0.0)
            put("top_p", 0.95)
            if (stopTokens.isNotEmpty()) {
                put(
                    "stop",
                    kotlinx.serialization.json.JsonArray(stopTokens.map { kotlinx.serialization.json.JsonPrimitive(it) }),
                )
            }
        }

        val client = HttpClients.create()
        try {
            val response = client.post("${config.normalizedBaseUrl}/v1/completions") {
                contentType(ContentType.Application.Json)
                config.apiKey?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
                setBody(body.toString())
            }
            if (!response.status.isSuccess()) {
                throw IllegalStateException("llama.cpp completion failed - ${response.errorText()}")
            }
            val root = parseJsonObject(response.readText())
            val choice = (root["choices"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.jsonObject
            return choice?.get("text")?.jsonPrimitive?.contentOrNullSafe().orEmpty()
        } finally {
            client.close()
        }
    }

    private class ToolCallBuilder {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }

    private fun ChatMessage.toOpenAiMessage(): JsonObject = buildJsonObject {
        put("role", openAiRole())
        put("content", content)
        toolCallId?.let { put("tool_call_id", it) }
        if (toolCalls.isNotEmpty()) {
            put(
                "tool_calls",
                buildJsonArray {
                    toolCalls.forEach { call ->
                        add(
                            buildJsonObject {
                                put("id", call.id)
                                put("type", "function")
                                put(
                                    "function",
                                    buildJsonObject {
                                        put("name", call.name)
                                        put("arguments", ToolPayloads.argumentsAsString(call.arguments))
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
