package dev.nocloud.ai.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.nocloud.ai.completion.FimTemplate
import dev.nocloud.ai.core.ProviderConfig
import dev.nocloud.ai.core.ProviderKind
import dev.nocloud.ai.provider.ProviderFactory
import dev.nocloud.ai.util.Notifications
import kotlinx.coroutines.runBlocking
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/** Settings page for the local backends. */
class LocalAiConfigurable : Configurable {

    private val ollamaActiveBox = JBCheckBox("Ollama")
    private val llamaActiveBox = JBCheckBox("llama.cpp")

    private val ollamaUrlField = JBTextField(28)
    private val ollamaKeyField = JBPasswordField()
    private val llamaUrlField = JBTextField(28)
    private val llamaKeyField = JBPasswordField()

    private val temperatureField = JBTextField(8)
    private val maxTokensField = JBTextField(8)
    private val contextSizeField = JBTextField(8)
    private val agentIterationsField = JBTextField(6)

    private val disableThinkingBox = JBCheckBox("Disable model thinking (enable_thinking = false)")
    private val agentEnabledBox = JBCheckBox("Enable the agent and its tools")
    private val inlineCompletionBox = JBCheckBox("Enable inline completion (ghost text)")
    private val fimTemplateCombo = ComboBox(FimTemplate.entries.toTypedArray())
    private val requireToolConfirmationBox = JBCheckBox("Require confirmation before executing tools that modify files or run commands")

    override fun getDisplayName(): String = "No Cloud AI"

    override fun createComponent(): JComponent {
        val testOllama = JButton("Test connection").apply { addActionListener { testConnection(ProviderKind.OLLAMA) } }
        val testLlama = JButton("Test connection").apply { addActionListener { testConnection(ProviderKind.LLAMA_CPP) } }

        val activeProvidersPanel = JPanel(GridLayout(1, 2, 8, 0)).apply {
            add(ollamaActiveBox)
            add(llamaActiveBox)
            border = JBUI.Borders.emptyLeft(8)
        }

        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Active providers:", activeProvidersPanel)
            .addSeparator()
            .addLabeledComponent("Ollama URL:", ollamaUrlField)
            .addLabeledComponent("Ollama API key:", ollamaKeyField)
            .addLabeledComponent("", withButton(JPanel(), testOllama))
            .addSeparator()
            .addLabeledComponent("llama.cpp URL:", llamaUrlField)
            .addLabeledComponent("llama.cpp API key:", llamaKeyField)
            .addLabeledComponent("", withButton(JPanel(), testLlama))
            .addSeparator()
            .addLabeledComponent("Temperature:", temperatureField)
            .addLabeledComponent("Max tokens:", maxTokensField)
            .addLabeledComponent("Context size:", contextSizeField)
            .addComponent(disableThinkingBox)
            .addSeparator()
            .addComponent(agentEnabledBox)
            .addLabeledComponent("Max tool iterations:", agentIterationsField)
            .addComponent(inlineCompletionBox)
            .addComponent(requireToolConfirmationBox)
            .addLabeledComponent("FIM template:", fimTemplateCombo)
            .addComponentFillVertically(JPanel(), 0)
            .panel
            .apply { border = JBUI.Borders.empty(8) }

        reset()
        return panel
    }

    /** `JPasswordField.getText()` is deprecated; read the password directly instead. */
    private fun passwordText(field: javax.swing.JPasswordField): String = String(field.password)

    private fun withButton(panel: JPanel, button: JButton): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(panel)
            add(button)
        }

    override fun isModified(): Boolean {
        val state = LocalAiSettings.instance().state
        val activeProviders = mutableSetOf<ProviderKind>()
        if (ollamaActiveBox.isSelected) activeProviders.add(ProviderKind.OLLAMA)
        if (llamaActiveBox.isSelected) activeProviders.add(ProviderKind.LLAMA_CPP)

        return activeProviders != state.activeProviders ||
            ollamaUrlField.text != state.ollamaBaseUrl ||
            passwordText(ollamaKeyField) != (Credentials.get(Credentials.KEY_OLLAMA) ?: "") ||
            llamaUrlField.text != state.llamaBaseUrl ||
            passwordText(llamaKeyField) != (Credentials.get(Credentials.KEY_LLAMA_CPP) ?: "") ||
            temperatureField.text != state.temperature.toString() ||
            maxTokensField.text != state.maxTokens.toString() ||
            contextSizeField.text != state.contextSize.toString() ||
            agentIterationsField.text != state.agentMaxIterations.toString() ||
            disableThinkingBox.isSelected != state.disableThinking ||
            agentEnabledBox.isSelected != state.agentEnabled ||
            inlineCompletionBox.isSelected != state.inlineCompletionEnabled ||
            requireToolConfirmationBox.isSelected != state.requireToolConfirmation ||
            fimTemplateCombo.selectedItem != state.fimTemplate
    }

    override fun apply() {
        val state = LocalAiSettings.instance().state
        state.activeProviders.clear()
        if (ollamaActiveBox.isSelected) state.activeProviders.add(ProviderKind.OLLAMA)
        if (llamaActiveBox.isSelected) state.activeProviders.add(ProviderKind.LLAMA_CPP)

        // Backward compatibility: keep the deprecated single provider field in sync
        state.provider = state.activeProviders.firstOrNull() ?: ProviderKind.OLLAMA

        state.ollamaBaseUrl = ollamaUrlField.text.trim()
        state.llamaBaseUrl = llamaUrlField.text.trim()
        state.temperature = temperatureField.text.trim().toDoubleOrNull() ?: 0.7
        state.maxTokens = maxTokensField.text.trim().toIntOrNull() ?: 4096
        state.contextSize = contextSizeField.text.trim().toIntOrNull() ?: 8192
        state.agentMaxIterations = agentIterationsField.text.trim().toIntOrNull() ?: 20
        state.disableThinking = disableThinkingBox.isSelected
        state.agentEnabled = agentEnabledBox.isSelected
        state.inlineCompletionEnabled = inlineCompletionBox.isSelected
        state.requireToolConfirmation = requireToolConfirmationBox.isSelected
        state.fimTemplate = fimTemplateCombo.selectedItem as? FimTemplate ?: state.fimTemplate

        Credentials.set(Credentials.KEY_OLLAMA, passwordText(ollamaKeyField))
        Credentials.set(Credentials.KEY_LLAMA_CPP, passwordText(llamaKeyField))
    }

    override fun reset() {
        val state = LocalAiSettings.instance().state
        ollamaActiveBox.isSelected = ProviderKind.OLLAMA in state.activeProviders
        llamaActiveBox.isSelected = ProviderKind.LLAMA_CPP in state.activeProviders

        ollamaUrlField.text = state.ollamaBaseUrl
        llamaUrlField.text = state.llamaBaseUrl
        temperatureField.text = state.temperature.toString()
        maxTokensField.text = state.maxTokens.toString()
        contextSizeField.text = state.contextSize.toString()
        agentIterationsField.text = state.agentMaxIterations.toString()
        disableThinkingBox.isSelected = state.disableThinking
        agentEnabledBox.isSelected = state.agentEnabled
        inlineCompletionBox.isSelected = state.inlineCompletionEnabled
        requireToolConfirmationBox.isSelected = state.requireToolConfirmation
        fimTemplateCombo.selectedItem = state.fimTemplate
        ollamaKeyField.text = Credentials.get(Credentials.KEY_OLLAMA) ?: ""
        llamaKeyField.text = Credentials.get(Credentials.KEY_LLAMA_CPP) ?: ""
    }

    /** Builds a configuration from what is currently shown in the form. */
    private fun configFor(kind: ProviderKind): ProviderConfig {
        val state = LocalAiSettings.instance().state
        return ProviderConfig(
            kind = kind,
            baseUrl = when (kind) {
                ProviderKind.OLLAMA -> ollamaUrlField.text.trim()
                ProviderKind.LLAMA_CPP -> llamaUrlField.text.trim()
            },
            model = when (kind) {
                ProviderKind.OLLAMA -> state.ollamaModel
                ProviderKind.LLAMA_CPP -> state.llamaModel
            },
            apiKey = Credentials.get(
                when (kind) {
                    ProviderKind.OLLAMA -> Credentials.KEY_OLLAMA
                    ProviderKind.LLAMA_CPP -> Credentials.KEY_LLAMA_CPP
                },
            ),
            temperature = temperatureField.text.trim().toDoubleOrNull() ?: state.temperature,
            maxTokens = maxTokensField.text.trim().toIntOrNull() ?: state.maxTokens,
            contextSize = contextSizeField.text.trim().toIntOrNull() ?: state.contextSize,
            disableThinking = disableThinkingBox.isSelected,
        )
    }

    private fun testConnection(kind: ProviderKind) {
        val config = configFor(kind)
        if (config.baseUrl.isBlank()) {
            Notifications.warning(null, "Set the server URL for ${kind.displayName} first.")
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { runBlocking { ProviderFactory.create(kind).listModels(config) } }
            ApplicationManager.getApplication().invokeLater {
                result.onSuccess { models ->
                    Notifications.info(null, "Connected to ${config.baseUrl} (${kind.displayName}). ${models.size} model(s) available.")
                }.onFailure { error ->
                    Notifications.error(null, "Cannot reach ${config.baseUrl} (${kind.displayName}): ${error.message}")
                }
            }
        }
    }
}