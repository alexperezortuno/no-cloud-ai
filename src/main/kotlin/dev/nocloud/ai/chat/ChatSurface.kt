package dev.nocloud.ai.chat

import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.nocloud.ai.core.ChatMessage
import dev.nocloud.ai.core.ToolCall
import dev.nocloud.ai.util.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.*
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder
import javax.swing.event.HyperlinkEvent
import javax.swing.text.html.HTMLDocument
import javax.swing.text.html.HTMLEditorKit

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
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        getViewport().setBackground(JBColor.namedColor("Panel.background", JBColor.WHITE))
    }

    private val inputArea = JBTextArea(1, 1).apply {
        lineWrap = true
        wrapStyleWord = true
        font = UIUtil.getLabelFont().deriveFont(14f)
        emptyText.text = "Message No Cloud AI... (Shift+Enter for new line, Enter to send)"
    }
    private val inputScrollPane = JBScrollPane(inputArea).apply {
        border = JBUI.Borders.empty()
        viewport.isOpaque = false
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        preferredSize = Dimension(0, 60)
        maximumSize = Dimension(Integer.MAX_VALUE, 150)
    }
    private val sendButton = JButton(IconLoader.getIcon("/icons/send.svg", ChatSurface::class.java)).apply {
        toolTipText = "Send (Enter)"
        isFocusable = false
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        preferredSize = Dimension(28, 28)
        minimumSize = Dimension(28, 28)
        maximumSize = Dimension(28, 28)
        foreground = JBColor.namedColor("textColor", JBColor.LIGHT_GRAY)
    }
    private val attachButton = JButton(IconLoader.getIcon("/icons/add.svg", ChatSurface::class.java)).apply {
        toolTipText = "Attach file"
        isFocusable = false
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        preferredSize = Dimension(28, 28)
        minimumSize = Dimension(28, 28)
        maximumSize = Dimension(28, 28)
        foreground = JBColor.namedColor("textColor", JBColor.LIGHT_GRAY)
    }
    private val statusLabel = JBLabel("").apply {
        foreground = JBColor.GRAY
        font = UIUtil.getLabelFont().deriveFont(11f)
    }

    private val pendingText = StringBuilder()
    private val pendingToolCalls = mutableListOf<Pair<ToolCall, String>>()
    private var activeAssistantPanel: AssistantPanel? = null
    private var running = false
    private var mentionController: FileMentionController? = null

    private val flushTimer = Timer(30) { flushPending() }

    init {
        val inputContainer = JPanel(BorderLayout(8, 8)).apply {
            border = CompoundBorder(
                JBUI.Borders.customLine(JBColor.border()),
                EmptyBorder(8, 12, 8, 12),
            )
            background = JBColor.namedColor("Panel.background", JBColor.WHITE)

            add(attachButton, BorderLayout.WEST)
            add(inputScrollPane, BorderLayout.CENTER)

            val rightPanel = JPanel(BorderLayout(8, 0)).apply {
                add(statusLabel, BorderLayout.CENTER)
                add(sendButton, BorderLayout.EAST)
            }
            add(rightPanel, BorderLayout.EAST)
        }

        val inputPanel = JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(0, 0, 8, 0)
            add(inputContainer, BorderLayout.CENTER)
        }

        val messagePanel = JPanel(BorderLayout()).apply {
            add(scrollPane, BorderLayout.CENTER)
            add(inputPanel, BorderLayout.SOUTH)
        }

        setContent(
            JPanel(BorderLayout()).apply {
                add(scrollPane, BorderLayout.CENTER)
                add(inputPanel, BorderLayout.SOUTH)
            },
        )

        installSendShortcut()
        sendButton.addActionListener { submit() }
        attachButton.addActionListener { showAttachMenu() }
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
            alignmentX = Component.CENTER_ALIGNMENT
        }
        val wrapper = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(Box.createVerticalGlue())
            add(label)
            add(Box.createVerticalGlue())
        }
        messageList.add(wrapper)
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
                val produced = handler.respond(text, history.toList(), sink)
                history += produced
            } catch (e: Exception) {
                sink.fail(e.message ?: e::class.java.simpleName)
            } finally {
                ApplicationManager.getApplication().invokeLater {
                    flushPending()
                    running = false
                    sendButton.isEnabled = true
                    statusLabel.text = ""
                }
            }
        }
    }

    /** Moves queued deltas onto the UI. Must run on the EDT. */
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
            messageList.add(it, 0)
        }
        if (text.isNotEmpty()) panel.appendText(text)
        toolCalls.forEach { (call, result) -> panel.appendToolCall(call, result) }
        messageList.revalidate()
        messageList.repaint()
        scrollPane.verticalScrollBar.value = scrollPane.verticalScrollBar.maximum
    }

    private fun addUserMessage(text: String) {
        val bubble = UserMessageBubble(text)
        messageList.add(bubble, 0)
        messageList.revalidate()
        messageList.repaint()
        scrollPane.verticalScrollBar.value = scrollPane.verticalScrollBar.maximum
    }

    private fun showAttachMenu() {
        val group = DefaultActionGroup()
        group.add(com.intellij.ide.actions.OpenFileAction())
        val toolbar = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            .createActionToolbar("attachMenu", group, true)
        toolbar.setTargetComponent(attachButton)
        val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createActionGroupPopup("", group, com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT, com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
        popup.showUnderneathOf(attachButton)
    }

    /** Renders the assistant side of the conversation with markdown support. */
    private inner class AssistantPanel : JPanel(BorderLayout()) {
        private val textPane = createStyledTextPane()
        private val toolPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        private val markdown = StringBuilder()

        init {
            val contentPanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(4, 0)
                add(textPane, BorderLayout.CENTER)
                add(toolPanel, BorderLayout.SOUTH)
            }
            val bubble = AssistantBubble(contentPanel)
            add(bubble, BorderLayout.CENTER)
        }

        fun appendText(delta: String) {
            markdown.append(delta)
            textPane.text = Markdown.toHtml(markdown.toString())
            textPane.caretPosition = textPane.document.length
        }

        fun appendToolCall(call: ToolCall, result: String) {
            val label = JBLabel("<html><b>${escapeHtml(call.name)}</b> &mdash; ${escapeHtml(result.take(400))}</html>").apply {
                foreground = JBColor.GRAY
                border = JBUI.Borders.empty(4, 6)
                font = UIUtil.getLabelFont().deriveFont(12f)
            }
            toolPanel.add(label)
            toolPanel.revalidate()
            toolPanel.repaint()
        }

        private fun escapeHtml(text: String): String = text
            .replace("&", "&")
            .replace("<", "<")
            .replace(">", ">")
    }

    /** User message bubble - right aligned with distinct color */
    private inner class UserMessageBubble(text: String) : JPanel(BorderLayout()) {
        init {
            isOpaque = false
            border = JBUI.Borders.empty(6, 12)

            val bubbleContent = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                border = JBUI.Borders.empty(2, 0)
            }

            val messageLabel = JBLabel("<html>${escapeHtml(text)}</html>").apply {
                font = UIUtil.getLabelFont().deriveFont(13f)
                foreground = JBColor.WHITE
            }

            val bubble = BubblePanel(messageLabel, true)
            maximumSize = Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE)

            val wrapper = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                add(Box.createHorizontalGlue())
                add(bubble)
            }

            add(wrapper, BorderLayout.CENTER)
        }

        private fun escapeHtml(text: String): String = text
            .replace("&", "&")
            .replace("<", "<")
            .replace(">", ">")
            .replace("\n", "<br/>")
    }

    /** Assistant message bubble - left aligned */
    private inner class AssistantBubble(content: JComponent) : JPanel(BorderLayout()) {
        init {
            isOpaque = false
            border = JBUI.Borders.empty(6, 12)

            val wrapper = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                add(BubblePanel(content, false))
                add(Box.createHorizontalGlue())
            }

            add(wrapper, BorderLayout.CENTER)
        }
    }

    /** Custom panel with rounded corners and bubble styling */
    private inner class BubblePanel(content: JComponent, private val isUser: Boolean) : JPanel(BorderLayout()) {
        private val bubbleColor = if (isUser) {
            JBColor(0x007AFF, 0x0A84FF)
        } else {
            JBColor.namedColor("Panel.background", JBColor.WHITE)
        }
        private val textColor = if (isUser) JBColor.WHITE else JBColor.namedColor("textColor", JBColor.BLACK)
        private val borderColor = if (isUser) bubbleColor else JBColor.namedColor("SeparatorColor", JBColor.GRAY)

        init {
            isOpaque = false
            border = JBUI.Borders.empty(10, 14)
            background = bubbleColor

            val contentWrapper = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(content, BorderLayout.CENTER)
            }
            add(contentWrapper, BorderLayout.CENTER)

            if (content is JBLabel) {
                content.foreground = textColor
            } else if (content is JTextPane) {
                content.foreground = textColor
                content.background = bubbleColor
                content.caretColor = textColor
            }
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

            val width = this.width
            val height = this.height
            val arc = 20

            val shape = RoundRectangle2D.Double(0.0, 0.0, width.toDouble() - 1, height.toDouble() - 1, arc.toDouble(), arc.toDouble())
            g2.setColor(bubbleColor)
            g2.fill(shape)

            if (!isUser) {
                g2.setColor(borderColor)
                g2.setStroke(BasicStroke(1f))
                g2.draw(shape)
            }

            super.paintComponent(g)
        }
    }

    private fun createStyledTextPane(): JTextPane {
        return JTextPane().apply {
            isEditable = false
            isOpaque = false
            border = JBUI.Borders.empty(2, 0)
            putClientProperty(JTextPane.HONOR_DISPLAY_PROPERTIES, true)
            editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
            contentType = "text/html"

            addHyperlinkListener { e ->
                if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                    val desktop = java.awt.Desktop.getDesktop()
                    try {
                        desktop.browse(e.url.toURI())
                    } catch (ex: Exception) {
                        Notifications.error(project, "Failed to open link: ${ex.message}")
                    }
                }
            }

            val styleSheet = (editorKit as HTMLEditorKit).styleSheet
            styleSheet.addRule("""
                body {
                    font-family: ${UIUtil.getLabelFont().family};
                    font-size: 13px;
                    color: ${JBColor.namedColor("textColor", JBColor.BLACK).toHexString()};
                    margin: 0;
                    padding: 0;
                    line-height: 1.5;
                }
                pre {
                    background-color: ${JBColor(0x1E1E1E, 0x2D2D2D).toHexString()};
                    color: ${JBColor(0xD4D4D4, 0xD4D4D4).toHexString()};
                    padding: 12px;
                    border-radius: 8px;
                    overflow-x: auto;
                    margin: 8px 0;
                    font-family: 'Monospaced', 'Consolas', 'Menlo', monospace;
                    font-size: 12px;
                    line-height: 1.4;
                    border: 1px solid ${JBColor(0x3C3C3C, 0x4A4A4A).toHexString()};
                }
                code {
                    background-color: ${JBColor(0xF0F0F0, 0x3A3A3A).toHexString()};
                    color: ${JBColor(0xD73A49, 0xF97583).toHexString()};
                    padding: 2px 6px;
                    border-radius: 4px;
                    font-family: 'Monospaced', 'Consolas', 'Menlo', monospace;
                    font-size: 12px;
                }
                pre code {
                    background-color: transparent;
                    color: inherit;
                    padding: 0;
                    border-radius: 0;
                }
                blockquote {
                    border-left: 3px solid ${JBColor.namedColor("SeparatorColor", JBColor.GRAY).toHexString()};
                    margin: 8px 0;
                    padding-left: 12px;
                    color: ${JBColor.GRAY.toHexString()};
                    font-style: italic;
                }
                table {
                    border-collapse: collapse;
                    width: 100%;
                    margin: 8px 0;
                }
                th, td {
                    border: 1px solid ${JBColor.namedColor("SeparatorColor", JBColor.GRAY).toHexString()};
                    padding: 6px 10px;
                    text-align: left;
                }
                th {
                    background-color: ${JBColor.namedColor("HeaderBackground", JBColor.LIGHT_GRAY).toHexString()};
                }
                h1, h2, h3, h4, h5, h6 {
                    margin: 12px 0 6px;
                    font-weight: 600;
                    color: ${JBColor.namedColor("textColor", JBColor.BLACK).toHexString()};
                }
                h1 { font-size: 20px; }
                h2 { font-size: 18px; }
                h3 { font-size: 16px; }
                p { margin: 6px 0; }
                ul, ol { margin: 6px 0; padding-left: 24px; }
                li { margin: 2px 0; }
                a { color: ${JBColor(0x007AFF, 0x0A84FF).toHexString()}; text-decoration: none; }
                a:hover { text-decoration: underline; }
                hr { border: none; border-top: 1px solid ${JBColor.namedColor("SeparatorColor", JBColor.GRAY).toHexString()}; margin: 12px 0; }
                .hljs { color: #D4D4D4; }
                .hljs-keyword, .hljs-selector-tag, .hljs-built_in, .hljs-name, .hljs-tag { color: #C586C0; }
                .hljs-string, .hljs-title, .hljs-section, .hljs-attribute, .hljs-literal, .hljs-template-tag, .hljs-template-variable, .hljs-type, .hljs-addition { color: #CE9178; }
                .hljs-comment, .hljs-quote, .hljs-deletion, .hljs-meta { color: #6A9955; }
                .hljs-number, .hljs-regexp, .hljs-symbol, .hljs-bullet, .hljs-link { color: #B5CEA8; }
                .hljs-variable, .hljs-template-variable, .hljs-params, .hljs-function { color: #9CDCFE; }
                .hljs-function .hljs-title { color: #DCDCAA; }
                .hljs-attr, .hljs-property, .hljs-class .hljs-title { color: #4EC9B0; }
                .hljs-operator, .hljs-punctuation { color: #D4D4D4; }
            """.trimIndent())
        }
    }

    fun dispose() {
        flushTimer.stop()
        scope.cancel()
        mentionController?.dispose()
    }

    // Extension to convert JBColor to hex string for CSS
    private fun JBColor.toHexString(): String {
        return String.format("#%02X%02X%02X", red, green, blue)
    }
}
