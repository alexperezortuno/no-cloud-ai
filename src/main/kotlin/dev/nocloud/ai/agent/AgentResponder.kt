package dev.nocloud.ai.agent

import com.intellij.openapi.project.Project
import dev.nocloud.ai.chat.ConversationHandler
import dev.nocloud.ai.chat.ResponseSink
import dev.nocloud.ai.core.ChatMessage
import dev.nocloud.ai.core.ToolCall
import dev.nocloud.ai.provider.ProviderFactory
import dev.nocloud.ai.settings.ProviderConfigFactory

/** Agent mode: the model may call tools until it produces a final answer. */
class AgentResponder(private val project: Project) : ConversationHandler {

    override suspend fun respond(
        userText: String,
        history: List<ChatMessage>,
        sink: ResponseSink,
    ): List<ChatMessage> {
        val configs = ProviderConfigFactory.active()
        if (configs.isEmpty()) {
            sink.fail("No active providers configured. Enable at least one provider in settings.")
            return emptyList()
        }

        val tools = ToolRegistry.forProject(project)

        var lastError: Throwable? = null
        for (config in configs) {
            val provider = ProviderFactory.create(config.kind)
            if (!provider.supportsToolCalling) {
                sink.appendText("\n[${config.kind.displayName} does not support tool calling, trying next provider...]")
                continue
            }
            try {
                return AgentRunner(provider, config).run(
                    userMessage = userText,
                    history = history,
                    tools = tools,
                    sink = object : AgentSink {
                        override fun onText(delta: String) = sink.appendText(delta)

                        override fun onToolCall(call: ToolCall, result: String) = sink.appendToolCall(call, result)

                        override fun onError(message: String) = sink.fail(message)

                        override fun onFinished(finalText: String) = sink.finish()
                    },
                )
            } catch (e: Exception) {
                lastError = e
                sink.appendText("\n[${config.kind.displayName} failed: ${e.message}, trying next provider...]")
            }
        }

        sink.fail("All active providers failed. Last error: ${lastError?.message}")
        return emptyList()
    }
}
