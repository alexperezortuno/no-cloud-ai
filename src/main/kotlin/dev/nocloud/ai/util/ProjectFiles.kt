package dev.nocloud.ai.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path

/** Helpers for turning model-supplied paths into real files. */
object ProjectFiles {

    /** Root directory of the project, or the user home when the project has none. */
    fun root(project: Project): String = project.basePath ?: System.getProperty("user.home")

    /** Resolves [rawPath] against the project root when it is relative. */
    fun resolvePath(project: Project, rawPath: String): Path {
        val trimmed = rawPath.trim()
        val path = Path.of(trimmed)
        val absolute = if (path.isAbsolute) path else Path.of(root(project)).resolve(path)
        return absolute.normalize()
    }

    /** Finds the virtual file for [rawPath], or null when it does not exist. */
    fun findFile(project: Project, rawPath: String): VirtualFile? {
        val resolved = resolvePath(project, rawPath)
        return LocalFileSystem.getInstance().findFileByPath(resolved.toString())
    }

    /** Creates the parent directories of [path] on disk. */
    fun ensureParentDirectory(path: Path) {
        path.parent?.let { Files.createDirectories(it) }
    }

    /** Reads a virtual file's text, or null when it is binary or unreadable. */
    fun readText(file: VirtualFile): String? = ReadAction.computeBlocking<String?, RuntimeException> {
        if (file.isDirectory || file.fileType.isBinary) {
            null
        } else {
            runCatching { String(file.contentsToByteArray(), Charsets.UTF_8) }.getOrNull()
        }
    }

    /** Writes [content] to [file] inside a write action. */
    fun writeFile(file: VirtualFile, content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        ApplicationManager.getApplication().runWriteAction {
            file.setBinaryContent(bytes)
        }
    }
}
