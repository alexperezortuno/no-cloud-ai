package dev.nocloud.ai.chat

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * Adds `@path` completion to the chat input.
 *
 * Typing `@` opens a popup listing the project files; clicking one inserts its path.
 * The popup never takes focus so the user can keep typing to narrow the list.
 *
 * The file index is cached and invalidated when files are created, deleted, moved or
 * copied inside the project. The rebuild itself is lazy: it happens the next time the
 * popup is opened, never while the user is typing.
 */
class FileMentionController(
    private val project: Project,
    private val textArea: com.intellij.ui.components.JBTextArea,
) : Disposable {

    private val listModel = DefaultListModel<String>()
    private val list = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 12
    }

    private val allFiles = CopyOnWriteArrayList<String>()

    @Volatile
    private var loaded = false

    @Volatile
    private var dirty = false

    @Volatile
    private var loading = false

    private var popup: JBPopup? = null
    private var tokenStart = -1

    private val connection = project.messageBus.connect(this)

    init {
        // Only structural changes affect the list of files; a save does not.
        connection.subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { it.isStructuralChange() }) {
                        dirty = true
                    }
                }
            },
        )

        list.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 1) {
                        insertSelected()
                    }
                }
            },
        )

        textArea.document.addDocumentListener(
            object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent) = onTextChanged()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent) = onTextChanged()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent) = onTextChanged()
            },
        )
    }

    private fun VFileEvent.isStructuralChange(): Boolean =
        this is VFileCreateEvent || this is VFileDeleteEvent || this is VFileMoveEvent || this is VFileCopyEvent

    /**
     * Rebuilds the file index when it is missing or stale.
     *
     * Runs on a background thread; the popup is refreshed once the new list is ready.
     */
    private fun ensureLoaded() {
        if (loading) return
        if (loaded && !dirty) return

        loading = true
        Thread({
            val files = collectFiles()
            allFiles.clear()
            allFiles.addAll(files)
            loaded = true
            dirty = false
            loading = false
            ApplicationManager.getApplication().invokeLater { refreshList(currentToken()) }
        }, "no-cloud-ai-file-index").apply { isDaemon = true }.start()
    }

    private fun collectFiles(): List<String> {
        val base = project.basePath ?: return emptyList()
        return ReadAction.computeBlocking<List<String>, RuntimeException> {
            val collected = mutableListOf<String>()
            ProjectFileIndex.getInstance(project).iterateContent { file ->
                if (!file.isDirectory && !file.fileType.isBinary && file.length < MAX_FILE_SIZE) {
                    collected += file.path.removePrefix("$base/")
                }
                collected.size < MAX_FILES
            }
            collected.sorted()
        }
    }

    private fun currentToken(): String {
        val caret = textArea.caretPosition.coerceAtLeast(0)
        val text = textArea.text
        if (caret > text.length) return ""
        val before = text.substring(0, caret)
        val at = before.lastIndexOf('@')
        if (at < 0) return ""
        val token = before.substring(at + 1)
        return if (token.contains(' ') || token.contains('\n')) "" else token
    }

    private fun onTextChanged() {
        val caret = textArea.caretPosition.coerceAtLeast(0)
        val text = textArea.text
        val before = if (caret <= text.length) text.substring(0, caret) else ""
        val at = before.lastIndexOf('@')
        if (at < 0) {
            hidePopup()
            return
        }
        val token = before.substring(at + 1)
        if (token.contains(' ') || token.contains('\n') || token.length > 80) {
            hidePopup()
            return
        }
        tokenStart = at
        ensureLoaded()
        refreshList(token)
    }

    private fun refreshList(token: String) {
        val source = allFiles
        if (source.isEmpty()) return

        val matches = if (token.isEmpty()) {
            source
        } else {
            source.filter { it.contains(token, ignoreCase = true) }
        }.take(MAX_SUGGESTIONS)

        listModel.clear()
        matches.forEach { listModel.addElement(it) }
        if (matches.isEmpty()) {
            hidePopup()
            return
        }
        list.selectedIndex = 0
        showPopup()
    }

    private fun showPopup() {
        if (popup?.isVisible == true) {
            popup?.pack(true, true)
            return
        }
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLine(JBColor.border())
            add(list, BorderLayout.CENTER)
        }
        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, list)
            .setRequestFocus(false)
            .setCancelOnClickOutside(true)
            .setResizable(false)
            .createPopup()
        popup?.showUnderneathOf(textArea)
    }

    private fun hidePopup() {
        popup?.cancel()
        popup = null
    }

    private fun insertSelected() {
        val selected = list.selectedValue ?: return
        val caret = textArea.caretPosition.coerceAtLeast(0)
        val text = textArea.text
        if (tokenStart < 0 || tokenStart > text.length || caret > text.length) {
            hidePopup()
            return
        }
        val replacement = "@$selected "
        val updated = text.substring(0, tokenStart) + replacement + text.substring(caret)
        textArea.text = updated
        textArea.caretPosition = tokenStart + replacement.length
        hidePopup()
    }

    override fun dispose() {
        hidePopup()
    }

    private companion object {
        const val MAX_FILES = 20_000
        const val MAX_SUGGESTIONS = 40
        const val MAX_FILE_SIZE = 2_000_000L
    }
}
