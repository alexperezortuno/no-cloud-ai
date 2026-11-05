package dev.nocloud.ai.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import dev.nocloud.ai.completion.FimTemplate
import dev.nocloud.ai.core.ProviderConfig
import dev.nocloud.ai.core.ProviderKind

/**
 * Persisted configuration for the local backends.
 *
 * A plain bean is used rather than `BaseState` so every field is non-null and the
 * defaults are obvious at the call site.
 */
@Service(Service.Level.APP)
@State(name = "NoCloudAiSettings", storages = [Storage("NoCloudAiSettings.xml")])
class LocalAiSettings : PersistentStateComponent<LocalAiSettingsState> {

    private var state = LocalAiSettingsState()

    override fun getState(): LocalAiSettingsState = state

    override fun loadState(state: LocalAiSettingsState) {
        this.state = state
    }

    companion object {
        fun instance(): LocalAiSettings = ApplicationManager.getApplication().getService(LocalAiSettings::class.java)
    }
}

class LocalAiSettingsState {
    var provider: ProviderKind = ProviderKind.OLLAMA

    var ollamaBaseUrl: String = "http://localhost:11434"
    var ollamaModel: String = ""

    var llamaBaseUrl: String = "http://localhost:8080"
    var llamaModel: String = ""

    var temperature: Double = 0.7
    var maxTokens: Int = 4096
    var contextSize: Int = 8192

    /** Send `chat_template_kwargs.enable_thinking = false` to llama.cpp. */
    var disableThinking: Boolean = false

    var agentEnabled: Boolean = true
    var agentMaxIterations: Int = 20

    var inlineCompletionEnabled: Boolean = false
    var fimTemplate: FimTemplate = FimTemplate.QWEN

    var debugHttp: Boolean = false
}

/**
 * Builds the runtime configuration for a backend.
 *
 * Read fresh on every request so that changing a setting takes effect immediately
 * without restarting the IDE.
 */
object ProviderConfigFactory {

    fun current(): ProviderConfig = forKind(LocalAiSettings.instance().state.provider)

    fun forKind(
        kind: ProviderKind,
        state: LocalAiSettingsState = LocalAiSettings.instance().state,
    ): ProviderConfig {
        val (baseUrl, model, apiKey) = when (kind) {
            ProviderKind.OLLAMA -> Triple(
                state.ollamaBaseUrl,
                state.ollamaModel,
                Credentials.get(Credentials.KEY_OLLAMA),
            )

            ProviderKind.LLAMA_CPP -> Triple(
                state.llamaBaseUrl,
                state.llamaModel,
                Credentials.get(Credentials.KEY_LLAMA_CPP),
            )
        }
        return ProviderConfig(
            kind = kind,
            baseUrl = baseUrl,
            model = model,
            apiKey = apiKey,
            temperature = state.temperature,
            maxTokens = state.maxTokens,
            contextSize = state.contextSize,
            disableThinking = state.disableThinking,
        )
    }
}
