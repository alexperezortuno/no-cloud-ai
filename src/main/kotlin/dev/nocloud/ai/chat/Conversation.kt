package dev.nocloud.ai.chat

import dev.nocloud.ai.core.ChatMessage
import dev.nocloud.ai.core.ToolCall

/** Where a running conversation reports its progress. */
interface ResponseSink {
    /** A chunk of assistant text. */
    fun appendText(delta: String)

    /** A tool call finished; [result] is the plain text the model will see. */
    fun appendToolCall(call: ToolCall, result: String) {}

    /** The response completed. */
    fun finish() {}

    /** The response failed. */
    fun fail(message: String)
}

/**
 * Handles one user turn.
 *
 * Returns the messages that were produced during the turn, so they can be appended to
 * the conversation history. For the agent this includes the assistant turns that
 * requested a tool and the tool results, which is what makes the agent remember what
 * it already did.
 */
interface ConversationHandler {
    suspend fun respond(
        userText: String,
        history: List<ChatMessage>,
        sink: ResponseSink,
    ): List<ChatMessage>
}
