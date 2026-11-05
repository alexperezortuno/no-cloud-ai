package dev.nocloud.ai.completion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompletionPostProcessorTest {

    @Test
    fun `drops a leading newline`() {
        assertEquals("foo()", CompletionPostProcessor.process("\nfoo()", "", ""))
    }

    @Test
    fun `removes the part of the line that is already typed`() {
        val prefix = "    retur"
        assertEquals("n 42;", CompletionPostProcessor.process("    return 42;", prefix, ""))
    }

    @Test
    fun `removes a trailing echo of the suffix`() {
        val result = CompletionPostProcessor.process("value = 1\n}", "x = ", "}")
        assertEquals("value = 1", result)
    }

    @Test
    fun `normalises windows line endings`() {
        assertTrue(CompletionPostProcessor.process("a\r\nb", "", "").contains("\n"))
        assertEquals("a\nb", CompletionPostProcessor.process("a\r\nb", "", ""))
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals("", CompletionPostProcessor.process("", "abc", ""))
    }
}
