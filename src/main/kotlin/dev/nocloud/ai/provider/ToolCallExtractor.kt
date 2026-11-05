package dev.nocloud.ai.provider

import dev.nocloud.ai.core.ToolCall
import dev.nocloud.ai.util.parseJsonElement
import dev.nocloud.ai.util.parseJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Recovers tool calls that a local model emitted as plain text.
 *
 * Many local servers - llama.cpp in particular - do not turn the model's output into
 * a structured `tool_calls` array. The model then answers with the tool call written
 * out as text, for example:
 *
 * ```json
 * {"name": "read_file", "arguments": {"path": "src/app.ts"}}
 * ```
 *
 * or as `<tool_call>{...}</tool_call>`, or as
 * `<function name="read_file" arguments='{...}'/>`.
 *
 * This extractor recognises those shapes so the agent can still act on them.
 */
object ToolCallExtractor {

    data class Result(
        /** The text with the tool call markup removed. */
        val text: String,
        /** The tool calls that were found. */
        val toolCalls: List<ToolCall>,
    )

    /**
     * Matches `<tool_call>` and `</tool_call>`.
     *
     * Some chat templates put a zero width space inside the tag name, so it is
     * tolerated here.
     */
    private val TOOL_CALL_OPEN = Regex("""<[‌‍]*tool_call>""")
    private val TOOL_CALL_CLOSE = Regex("""</[‌‍]*tool_call>""")

    /**
     * Matches `<function name="x" arguments='{...}'/>`.
     *
     * The name and the arguments may use different quote styles, and the arguments
     * themselves usually contain the other quote style, so both are matched separately.
     */
    private val FUNCTION_TAG = Regex(
        """<function\s+name\s*=\s*(?:"(?<name1>[^"]*)"|'(?<name2>[^']*)')\s+arguments\s*=\s*(?:"(?<args1>[^"]*)"|'(?<args2>[^']*)')\s*/?>""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** Matches `<function=x>{...}</function>`. */
    private val FUNCTION_BLOCK = Regex(
        """<function\s*=\s*([^\s>]+)\s*>(.*?)</function>""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** A JSON fence on a line of its own. */
    private val JSON_FENCE = Regex("""(?m)^[ \t]*```json[ \t]*$""")

    /** A closing code fence on a line of its own. */
    private val CLOSING_FENCE = Regex("""^[ \t]*```[ \t]*$""", RegexOption.MULTILINE)

    /** Keys a model may use for the argument object. */
    private val ARGUMENT_KEYS = listOf("arguments", "parameters", "args", "arguments_json")

    /**
     * Returns the index of the first character that could start a tool call, or -1.
     *
     * Used by the streaming filter to hold back text that might turn out to be a tool
     * call, so the markup never reaches the chat UI.
     */
    fun findCandidateStart(text: String): Int {
        var offset = 0
        val lines = text.split("\n")
        for (index in lines.indices) {
            val line = lines[index]
            val trimmed = line.trimStart()
            val isLastLine = index == lines.lastIndex
            val definite = trimmed.startsWith("```json") ||
                TOOL_CALL_OPEN.containsMatchIn(trimmed) ||
                TOOL_CALL_CLOSE.containsMatchIn(trimmed) ||
                trimmed.startsWith("<function")
            val jsonWithName = trimmed.startsWith("{") && line.contains("\"name\"")
            val possiblyOpeningJson = isLastLine && trimmed.startsWith("{")
            if (definite || jsonWithName || possiblyOpeningJson) return offset
            offset += line.length + 1
        }
        return -1
    }

    /**
     * Extracts every tool call found in [text].
     *
     * [toolNames] is only used for diagnostics: a call that names an unknown tool is
     * still reported, so the agent can answer with a meaningful "unknown tool" error
     * instead of the call being silently dropped.
     */
    fun extract(text: String, toolNames: Set<String> = emptySet()): Result {
        val calls = mutableListOf<ToolCall>()
        val spans = mutableListOf<IntRange>()

        // 1. XML-ish <function .../> forms.
        for (match in FUNCTION_TAG.findAll(text)) {
            val name = match.groupValue("name1") ?: match.groupValue("name2") ?: continue
            val arguments = match.groupValue("args1") ?: match.groupValue("args2") ?: continue
            calls += buildCall(name.trim(), arguments, calls.size)
            spans += expandAroundMarkup(text, match.range)
        }
        for (match in FUNCTION_BLOCK.findAll(text)) {
            calls += buildCall(match.groupValues[1].trim(), match.groupValues[2], calls.size)
            spans += expandAroundMarkup(text, match.range)
        }

        // 2. JSON objects that carry a name and an argument object.
        for ((start, end) in findJsonObjects(text)) {
            val parsed = parseJsonElement(text.substring(start, end)) as? JsonObject ?: continue
            val name = parsed["name"]?.jsonPrimitive
                ?.takeIf { it !is kotlinx.serialization.json.JsonNull }
                ?.content
            if (name.isNullOrBlank()) continue
            val arguments = parsed.argumentsObject() ?: continue
            calls += buildCall(name, arguments, calls.size)
            spans += expandAroundMarkup(text, start until end)
        }

        if (calls.isEmpty()) return Result(text, emptyList())

        return Result(removeSpans(text, spans), calls)
    }

    /**
     * Widens a match so that the code fence or tool call tag around it is removed as
     * well, without touching unrelated code blocks in the same message.
     */
    private fun expandAroundMarkup(text: String, span: IntRange): IntRange {
        var start = span.first
        var end = span.last + 1

        // A "```json" fence directly above the match.
        val before = text.substring(0, start)
        val fence = JSON_FENCE.findAll(before).lastOrNull()
        if (fence != null && before.substring(fence.range.last + 1).isBlank()) {
            start = fence.range.first
        }

        // An opening tool call tag directly above the match.
        val tag = TOOL_CALL_OPEN.findAll(before).lastOrNull()
        if (tag != null && before.substring(tag.range.last + 1).isBlank()) {
            start = tag.range.first
        }

        // A closing fence or tool call tag directly below the match.
        val after = text.substring(end)
        val closing = CLOSING_FENCE.find(after)
        if (closing != null && after.substring(0, closing.range.first).isBlank()) {
            end += closing.range.last + 1
        }
        val closeTag = TOOL_CALL_CLOSE.find(after)
        if (closeTag != null && after.substring(0, closeTag.range.first).isBlank()) {
            end += closeTag.range.last + 1
        }

        return start until end
    }

    /** Returns the value of a named group, or null when it did not participate. */
    private fun MatchResult.groupValue(name: String): String? = groups[name]?.value

    /** Builds a [ToolCall] from a raw argument payload that may be a JSON object or a JSON string. */
    private fun buildCall(name: String, rawArguments: String, index: Int): ToolCall {
        val arguments = when {
            rawArguments.isBlank() -> "{}"
            rawArguments.trim().startsWith("{") -> normalize(rawArguments)
            else -> {
                // The arguments were handed over as a JSON string.
                val parsed = parseJsonElement(rawArguments.trim())
                if (parsed is JsonObject) parsed.toString() else rawArguments
            }
        }
        return ToolCall(id = "call_${name}_$index", name = name, arguments = arguments)
    }

    /** Normalises an argument payload into a compact JSON object string. */
    private fun normalize(raw: String): String {
        val parsed = parseJsonElement(raw.trim())
        return if (parsed is JsonObject) parsed.toString() else raw
    }

    /** Returns the argument object of a tool call payload, whatever key it used. */
    private fun JsonObject.argumentsObject(): String? {
        for (key in ARGUMENT_KEYS) {
            when (val value = this[key]) {
                is JsonObject -> return value.toString()
                is JsonPrimitive -> {
                    if (value !is kotlinx.serialization.json.JsonNull && value.isString) {
                        val nested = parseJsonObject(value.content)
                        if (nested.isNotEmpty()) return nested.toString()
                        return value.content
                    }
                }

                else -> Unit
            }
        }
        return null
    }

    /**
     * Finds the outermost balanced JSON objects in [text].
     *
     * Nested objects are not returned, because the argument object of a tool call is
     * itself a JSON object.
     */
    internal fun findJsonObjects(text: String): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for (index in text.indices) {
            val c = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = index
                    depth++
                }

                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) {
                            result += start to (index + 1)
                            start = -1
                        }
                    }
                }
            }
        }
        return result
    }

    /** Removes the given spans from the text. */
    private fun removeSpans(text: String, spans: List<IntRange>): String {
        val sorted = spans.sortedBy { it.first }
        val builder = StringBuilder()
        var cursor = 0
        for (span in sorted) {
            if (span.first < cursor) continue
            builder.append(text, cursor, span.first)
            cursor = span.last + 1
        }
        builder.append(text, cursor, text.length)

        return builder.toString()
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }
}
