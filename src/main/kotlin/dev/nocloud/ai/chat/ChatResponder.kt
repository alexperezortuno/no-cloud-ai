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
        val config = ProviderConfigFactory.current()
        val provider = ProviderFactory.create(config.kind)

        val messages = buildList {
            addAll(history)
            add(ChatMessage.user(userText))
        }

        val request = LlmRequest(
            messages = messages,
            tools = emptyList(),
            systemPrompt = AgentPrompts.chatPrompt(),
        )

        val answer = StringBuilder()
        provider.complete(config, request) { delta ->
            answer.append(delta)
            sink.appendText(delta)
        }
        sink.finish()
        return listOf(ChatMessage.assistant(answer.toString()))
    }
}
