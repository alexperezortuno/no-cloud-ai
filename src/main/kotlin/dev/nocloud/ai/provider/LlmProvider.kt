package dev.nocloud.ai.provider

import dev.nocloud.ai.core.ChatMessage
import dev.nocloud.ai.core.LlmRequest
import dev.nocloud.ai.core.LlmResponse
import dev.nocloud.ai.core.ModelInfo
import dev.nocloud.ai.core.ProviderConfig
import dev.nocloud.ai.core.ProviderKind
import dev.nocloud.ai.core.ToolSchema
import dev.nocloud.ai.util.toJsonString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A local inference backend.
 *
 * Implementations are stateless: everything needed for a request is passed in via
 * [ProviderConfig], so a single instance can be reused across requests.
 */
interface LlmProvider {
    val kind: ProviderKind

    /** Lists the models the server currently has available. */
    suspend fun listModels(config: ProviderConfig): List<ModelInfo>

    /**
     * Runs one assistant turn.
     *
     * [onDelta] is invoked with incremental text as it arrives so the UI can stream.
     * The returned [LlmResponse] carries the complete text plus any tool calls the
     * model requested.
     */
    suspend fun complete(
        config: ProviderConfig,
        request: LlmRequest,
        onDelta: suspend (String) -> Unit,
    ): LlmResponse

    /** True when the backend supports OpenAI-style function calling. */
    val supportsToolCalling: Boolean get() = true

    /**
     * Runs a raw fill-in-the-middle completion.
     *
     * [prompt] is already assembled by the caller using a
     * [dev.nocloud.ai.completion.FimTemplate], so the backend only has to send it and
     * return the generated text.
     */
    suspend fun completeFim(
        config: ProviderConfig,
        prompt: String,
        stopTokens: List<String>,
        maxTokens: Int = 128,
    ): String
}

/** Builds the provider implementation for a backend. */
object ProviderFactory {
    fun create(kind: ProviderKind): LlmProvider = when (kind) {
        ProviderKind.OLLAMA -> OllamaProvider()
        ProviderKind.LLAMA_CPP -> LlamaCppProvider()
    }
}

/** Shared helpers for building OpenAI-style function tool payloads. */
internal object ToolPayloads {

    /** `{"type":"function","function":{"name":..,"description":..,"parameters":..}}` */
    fun openAiStyle(tool: ToolSchema): JsonObject = buildJsonObject {
        put("type", "function")
        put(
            "function",
            buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", tool.parameters)
            },
        )
    }

    /**
     * Ollama expects the arguments of an assistant tool call as a JSON *object*,
     * while OpenAI-compatible servers expect a JSON *string*.
     */
    fun argumentsAsObject(rawArguments: String): JsonObject = dev.nocloud.ai.util.parseJsonObject(rawArguments)

    fun argumentsAsString(rawArguments: String): String {
        val parsed = dev.nocloud.ai.util.parseJsonElement(rawArguments) ?: return rawArguments
        return parsed.toJsonString()
    }
}

/** Maps our role enum onto the wire names used by the OpenAI-compatible endpoints. */
internal fun ChatMessage.openAiRole(): String = when (role) {
    ChatMessage.Role.SYSTEM -> "system"
    ChatMessage.Role.USER -> "user"
    ChatMessage.Role.ASSISTANT -> "assistant"
    ChatMessage.Role.TOOL -> "tool"
}
