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
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/** Settings page for the local backends. */
class LocalAiConfigurable : Configurable {

    private val providerCombo = ComboBox(ProviderKind.entries.toTypedArray())
    private val ollamaUrlField = JBTextField(28)
    private val ollamaModelCombo = ComboBox<String>()
    private val ollamaKeyField = JBPasswordField()
    private val llamaUrlField = JBTextField(28)
    private val llamaModelCombo = ComboBox<String>()
    private val llamaKeyField = JBPasswordField()

    private val temperatureField = JBTextField(8)
    private val maxTokensField = JBTextField(8)
    private val contextSizeField = JBTextField(8)
    private val agentIterationsField = JBTextField(6)

    private val disableThinkingBox = JBCheckBox("Disable model thinking (enable_thinking = false)")
    private val agentEnabledBox = JBCheckBox("Enable the agent and its tools")
    private val inlineCompletionBox = JBCheckBox("Enable inline completion (ghost text)")
    private val fimTemplateCombo = ComboBox(FimTemplate.entries.toTypedArray())

    override fun getDisplayName(): String = "No Cloud AI"

    override fun createComponent(): JComponent {
        val refreshOllama = JButton("Refresh models").apply { addActionListener { refreshModels(ProviderKind.OLLAMA) } }
        val refreshLlama = JButton("Refresh models").apply { addActionListener { refreshModels(ProviderKind.LLAMA_CPP) } }
        val testButton = JButton("Test connection").apply { addActionListener { testConnection() } }

        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Backend:", providerCombo)
            .addSeparator()
            .addLabeledComponent("Ollama URL:", ollamaUrlField)
            .addLabeledComponent("Ollama model:", withButton(ollamaModelCombo, refreshOllama))
            .addLabeledComponent("Ollama API key:", ollamaKeyField)
            .addSeparator()
            .addLabeledComponent("llama.cpp URL:", llamaUrlField)
            .addLabeledComponent("llama.cpp model:", withButton(llamaModelCombo, refreshLlama))
            .addLabeledComponent("llama.cpp API key:", llamaKeyField)
            .addSeparator()
            .addLabeledComponent("Temperature:", temperatureField)
            .addLabeledComponent("Max tokens:", maxTokensField)
            .addLabeledComponent("Context size:", contextSizeField)
            .addComponent(disableThinkingBox)
            .addSeparator()
            .addComponent(agentEnabledBox)
            .addLabeledComponent("Max tool iterations:", agentIterationsField)
            .addComponent(inlineCompletionBox)
            .addLabeledComponent("FIM template:", fimTemplateCombo)
            .addSeparator()
            .addComponent(testButton)
            .addComponentFillVertically(JPanel(), 0)
            .panel
            .apply { border = JBUI.Borders.empty(8) }

        reset()
        return panel
    }

    /** `JPasswordField.getText()` is deprecated; read the password directly instead. */
    private fun passwordText(field: javax.swing.JPasswordField): String = String(field.password)

    private fun withButton(combo: ComboBox<String>, button: JButton): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(combo)
            add(button)
        }

    override fun isModified(): Boolean {
        val state = LocalAiSettings.instance().state
        return providerCombo.selectedItem != state.provider ||
            ollamaUrlField.text != state.ollamaBaseUrl ||
            selectedModel(ProviderKind.OLLAMA) != state.ollamaModel ||
            passwordText(ollamaKeyField) != (Credentials.get(Credentials.KEY_OLLAMA) ?: "") ||
            llamaUrlField.text != state.llamaBaseUrl ||
            selectedModel(ProviderKind.LLAMA_CPP) != state.llamaModel ||
            passwordText(llamaKeyField) != (Credentials.get(Credentials.KEY_LLAMA_CPP) ?: "") ||
            temperatureField.text != state.temperature.toString() ||
            maxTokensField.text != state.maxTokens.toString() ||
            contextSizeField.text != state.contextSize.toString() ||
            agentIterationsField.text != state.agentMaxIterations.toString() ||
            disableThinkingBox.isSelected != state.disableThinking ||
            agentEnabledBox.isSelected != state.agentEnabled ||
            inlineCompletionBox.isSelected != state.inlineCompletionEnabled ||
            fimTemplateCombo.selectedItem != state.fimTemplate
    }

    override fun apply() {
        val state = LocalAiSettings.instance().state
        state.provider = providerCombo.selectedItem as? ProviderKind ?: state.provider
        state.ollamaBaseUrl = ollamaUrlField.text.trim()
        state.ollamaModel = selectedModel(ProviderKind.OLLAMA)
        state.llamaBaseUrl = llamaUrlField.text.trim()
        state.llamaModel = selectedModel(ProviderKind.LLAMA_CPP)
        state.temperature = temperatureField.text.trim().toDoubleOrNull() ?: 0.7
        state.maxTokens = maxTokensField.text.trim().toIntOrNull() ?: 4096
        state.contextSize = contextSizeField.text.trim().toIntOrNull() ?: 8192
        state.agentMaxIterations = agentIterationsField.text.trim().toIntOrNull() ?: 20
        state.disableThinking = disableThinkingBox.isSelected
        state.agentEnabled = agentEnabledBox.isSelected
        state.inlineCompletionEnabled = inlineCompletionBox.isSelected
        state.fimTemplate = fimTemplateCombo.selectedItem as? FimTemplate ?: state.fimTemplate

        Credentials.set(Credentials.KEY_OLLAMA, passwordText(ollamaKeyField))
        Credentials.set(Credentials.KEY_LLAMA_CPP, passwordText(llamaKeyField))
    }

    override fun reset() {
        val state = LocalAiSettings.instance().state
        providerCombo.selectedItem = state.provider
        ollamaUrlField.text = state.ollamaBaseUrl
        llamaUrlField.text = state.llamaBaseUrl
        temperatureField.text = state.temperature.toString()
        maxTokensField.text = state.maxTokens.toString()
        contextSizeField.text = state.contextSize.toString()
        agentIterationsField.text = state.agentMaxIterations.toString()
        disableThinkingBox.isSelected = state.disableThinking
        agentEnabledBox.isSelected = state.agentEnabled
        inlineCompletionBox.isSelected = state.inlineCompletionEnabled
        fimTemplateCombo.selectedItem = state.fimTemplate
        ollamaKeyField.text = Credentials.get(Credentials.KEY_OLLAMA) ?: ""
        llamaKeyField.text = Credentials.get(Credentials.KEY_LLAMA_CPP) ?: ""

        setModels(ProviderKind.OLLAMA, state.ollamaModel)
        setModels(ProviderKind.LLAMA_CPP, state.llamaModel)
    }

    private fun selectedModel(kind: ProviderKind): String = when (kind) {
        ProviderKind.OLLAMA -> ollamaModelCombo.selectedItem as? String ?: ""
        ProviderKind.LLAMA_CPP -> llamaModelCombo.selectedItem as? String ?: ""
    }

    private fun setModels(kind: ProviderKind, model: String) {
        val combo = when (kind) {
            ProviderKind.OLLAMA -> ollamaModelCombo
            ProviderKind.LLAMA_CPP -> llamaModelCombo
        }
        combo.removeAllItems()
        if (model.isNotBlank()) combo.addItem(model)
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
            model = selectedModel(kind),
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

    private fun refreshModels(kind: ProviderKind) {
        val config = configFor(kind)
        if (config.baseUrl.isBlank()) {
            Notifications.warning(null, "Set the server URL first.")
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { runBlocking { ProviderFactory.create(kind).listModels(config) } }
            ApplicationManager.getApplication().invokeLater {
                result.onSuccess { models ->
                    val combo = when (kind) {
                        ProviderKind.OLLAMA -> ollamaModelCombo
                        ProviderKind.LLAMA_CPP -> llamaModelCombo
                    }
                    val current = combo.selectedItem as? String
                    combo.removeAllItems()
                    models.forEach { combo.addItem(it.id) }
                    if (current != null && models.none { it.id == current }) {
                        combo.addItem(current)
                    }
                    if (combo.itemCount > 0 && combo.selectedIndex < 0) {
                        combo.selectedIndex = 0
                    }
                    Notifications.info(null, "Found ${models.size} model(s) on ${config.baseUrl}.")
                }.onFailure { error ->
                    Notifications.error(null, "Cannot reach ${config.baseUrl}: ${error.message}")
                }
            }
        }
    }

    private fun testConnection() {
        val kind = providerCombo.selectedItem as? ProviderKind ?: ProviderKind.OLLAMA
        val config = configFor(kind)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { runBlocking { ProviderFactory.create(kind).listModels(config) } }
            ApplicationManager.getApplication().invokeLater {
                result.onSuccess { models ->
                    Notifications.info(null, "Connected to ${config.baseUrl}. ${models.size} model(s) available.")
                }.onFailure { error ->
                    Notifications.error(null, "Cannot reach ${config.baseUrl}: ${error.message}")
                }
            }
        }
    }
}
