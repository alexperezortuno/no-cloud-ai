package dev.nocloud.ai.chat

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import dev.nocloud.ai.core.ChatMessage
import dev.nocloud.ai.core.ToolCall
import dev.nocloud.ai.util.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.KeyStroke
import javax.swing.Timer

/**
 * The shared chat panel used by both the Chat and the Agent tab.
 *
 * Rendering happens on the EDT. Text produced on a background thread is queued and
 * flushed by a short Swing timer so that a fast model cannot flood the EDT with
 * individual repaints.
 */
class ChatSurface(
    private val project: Project,
    private val handler: ConversationHandler,
    private val emptyStateText: String,
) : SimpleToolWindowPanel(true, true) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val history = mutableListOf<ChatMessage>()

    private val messageList = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    private val scrollPane = JBScrollPane(messageList).apply {
        border = JBUI.Borders.empty()
        viewport.isOpaque = false
    }
    private val inputArea = JBTextArea(4, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "Ask anything. Use @ to reference project files."
    }
    private val sendButton = JButton("Send").apply { isFocusable = false }
    private val statusLabel = JBLabel("").apply { foreground = JBColor.GRAY }

    private val pendingText = StringBuilder()
    private val pendingToolCalls = mutableListOf<Pair<ToolCall, String>>()
    private var activeAssistantPanel: AssistantPanel? = null
    private var running = false
    private var mentionController: FileMentionController? = null

    private val flushTimer = Timer(30) { flushPending() }

    init {
        val inputPanel = JPanel(BorderLayout(6, 6)).apply {
            border = JBUI.Borders.empty(6)
            add(JBScrollPane(inputArea), BorderLayout.CENTER)
            add(
                JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0)).apply {
                    add(statusLabel)
                    add(sendButton)
                },
                BorderLayout.EAST,
            )
        }

        setContent(
            JPanel(BorderLayout()).apply {
                add(scrollPane, BorderLayout.CENTER)
                add(inputPanel, BorderLayout.SOUTH)
            },
        )

        installSendShortcut()
        sendButton.addActionListener { submit() }
        inputArea.addKeyListener(
            object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                        submit()
                        e.consume()
                    }
                }
            },
        )

        FileMentionController(project, inputArea).also { mentionController = it }

        addEmptyState()

        // Drains queued deltas onto the EDT at a fixed rate.
        flushTimer.start()
    }

    private fun installSendShortcut() {
        val inputMap = inputArea.getInputMap(JComponent.WHEN_FOCUSED)
        val actionMap = inputArea.actionMap
        inputMap.put(KeyStroke.getKeyStroke("shift ENTER"), "insert-break")
        inputMap.put(KeyStroke.getKeyStroke("ENTER"), "no-cloud-ai-send")
        actionMap.put(
            "no-cloud-ai-send",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = submit()
            },
        )
    }

    private fun addEmptyState() {
        val label = JBLabel(emptyStateText).apply {
            foreground = JBColor.GRAY
            border = JBUI.Borders.empty(24)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
        messageList.add(label)
        messageList.revalidate()
        messageList.repaint()
    }

    private fun submit() {
        if (running) return
        val text = inputArea.text.trim()
        if (text.isEmpty()) return

        inputArea.text = ""
        running = true
        sendButton.isEnabled = false
        statusLabel.text = "Thinking..."

        addUserMessage(text)

        scope.launch {
            val answer = StringBuilder()
            val sink = object : ResponseSink {
                override fun appendText(delta: String) {
                    answer.append(delta)
                    synchronized(pendingText) { pendingText.append(delta) }
                }

                override fun appendToolCall(call: ToolCall, result: String) {
                    synchronized(pendingToolCalls) { pendingToolCalls += call to result }
                }

                override fun finish() {
                    statusLabel.text = ""
                }

                override fun fail(message: String) {
                    statusLabel.text = ""
                    Notifications.error(project, message)
                }
            }

            try {
                // The handler returns the messages it produced, including the tool
                // calls and their results, so the agent remembers them next turn.
                val produced = handler.respond(text, history.toList(), sink)
                history += produced
            } catch (e: Exception) {
                sink.fail(e.message ?: e::class.java.simpleName)
            } finally {
                ApplicationManager.getApplication().invokeLater {
                    flushPending()
                    running = false
                    sendButton.isEnabled = true
                }
            }
        }
    }

    /** Moves queued text and tool calls onto the UI. Must run on the EDT. */
    private fun flushPending() {
        val text: String
        val toolCalls: List<Pair<ToolCall, String>>
        synchronized(pendingText) {
            text = pendingText.toString()
            pendingText.setLength(0)
        }
        synchronized(pendingToolCalls) {
            toolCalls = pendingToolCalls.toList()
            pendingToolCalls.clear()
        }

        if (text.isEmpty() && toolCalls.isEmpty()) return

        val panel = activeAssistantPanel ?: AssistantPanel().also {
            activeAssistantPanel = it
            messageList.add(it)
        }
        if (text.isNotEmpty()) panel.appendText(text)
        toolCalls.forEach { (call, result) -> panel.appendToolCall(call, result) }
        messageList.revalidate()
        messageList.repaint()
        scrollPane.verticalScrollBar.value = scrollPane.verticalScrollBar.maximum
    }

    private fun addUserMessage(text: String) {
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 6)
            add(
                JBLabel("<html>${escapeHtml(text)}</html>").apply { border = JBUI.Borders.empty(6) },
                BorderLayout.CENTER,
            )
        }
        messageList.add(panel)
        messageList.revalidate()
        messageList.repaint()
        scrollPane.verticalScrollBar.value = scrollPane.verticalScrollBar.maximum
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\n", "<br/>")

    /** Renders the assistant side of the conversation. */
    private class AssistantPanel : JPanel(BorderLayout()) {
        private val textPane = JTextPane().apply {
            isEditable = false
            isOpaque = false
            border = JBUI.Borders.empty(6)
            putClientProperty(JTextPane.HONOR_DISPLAY_PROPERTIES, true)
            editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
            contentType = "text/html"
        }
        private val toolPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        private val markdown = StringBuilder()

        init {
            add(textPane, BorderLayout.CENTER)
            add(toolPanel, BorderLayout.SOUTH)
        }

        fun appendText(delta: String) {
            markdown.append(delta)
            textPane.text = Markdown.toHtml(markdown.toString())
            textPane.caretPosition = textPane.document.length
        }

        fun appendToolCall(call: dev.nocloud.ai.core.ToolCall, result: String) {
            val label = JBLabel("<html><b>${escapeHtml(call.name)}</b> &mdash; ${escapeHtml(result.take(400))}</html>").apply {
                foreground = JBColor.GRAY
                border = JBUI.Borders.empty(4, 6)
            }
            toolPanel.add(label)
            toolPanel.revalidate()
            toolPanel.repaint()
        }

        private fun escapeHtml(text: String): String = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    fun dispose() {
        flushTimer.stop()
        scope.cancel()
        mentionController?.dispose()
    }
}
