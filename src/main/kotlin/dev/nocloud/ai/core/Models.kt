package dev.nocloud.ai.core

import kotlinx.serialization.json.JsonObject

/**
 * The local inference backends this plugin can talk to.
 *
 * Both speak HTTP on localhost; they differ in wire format, so each gets its own
 * [dev.nocloud.ai.provider.LlmProvider] implementation.
 */
enum class ProviderKind(val id: String, val displayName: String) {
    OLLAMA("ollama", "Ollama"),
    LLAMA_CPP("llama_cpp", "llama.cpp"),
    ;

    companion object {
        fun fromId(id: String?): ProviderKind? = entries.firstOrNull { it.id == id }
    }
}

/** A single tool the model may call. Mirrors the OpenAI function-tool shape used by both backends. */
data class ToolSchema(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/** A tool invocation requested by the model. [arguments] is always a raw JSON object string. */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

/** A model exposed by a local server. */
data class ModelInfo(
    val id: String,
    val displayName: String = id,
)

/** One turn of a conversation. */
data class ChatMessage(
    val role: Role,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    /** Set on [Role.TOOL] messages to correlate a result back to its call. */
    val toolCallId: String? = null,
    /** Set on [Role.TOOL] messages so the model knows which tool produced the result. */
    val toolName: String? = null,
) {
    enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

    val isToolResult: Boolean get() = role == Role.TOOL

    companion object {
        fun system(text: String) = ChatMessage(Role.SYSTEM, text)
        fun user(text: String) = ChatMessage(Role.USER, text)
        fun assistant(text: String, toolCalls: List<ToolCall> = emptyList()) =
            ChatMessage(Role.ASSISTANT, text, toolCalls)

        fun toolResult(call: ToolCall, result: String) =
            ChatMessage(Role.TOOL, result, toolCallId = call.id, toolName = call.name)
    }
}

/** Result of a single provider round-trip. */
data class LlmResponse(
    val text: String,
    val toolCalls: List<ToolCall> = emptyList(),
) {
    val hasToolCalls: Boolean get() = toolCalls.isNotEmpty()
}

/** Everything a provider needs to produce one assistant turn. */
data class LlmRequest(
    val messages: List<ChatMessage>,
    val tools: List<ToolSchema> = emptyList(),
    val systemPrompt: String? = null,
)

/**
 * Runtime configuration for one local backend. Resolved from persisted settings
 * immediately before each request so that changing settings takes effect at once.
 */
data class ProviderConfig(
    val kind: ProviderKind,
    val baseUrl: String,
    val model: String,
    val apiKey: String?,
    val temperature: Double,
    val maxTokens: Int,
    val contextSize: Int,
    /** llama.cpp only: pass `chat_template_kwargs.enable_thinking = false`. */
    val disableThinking: Boolean = false,
) {
    /** Base URL without a trailing slash, so path concatenation is predictable. */
    val normalizedBaseUrl: String get() = baseUrl.trimEnd('/')
}
