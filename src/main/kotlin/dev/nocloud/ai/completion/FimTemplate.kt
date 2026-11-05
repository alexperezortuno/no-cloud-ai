package dev.nocloud.ai.completion

/**
 * Fill-in-the-middle prompt formats for the code models that are commonly run locally.
 *
 * The plugin builds the prompt itself and sends it as a plain `prompt`, so the same
 * template works against both llama.cpp and Ollama regardless of whether the server
 * would otherwise apply a chat template.
 */
enum class FimTemplate(
    val id: String,
    val displayName: String,
    val stopTokens: List<String>,
) {
    NONE(
        id = "none",
        displayName = "None (raw prefix)",
        stopTokens = emptyList(),
    ),
    QWEN(
        id = "qwen",
        displayName = "Qwen2.5 / Qwen3 Coder",
        stopTokens = listOf("<|fim_middle|>", "<|im_end|>", "<|endoftext|>"),
    ),
    DEEPSEEK(
        id = "deepseek",
        displayName = "DeepSeek Coder",
        stopTokens = listOf("<｜fim▁end｜>", "<｜end▁of▁sentence｜>"),
    ),
    CODE_LLAMA(
        id = "code_llama",
        displayName = "Code Llama",
        stopTokens = listOf("<EOT>"),
    ),
    CODESTRAL(
        id = "codestral",
        displayName = "Codestral",
        stopTokens = listOf("</s>"),
    ),
    STABLE_CODE(
        id = "stable_code",
        displayName = "Stable Code",
        stopTokens = listOf("<|endoftext|>"),
    ),
    ;

    fun buildPrompt(prefix: String, suffix: String): String = when (this) {
        NONE -> prefix
        QWEN, STABLE_CODE -> "<|fim_prefix|>$prefix<|fim_suffix|>${suffix}<|fim_middle|>"
        DEEPSEEK -> "<｜fim▁begin｜>$prefix<｜fim▁hole｜>$suffix<｜fim▁end｜>"
        CODE_LLAMA -> "<PRE> $prefix <SUF> $suffix <MID>"
        CODESTRAL -> "[SUFFIX]$suffix[PREFIX]$prefix[MIDDLE]"
    }

    companion object {
        fun fromId(id: String?): FimTemplate = entries.firstOrNull { it.id == id } ?: QWEN
    }
}
