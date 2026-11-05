package dev.nocloud.ai.completion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FimTemplateTest {

    @Test
    fun `qwen template wraps prefix and suffix`() {
        val prompt = FimTemplate.QWEN.buildPrompt("const a = ", ";\nconsole.log(a)")
        assertEquals("<|fim_prefix|>const a = <|fim_suffix|>;\nconsole.log(a)<|fim_middle|>", prompt)
    }

    @Test
    fun `deepseek template uses its own markers`() {
        val prompt = FimTemplate.DEEPSEEK.buildPrompt("a", "b")
        assertTrue(prompt.contains("fim▁begin"))
        assertTrue(prompt.contains("fim▁hole"))
        assertTrue(prompt.contains("fim▁end"))
    }

    @Test
    fun `codestral template puts the suffix first`() {
        val prompt = FimTemplate.CODESTRAL.buildPrompt("PRE", "SUF")
        assertEquals("[SUFFIX]SUF[PREFIX]PRE[MIDDLE]", prompt)
    }

    @Test
    fun `none template returns the raw prefix`() {
        assertEquals("raw", FimTemplate.NONE.buildPrompt("raw", "ignored"))
    }

    @Test
    fun `unknown id falls back to qwen`() {
        assertEquals(FimTemplate.QWEN, FimTemplate.fromId("does-not-exist"))
        assertEquals(FimTemplate.CODE_LLAMA, FimTemplate.fromId("code_llama"))
    }
}
