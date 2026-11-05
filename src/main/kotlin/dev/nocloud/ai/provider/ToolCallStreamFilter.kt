package dev.nocloud.ai.provider

import dev.nocloud.ai.core.ToolCall

/**
 * Buffers streamed text so that tool call markup never reaches the chat UI.
 *
 * A local model that is asked to call a tool answers with the call written out as
 * text. The markup is only recognisable once it has been received in full, so the
 * tail of the stream is held back until it is clear whether the text is prose or a
 * tool call.
 *
 * The buffer is released as soon as it is safe:
 * * while the text cannot be a tool call, everything but a small tail is streamed;
 * * as soon as a possible tool call is seen, the rest of the turn is buffered and
 *   released - without the tool call - when the turn ends.
 */
internal class ToolCallStreamFilter(
    private val toolNames: Set<String>,
    /** How much text is kept back while no tool call can be seen yet. */
    private val holdBackChars: Int = 48,
) {

    data class Chunk(val text: String)

    data class Final(
        /** The complete text of the turn, with any tool call markup removed. */
        val text: String,
        /** The tool calls that were found in the text. */
        val toolCalls: List<ToolCall>,
        /** The part of [text] that has not been streamed out yet. */
        val pending: String,
    )

    private val full = StringBuilder()

    /** How much of [full] has already been streamed. */
    private var emitted = 0

    /** Index in [full] where the text stopped being safe to stream. */
    private var holdStart = -1

    /** Feeds a delta and returns the text that is safe to stream now. */
    fun accept(delta: String): Chunk {
        full.append(delta)
        val text = full.toString()

        if (holdStart < 0) {
            val candidate = ToolCallExtractor.findCandidateStart(text)
            if (candidate >= 0 && candidate >= emitted) {
                holdStart = candidate
            }
        }

        val safe = if (holdStart >= 0) holdStart else maxOf(0, text.length - holdBackChars)
        val out = if (safe > emitted) text.substring(emitted, safe) else ""
        emitted = maxOf(emitted, safe)
        return Chunk(out)
    }

    /** Ends the stream and returns the complete text plus any tool calls that were found. */
    fun finish(): Final {
        val text = full.toString()

        if (holdStart < 0) {
            val pending = if (emitted < text.length) text.substring(emitted) else ""
            emitted = text.length
            return Final(text, emptyList(), pending)
        }

        val prefix = text.substring(0, holdStart)
        val suffix = text.substring(holdStart)
        val extracted = ToolCallExtractor.extract(suffix, toolNames)
        val cleaned = prefix + extracted.text
        val pending = if (emitted < cleaned.length) cleaned.substring(emitted) else ""
        emitted = cleaned.length
        return Final(cleaned, extracted.toolCalls, pending)
    }
}
