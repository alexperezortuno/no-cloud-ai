package dev.nocloud.ai.chat

import dev.nocloud.ai.agent.AgentPrompts
import dev.nocloud.ai.core.ChatMessage
import dev.nocloud.ai.core.LlmRequest
import dev.nocloud.ai.provider.ProviderFactory
import dev.nocloud.ai.settings.ProviderConfigFactory

/** Plain chat: one model turn, no tools. */
class ChatResponder : ConversationHandler {

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

        val messages = buildList {
            addAll(history)
            add(ChatMessage.user(userText))
        }

        val request = LlmRequest(
            messages = messages,
            tools = emptyList(),
            systemPrompt = AgentPrompts.chatPrompt(),
        )

        var lastError: Throwable? = null
        for (config in configs) {
            val provider = ProviderFactory.create(config.kind)
            val answer = StringBuilder()
            try {
                provider.complete(config, request) { delta ->
                    answer.append(delta)
                    sink.appendText(delta)
                }
                sink.finish()
                return listOf(ChatMessage.assistant(answer.toString()))
            } catch (e: Exception) {
                lastError = e
                sink.appendText("\n[${config.kind.displayName} failed: ${e.message}, trying next provider...]")
            }
        }

        sink.fail("All active providers failed. Last error: ${lastError?.message}")
        return emptyList()
    }
}
