package dev.nocloud.ai.completion

import com.intellij.codeInsight.inline.completion.DebouncedInlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertEnvironment
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertHandler
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderPresentation
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import dev.nocloud.ai.settings.LocalAiSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Offers inline "ghost text" completions produced by the configured local model.
 *
 * Registered through the `inline.completion.provider` extension point, so it runs
 * inside every editor of the IDE.
 */
class LocalInlineCompletionProvider : DebouncedInlineCompletionProvider() {

    override val id: InlineCompletionProviderID = InlineCompletionProviderID("NoCloudAiInlineCompletion")

    override val providerPresentation: InlineCompletionProviderPresentation =
        InlineCompletionProviderPresentation.dummy(this)

    override val insertHandler: InlineCompletionInsertHandler = InsertHandler

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        if (!LocalAiSettings.instance().state.inlineCompletionEnabled) return false
        return event is InlineCompletionEvent.DirectCall || event is InlineCompletionEvent.DocumentChange
    }

    override suspend fun getDebounceDelay(request: InlineCompletionRequest): Duration {
        // A manual request should feel instant; typing gets a short debounce.
        return if (request.event is InlineCompletionEvent.DirectCall) {
            Duration.ZERO
        } else {
            250.milliseconds
        }
    }

    override suspend fun getSuggestionDebounced(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val editor = request.editor
        val document = request.document

        val elements: Flow<InlineCompletionElement> = flow {
            val suggestion = runCatching { CompletionEngine.suggest(editor, document) }.getOrDefault("")
            if (suggestion.isNotEmpty()) {
                emit(InlineCompletionGrayTextElement(suggestion))
            }
        }

        return InlineCompletionSingleSuggestion.build(request, elements)
    }

    private object InsertHandler : InlineCompletionInsertHandler {
        override fun afterInsertion(
            environment: InlineCompletionInsertEnvironment,
            elements: List<InlineCompletionElement>,
        ) {
            // Nothing extra to do: the platform already inserted the text.
        }
    }
}
