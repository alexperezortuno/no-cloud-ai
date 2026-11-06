package dev.nocloud.ai.chat

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * The files the user attached to the next message.
 *
 * An attachment is not a mention: the content of every attached file travels with the
 * prompt, so the model can answer about it without guessing. Files are read through
 * the editor document when one exists, so unsaved changes are part of the attachment.
 */
class ChatAttachments(private val project: Project) {

    private val files = mutableListOf<VirtualFile>()

    val isEmpty: Boolean get() = files.isEmpty()

    /** Adds [file] unless it is already attached. Returns false for a duplicate. */
    fun add(file: VirtualFile): Boolean {
        if (files.any { it == file }) return false
        files += file
        return true
    }

    fun remove(file: VirtualFile) {
        files.remove(file)
    }

    fun clear() {
        files.clear()
    }

    fun snapshot(): List<VirtualFile> = files.toList()

    /** Path used to identify the file for the model; relative to the project when possible. */
    fun pathOf(file: VirtualFile): String {
        val basePath = project.basePath ?: return file.path
        val prefix = basePath.trimEnd('/') + "/"
        return if (file.path.startsWith(prefix)) file.path.substring(prefix.length) else file.path
    }

    /**
     * Renders [files] for the prompt; the files no longer have to be attached.
     *
     * Binary and oversized files are reported instead of being inlined, so the model
     * knows they are there. The total size is bounded because the local model has a
     * limited context window.
     */
    fun render(files: List<VirtualFile>): String {
        if (files.isEmpty()) return ""

        val blocks = mutableListOf<String>()
        var remaining = MAX_TOTAL_CHARS
        for (file in files) {
            if (remaining <= 0) break
            val block = renderFile(file, remaining)
            blocks += block
            remaining -= block.length
        }
        val dropped = files.size - blocks.size
        val note = if (dropped > 0) "\n\n[$dropped more attached file(s) were left out to fit the context window]" else ""

        return "Attached files:\n\n" + blocks.joinToString("\n\n") + note
    }

    private fun renderFile(file: VirtualFile, limit: Int): String {
        val path = pathOf(file)
        val content = readContent(file)
        val body = when {
            content == null -> "[binary or unreadable file]"
            content.length > limit -> content.take(limit) + "\n... [truncated]"
            else -> content
        }
        return "--- BEGIN FILE: $path ---\n$body\n--- END FILE: $path ---"
    }

    /** Reads the file text, preferring the editor document over the saved bytes. */
    private fun readContent(file: VirtualFile): String? = ReadAction.computeBlocking<String?, RuntimeException> {
        if (file.isDirectory) return@computeBlocking null
        val document = FileDocumentManager.getInstance().getDocument(file)
        if (document != null) {
            document.text
        } else if (file.fileType.isBinary) {
            null
        } else {
            runCatching { String(file.contentsToByteArray(), Charsets.UTF_8) }.getOrNull()
        }
    }

    private companion object {
        /** Maximum number of characters inlined for all attached files together. */
        const val MAX_TOTAL_CHARS = 200_000
    }
}
