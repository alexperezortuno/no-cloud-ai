package dev.nocloud.ai.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The sample payloads in this test are the literal responses that a llama.cpp
 * server produced for a Qwen2.5-Coder model asked to call a tool.
 */
class ToolCallExtractorTest {

    private val toolNames = setOf("read_file", "write_file")

    @Test
    fun `extracts a tool call from a json code fence`() {
        val text = """
            ```json
            {"name": "read_file", "arguments": {"path": "src/app.ts"}}
            ```
        """.trimIndent()

        val result = ToolCallExtractor.extract(text, toolNames)

        assertEquals(1, result.toolCalls.size)
        assertEquals("read_file", result.toolCalls.single().name)
        assertTrue(result.toolCalls.single().arguments.contains("src/app.ts"))
        // The markup must not be shown to the user.
        assertTrue(!result.text.contains("read_file"))
        assertTrue(!result.text.contains("```"))
    }

    @Test
    fun `extracts a tool call from the function tag form`() {
        val text = """
            ```xml
            <function name="read_file" arguments='{"path": "src/app.ts", "offset": 1}'/>
            ```
        """.trimIndent()

        val result = ToolCallExtractor.extract(text, toolNames)

        assertEquals(1, result.toolCalls.size)
        assertEquals("read_file", result.toolCalls.single().name)
        assertTrue(result.toolCalls.single().arguments.contains("src/app.ts"))
        assertTrue(result.toolCalls.single().arguments.contains("offset"))
        assertTrue(!result.text.contains("function"))
    }

    @Test
    fun `extracts a tool call from tool_call tags`() {
        val text = "<tool_call>\n{\"name\": \"read_file\", \"arguments\": {\"path\": \"a.ts\"}}\n</tool_call>"

        val result = ToolCallExtractor.extract(text, toolNames)

        assertEquals(1, result.toolCalls.size)
        assertEquals("read_file", result.toolCalls.single().name)
        assertTrue(result.text.isBlank())
    }

    @Test
    fun `extracts a bare json tool call`() {
        val text = "{\"name\": \"read_file\", \"arguments\": {\"path\": \"a.ts\"}}"

        val result = ToolCallExtractor.extract(text, toolNames)

        assertEquals(1, result.toolCalls.size)
        assertEquals("read_file", result.toolCalls.single().name)
    }

    @Test
    fun `keeps prose and removes only the tool call`() {
        val text = """
            Let me read that file for you.

            ```json
            {"name": "read_file", "arguments": {"path": "src/app.ts"}}
            ```
        """.trimIndent()

        val result = ToolCallExtractor.extract(text, toolNames)

        assertEquals(1, result.toolCalls.size)
        assertTrue(result.text.contains("Let me read that file"))
        assertTrue(!result.text.contains("read_file"))
    }

    @Test
    fun `does not treat ordinary json as a tool call`() {
        val text = """{"name": "package.json", "version": "1.0.0"}"""

        val result = ToolCallExtractor.extract(text, toolNames)

        assertTrue(result.toolCalls.isEmpty())
        assertEquals(text, result.text)
    }

    @Test
    fun `accepts arguments given as a json string`() {
        val text = """{"name": "read_file", "arguments": "{\"path\": \"a.ts\"}"}"""

        val result = ToolCallExtractor.extract(text, toolNames)

        assertEquals(1, result.toolCalls.size)
        assertTrue(result.toolCalls.single().arguments.contains("a.ts"))
    }

    @Test
    fun `accepts the parameters key`() {
        val text = """{"name": "read_file", "parameters": {"path": "a.ts"}}"""

        val result = ToolCallExtractor.extract(text, toolNames)

        assertEquals(1, result.toolCalls.size)
        assertTrue(result.toolCalls.single().arguments.contains("a.ts"))
    }

    @Test
    fun `an unknown tool is still reported so the agent can complain about it`() {
        val text = """{"name": "does_not_exist", "arguments": {}}"""

        val result = ToolCallExtractor.extract(text, toolNames)

        assertEquals(1, result.toolCalls.size)
        assertEquals("does_not_exist", result.toolCalls.single().name)
    }

    @Test
    fun `candidate detection finds a json fence`() {
        val text = "Here you go:\n```json\n{\"name\": \"read_file\""
        assertTrue(ToolCallExtractor.findCandidateStart(text) >= 0)
    }

    @Test
    fun `candidate detection finds a json object at the start of a line`() {
        val text = "Here you go:\n{\"name\": \"read_file\""
        assertTrue(ToolCallExtractor.findCandidateStart(text) >= 0)
    }

    @Test
    fun `candidate detection ignores ordinary prose`() {
        val text = "Here you go:\nI will read the file now."
        assertEquals(-1, ToolCallExtractor.findCandidateStart(text))
    }
}
