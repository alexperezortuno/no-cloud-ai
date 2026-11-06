package dev.nocloud.ai.chat

import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
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
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.*
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder
import javax.swing.event.HyperlinkEvent
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

    private val attachments = ChatAttachments(project)
    private val attachmentPanel = JPanel().apply {
        layout = FlowLayout(FlowLayout.LEFT, 6, 0)
        isOpaque = false
        isVisible = false
    }

    private val pendingText = StringBuilder()
    private val pendingToolCalls = mutableListOf<Pair<ToolCall, String>>()
    private var activeAssistantPanel: AssistantPanel? = null
    private var running = false
    private var mentionController: FileMentionController? = null
    private var emptyState: JComponent? = null
    private var attachPopup: JBPopup? = null

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
            add(attachmentPanel, BorderLayout.SOUTH)
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

        scrollPane.viewport.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) = refitMessages()
        })

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
        emptyState = wrapper
        messageList.add(wrapper)
        messageList.revalidate()
        messageList.repaint()
    }

    /** Drops the placeholder as soon as the conversation has real content. */
    private fun removeEmptyState() {
        val state = emptyState ?: return
        emptyState = null
        messageList.remove(state)
        messageList.revalidate()
        messageList.repaint()
    }

    private fun submit() {
        if (running) return
        val text = inputArea.text.trim()
        val attached = attachments.snapshot()
        if (text.isEmpty() && attached.isEmpty()) return

        inputArea.text = ""
        attachments.clear()
        renderAttachments()
        running = true
        sendButton.isEnabled = false
        statusLabel.text = "Thinking..."

        // Every turn gets its own assistant bubble; the next response must not be
        // appended to the bubble of an earlier one.
        activeAssistantPanel = null

        addUserMessage(text, attached)

        scope.launch {
            // The prompt carries the content of every attachment, so the model can answer
            // about the files instead of only seeing their names. Reading them happens
            // here, off the UI thread.
            val prompt = buildPrompt(text, attached)

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
                val produced = handler.respond(prompt, history.toList(), sink)
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

    /** Combines the typed text with the content of the attached files. */
    private fun buildPrompt(text: String, files: List<VirtualFile>): String {
        val context = attachments.render(files)
        if (context.isEmpty()) return text
        return if (text.isEmpty()) context else "$text\n\n$context"
    }

    /**
     * A message container that hugs its content. Its maximum height is its preferred
     * height, so the vertical BoxLayout of the message list cannot hand it the
     * leftover space and stretch the bubble over the whole viewport.
     */
    private abstract class ShrinkToFitPanel(layout: LayoutManager) : JPanel(layout) {
        override fun getMaximumSize(): Dimension {
            val preferred = preferredSize
            return Dimension(Int.MAX_VALUE, preferred.height)
        }
    }

    /**
     * HTML text pane that reports the size of its content at the width it is
     * allowed to use, so bubbles can shrink to the height of the wrapped text.
     *
     * The minimum width is dropped on purpose. Swing derives it from the longest
     * unbreakable run of text, and a single long code line would then make the
     * bubble wider than the panel, which clips the message instead of wrapping it.
     */
    private inner class ChatTextPane : JTextPane() {
        override fun getPreferredSize(): Dimension {
            val preferred = super.getPreferredSize()
            val maxWidth = availableWidth(this)
            return if (maxWidth > 0 && preferred.width > maxWidth) {
                Dimension(maxWidth, preferred.height)
            } else {
                preferred
            }
        }

        override fun getMinimumSize(): Dimension {
            val minimum = super.getMinimumSize()
            return Dimension(0, minimum.height)
        }
    }

    /** Width the content of [component] may use before it has to wrap. */
    private fun availableWidth(component: JComponent): Int {
        val viewport = scrollPane.viewport
        var width = viewport.extentSize.width
        if (width <= 0) return 0
        var current: Component? = component.parent
        while (current != null) {
            if (current is JComponent) {
                width -= current.insets.left + current.insets.right
            }
            if (current === viewport) break
            current = current.parent
        }
        return width
    }

    /**
     * Lays the text out at the width it may use and refreshes the height of the
     * bubble that contains it.
     */
    private fun fitTextPane(textPane: JTextPane) {
        val width = availableWidth(textPane) - textPane.insets.left - textPane.insets.right
        if (width <= 0) return
        textPane.size = Dimension(width, Short.MAX_VALUE.toInt())
        textPane.size = Dimension(width, textPane.preferredSize.height)
        textPane.revalidate()
        textPane.repaint()
    }

    /** Re-fits every rendered message, e.g. after the viewport changed its width. */
    private fun refitMessages() {
        for (component in messageList.components) {
            when (component) {
                is AssistantPanel -> component.fitContent()
                is UserMessageBubble -> component.fitContent()
            }
        }
        messageList.revalidate()
        messageList.repaint()
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
            messageList.add(it)
        }
        if (text.isNotEmpty()) panel.appendText(text)
        toolCalls.forEach { (call, result) -> panel.appendToolCall(call, result) }
        messageList.revalidate()
        messageList.repaint()
        scrollPane.verticalScrollBar.value = scrollPane.verticalScrollBar.maximum
    }

    private fun addUserMessage(text: String, attached: List<VirtualFile>) {
        removeEmptyState()
        val bubble = UserMessageBubble(text, attached)
        messageList.add(bubble)
        messageList.revalidate()
        messageList.repaint()
        refitMessages()
        scrollPane.verticalScrollBar.value = scrollPane.verticalScrollBar.maximum
    }

    /**
     * Shows the files currently open in the IDE.
     *
     * Only the file name is listed; the full path stays available as a tooltip.
     */
    private fun showAttachMenu() {
        val openFiles = FileEditorManager.getInstance(project).openFiles
        if (openFiles.isEmpty()) {
            Notifications.info(project, "No files currently open in the editor")
            return
        }

        val listModel = DefaultListModel<VirtualFile>()
        openFiles.forEach { listModel.addElement(it) }

        val list = JBList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            visibleRowCount = 12
            cellRenderer = FileNameRenderer()
        }

        list.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 1) {
                        val selected = list.selectedValue ?: return
                        attachFile(selected)
                        closeAttachPopup()
                    }
                }
            },
        )

        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLine(JBColor.border())
            add(JBScrollPane(list), BorderLayout.CENTER)
        }

        closeAttachPopup()
        attachPopup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, list)
            .setRequestFocus(false)
            .setCancelOnClickOutside(true)
            .setResizable(false)
            .createPopup()
        attachPopup?.showUnderneathOf(attachButton)
    }

    /** Adds [file] to the pending attachments and refreshes the attachment chips. */
    private fun attachFile(file: VirtualFile) {
        if (!attachments.add(file)) {
            Notifications.info(project, "${file.name} is already attached")
            return
        }
        renderAttachments()
    }

    private fun closeAttachPopup() {
        attachPopup?.cancel()
        attachPopup = null
    }

    /** Rebuilds the row of chips that lists the pending attachments. */
    private fun renderAttachments() {
        attachmentPanel.removeAll()
        for (file in attachments.snapshot()) {
            attachmentPanel.add(attachmentChip(file))
        }
        attachmentPanel.isVisible = !attachments.isEmpty
        attachmentPanel.revalidate()
        attachmentPanel.repaint()
    }

    /** One chip: the file name with a button that removes the attachment. */
    private fun attachmentChip(file: VirtualFile): JComponent {
        val remove = JButton("×").apply {
            toolTipText = "Remove attachment"
            isFocusable = false
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            margin = Insets(0, 2, 0, 0)
            foreground = JBColor.GRAY
            font = UIUtil.getLabelFont().deriveFont(12f)
            addActionListener {
                attachments.remove(file)
                renderAttachments()
            }
        }
        val chip = JPanel(BorderLayout(4, 0)).apply {
            border = CompoundBorder(
                JBUI.Borders.customLine(JBColor.border()),
                EmptyBorder(2, 8, 2, 6),
            )
            add(JBLabel(file.name), BorderLayout.CENTER)
            add(remove, BorderLayout.EAST)
        }
        chip.toolTipText = attachments.pathOf(file)
        return chip
    }

    /** Renders only the file name, keeping the full path in the tooltip. */
    private inner class FileNameRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): java.awt.Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is VirtualFile) {
                text = value.name
                toolTipText = value.path
            }
            return component
        }
    }

    /** Renders the assistant side of the conversation with markdown support. */
    private inner class AssistantPanel : ShrinkToFitPanel(BorderLayout()) {
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
            fitContent()
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
            fitContent()
        }

        /** Re-wraps the text at the current width and resizes the bubble to match. */
        fun fitContent() {
            fitTextPane(textPane)
            revalidate()
            repaint()
        }
    }

    /** User message bubble - right aligned with distinct color */
    private inner class UserMessageBubble(text: String, attached: List<VirtualFile>) : ShrinkToFitPanel(BorderLayout()) {
        private val messagePane = createStyledTextPane()

        init {
            isOpaque = false
            border = JBUI.Borders.empty(6, 12)

            messagePane.text = "<html><body>${escapeHtml(text).replace("\n", "<br/>")}</body></html>"

            val content = JPanel(BorderLayout(0, 4)).apply {
                isOpaque = false
                add(messagePane, BorderLayout.CENTER)
                if (attached.isNotEmpty()) {
                    add(attachmentLabel(attached), BorderLayout.SOUTH)
                }
            }

            val bubble = BubblePanel(content, true)

            val wrapper = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                add(Box.createHorizontalGlue())
                add(bubble)
            }

            add(wrapper, BorderLayout.CENTER)
            fitContent()
        }

        /** Lists the files that travelled with this message. */
        private fun attachmentLabel(attached: List<VirtualFile>): JBLabel {
            val names = attached.joinToString(", ") { it.name }
            return JBLabel("Attached: $names").apply {
                foreground = JBColor.GRAY
                font = UIUtil.getLabelFont().deriveFont(11f)
                border = JBUI.Borders.empty(2, 0)
                toolTipText = attached.joinToString("\n") { attachments.pathOf(it) }
            }
        }

        /** Re-wraps the text at the current width and resizes the bubble to match. */
        fun fitContent() {
            fitTextPane(messagePane)
            revalidate()
            repaint()
        }
    }

    /** Assistant message bubble - left aligned */
    private inner class AssistantBubble(content: JComponent) : ShrinkToFitPanel(BorderLayout()) {
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
    private inner class BubblePanel(content: JComponent, private val isUser: Boolean) : ShrinkToFitPanel(BorderLayout()) {
        private val bubbleColor = if (isUser) {
            JBColor(0xF0F0F0, 0x2A2A2A)
        } else {
            JBColor.namedColor("Panel.background", JBColor.WHITE)
        }
        private val textColor = JBColor.namedColor("textColor", JBColor.BLACK)
        private val borderColor = JBColor.namedColor("SeparatorColor", JBColor.GRAY)

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
            } else {
                // The content may be a container around the text pane, for example when
                // a message carries attachments.
                findTextPane(content)?.let { pane ->
                    pane.foreground = textColor
                    pane.background = bubbleColor
                    pane.caretColor = textColor
                }
            }
        }

        /** First text pane inside [component], or null when there is none. */
        private fun findTextPane(component: JComponent): JTextPane? {
            if (component is JTextPane) return component
            for (child in component.components) {
                if (child is JComponent) {
                    findTextPane(child)?.let { return it }
                }
            }
            return null
        }

        /** The bubble is exactly as wide as its content, so it never spans the whole view. */
        override fun getMaximumSize(): Dimension = preferredSize

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
        return ChatTextPane().apply {
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
            styleSheet.addRule(ChatCodeStyle.css())
            styleSheet.addRule("""
                body {
                    font-family: ${UIUtil.getLabelFont().family};
                    font-size: 13px;
                    color: ${JBColor.namedColor("textColor", JBColor.BLACK).toHexString()};
                    margin: 0;
                    padding: 0;
                    line-height: 1.5;
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
                .hljs { color: ${ChatCodeStyle.foregroundHex}; }
                .hljs-keyword, .hljs-selector-tag, .hljs-built_in, .hljs-name, .hljs-tag { color: #C586C0; }
                .hljs-string, .hljs-title, .hljs-section, .hljs-attribute, .hljs-literal, .hljs-template-tag, .hljs-template-variable, .hljs-type, .hljs-addition { color: #CE9178; }
                .hljs-comment, .hljs-quote, .hljs-deletion, .hljs-meta { color: #6A9955; }
                .hljs-number, .hljs-regexp, .hljs-symbol, .hljs-bullet, .hljs-link { color: #B5CEA8; }
                .hljs-variable, .hljs-template-variable, .hljs-params, .hljs-function { color: #9CDCFE; }
                .hljs-function .hljs-title { color: #DCDCAA; }
                .hljs-attr, .hljs-property, .hljs-class .hljs-title { color: #4EC9B0; }
                .hljs-operator, .hljs-punctuation { color: ${ChatCodeStyle.foregroundHex}; }
            """.trimIndent())
        }
    }

    /** Escapes the characters that would otherwise be interpreted as HTML markup. */
    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    fun dispose() {
        flushTimer.stop()
        scope.cancel()
        mentionController?.dispose()
        closeAttachPopup()
    }

    // Extension to convert JBColor to hex string for CSS
    private fun JBColor.toHexString(): String {
        return String.format("#%02X%02X%02X", red, green, blue)
    }
}
