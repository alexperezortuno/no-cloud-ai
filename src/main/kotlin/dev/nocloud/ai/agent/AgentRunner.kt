package dev.nocloud.ai.agent

import dev.nocloud.ai.core.ChatMessage
import dev.nocloud.ai.core.LlmRequest
import dev.nocloud.ai.core.ProviderConfig
import dev.nocloud.ai.core.ToolCall
import dev.nocloud.ai.core.ToolSchema
import dev.nocloud.ai.provider.LlmProvider
import dev.nocloud.ai.settings.LocalAiSettings
import dev.nocloud.ai.settings.ProviderConfigFactory

/** Receives events produced while the agent runs. */
interface AgentSink {
    /** A chunk of assistant text. */
    fun onText(delta: String) {}

    /** A tool finished executing. */
    fun onToolCall(call: ToolCall, result: String) {}

    /** The run failed. */
    fun onError(message: String) {}

    /** The run ended normally. */
    fun onFinished(finalText: String) {}
}

/**
 * A hand written tool-calling loop.
 *
 * The loop is intentionally simple so that it behaves predictably against local servers:
 * ask the model, execute whatever tools it asked for, feed the results back, repeat.
 * No provider-specific endpoint switching happens here, which is what breaks many
 * agent frameworks when the backend is a plain llama.cpp or Ollama server.
 */
class AgentRunner(
    private val provider: LlmProvider,
    private val config: ProviderConfig = ProviderConfigFactory.current(),
    private val maxIterations: Int = LocalAiSettings.instance().state.agentMaxIterations,
) {

    /**
     * Runs the loop and returns the messages that were produced.
     *
     * The returned list holds the assistant turns - including the ones that requested
     * a tool - and the tool results, so the caller can keep the agent's memory across
     * turns.
     */
    suspend fun run(
        userMessage: String,
        history: List<ChatMessage> = emptyList(),
        tools: List<AgentTool> = emptyList(),
        sink: AgentSink,
    ): List<ChatMessage> {
        val messages = history.toMutableList()
        val produced = mutableListOf<ChatMessage>()
        messages += ChatMessage.user(userMessage)
        produced += ChatMessage.user(userMessage)

        val schemas = tools.map { ToolSchema(it.name, it.description, it.parameters) }
        var iteration = 0

        try {
            while (iteration < maxIterations) {
                iteration++
                val request = LlmRequest(
                    // Snapshot the conversation: the list keeps growing while the
                    // request is in flight.
                    messages = messages.toList(),
                    tools = schemas,
                    systemPrompt = AgentPrompts.systemPrompt(),
                )

                val response = provider.complete(config, request) { delta -> sink.onText(delta) }

                if (response.text.isNotBlank() || response.toolCalls.isEmpty()) {
                    messages += ChatMessage.assistant(response.text, response.toolCalls)
                    produced += ChatMessage.assistant(response.text, response.toolCalls)
                } else {
                    messages += ChatMessage.assistant("", response.toolCalls)
                    produced += ChatMessage.assistant("", response.toolCalls)
                }

                if (!response.hasToolCalls) {
                    sink.onFinished(response.text)
                    return produced
                }

                for (call in response.toolCalls) {
                    val tool = tools.firstOrNull { it.name == call.name }
                    val result = if (tool == null) {
                        "Error: unknown tool '${call.name}'. Available tools: ${tools.joinToString(", ") { it.name }}."
                    } else {
                        runCatching { tool.execute(call.arguments) }
                            .getOrElse { "Error: tool '${call.name}' failed: ${it.message}" }
                    }
                    sink.onToolCall(call, result)
                    val toolMessage = ChatMessage.toolResult(call, result)
                    messages += toolMessage
                    produced += toolMessage
                }
            }
            sink.onFinished("Stopped after $maxIterations tool iterations without a final answer.")
            return produced
        } catch (e: Exception) {
            sink.onError(e.message ?: e::class.java.simpleName)
            return produced
        }
    }
}
