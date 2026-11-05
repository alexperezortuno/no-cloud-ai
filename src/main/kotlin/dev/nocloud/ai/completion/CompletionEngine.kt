package dev.nocloud.ai.completion

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import dev.nocloud.ai.provider.ProviderFactory
import dev.nocloud.ai.settings.LocalAiSettings
import dev.nocloud.ai.settings.ProviderConfigFactory

/** Builds an inline completion request from the editor state. */
object CompletionEngine {

    /** Maximum number of characters of context sent to the model on each side. */
    const val PREFIX_CHARS = 2000
    const val SUFFIX_CHARS = 1000

    suspend fun suggest(editor: Editor, document: Document): String {
        val settings = LocalAiSettings.instance().state
        if (!settings.inlineCompletionEnabled) return ""
        if (settings.ollamaModel.isBlank() && settings.llamaModel.isBlank()) return ""

        val context = ReadAction.computeBlocking<Pair<String, String>, RuntimeException> {
            val caret = editor.caretModel.offset.coerceIn(0, document.textLength)
            val prefix = document.getText(TextRange(0, caret)).takeLast(PREFIX_CHARS)
            val suffix = document.getText(TextRange(caret, document.textLength)).take(SUFFIX_CHARS)
            prefix to suffix
        }

        val config = ProviderConfigFactory.current()
        if (config.model.isBlank()) return ""

        val provider = ProviderFactory.create(config.kind)
        val template = settings.fimTemplate
        val prompt = template.buildPrompt(context.first, context.second)

        val raw = provider.completeFim(config, prompt, template.stopTokens)
        return CompletionPostProcessor.process(raw, context.first, context.second)
    }
}

/** Cleans up the raw model output before it is shown as ghost text. */
object CompletionPostProcessor {

    fun process(raw: String, prefix: String, suffix: String): String {
        if (raw.isEmpty()) return ""

        var text = raw.replace("\r\n", "\n")

        // The model sometimes repeats the line that is already being typed.
        val currentLine = prefix.substringAfterLast('\n')
        if (currentLine.isNotEmpty() && text.startsWith(currentLine)) {
            text = text.substring(currentLine.length)
        }

        // A leading newline means the model started a new line even though the caret
        // is already positioned on the right one.
        text = text.removePrefix("\n")

        // Drop a trailing echo of the text that already follows the caret.
        val nextLine = suffix.substringBefore('\n').trim()
        if (nextLine.isNotEmpty() && text.trimEnd().endsWith(nextLine)) {
            text = text.trimEnd().removeSuffix(nextLine)
        }

        return text.trimEnd()
    }
}
