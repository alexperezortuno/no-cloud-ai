package dev.nocloud.ai.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolCallStreamFilterTest {

    private val toolNames = setOf("read_file")

    @Test
    fun `streams prose without waiting for the end of the turn`() {
        val filter = ToolCallStreamFilter(toolNames)
        val streamed = StringBuilder()

        streamed.append(filter.accept("Here is the answer to your question. ").text)
        streamed.append(filter.accept("It is a fairly long sentence that goes on for a while ").text)
        streamed.append(filter.accept("and keeps going so that the buffer passes the hold back size.").text)
        val final = filter.finish()
        streamed.append(final.pending)

        assertTrue(streamed.contains("Here is the answer"))
        assertTrue(streamed.contains("keeps going"))
    }

    @Test
    fun `never streams tool call markup`() {
        val filter = ToolCallStreamFilter(toolNames)
        val streamed = StringBuilder()

        val chunks = listOf(
            "```json\n",
            "{\"name\": \"read_file\", ",
            "\"arguments\": {\"path\": \"src/app.ts\"}}\n",
            "```",
        )
        chunks.forEach { streamed.append(filter.accept(it).text) }

        val final = filter.finish()
        streamed.append(final.pending)

        assertTrue(!streamed.contains("read_file"))
        assertTrue(!streamed.contains("```json"))
        assertEquals(1, final.toolCalls.size)
        assertEquals("read_file", final.toolCalls.single().name)
    }

    @Test
    fun `keeps the prose that came before the tool call`() {
        val filter = ToolCallStreamFilter(toolNames)
        val streamed = StringBuilder()

        streamed.append(filter.accept("I will read the file now.\n").text)
        streamed.append(filter.accept("```json\n").text)
        streamed.append(filter.accept("{\"name\": \"read_file\", \"arguments\": {\"path\": \"a.ts\"}}\n").text)
        streamed.append(filter.accept("```").text)

        val final = filter.finish()
        streamed.append(final.pending)

        assertTrue(streamed.contains("I will read the file now."))
        assertTrue(!streamed.contains("read_file"))
        assertEquals(1, final.toolCalls.size)
    }

    @Test
    fun `a plain answer is returned unchanged`() {
        val filter = ToolCallStreamFilter(toolNames)
        filter.accept("The answer is 42.")
        val final = filter.finish()

        assertEquals("The answer is 42.", final.text)
        assertTrue(final.toolCalls.isEmpty())
    }

    @Test
    fun `a false positive candidate releases the text at the end`() {
        val filter = ToolCallStreamFilter(toolNames)
        filter.accept("Here is some json:\n")
        filter.accept("{\"notATool\": 1}\n")
        filter.accept("and more prose after it.")
        val final = filter.finish()

        assertTrue(final.toolCalls.isEmpty())
        assertTrue(final.text.contains("and more prose after it."))
    }
}
