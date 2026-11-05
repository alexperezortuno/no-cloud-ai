package dev.nocloud.ai.agent.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import dev.nocloud.ai.agent.AgentTool
import dev.nocloud.ai.agent.Schema
import dev.nocloud.ai.agent.boolArg
import dev.nocloud.ai.agent.intArg
import dev.nocloud.ai.agent.stringArg
import dev.nocloud.ai.util.ProjectFiles
import dev.nocloud.ai.util.parseJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Reads a slice of a text file. */
class ReadFileTool(private val project: Project) : AgentTool {
    override val name: String = "read_file"
    override val description: String =
        "Read a text file from disk. Returns the file content with line numbers. " +
            "Use offset/limit to read large files in parts."

    override val parameters: JsonObject = Schema.obj(
        mapOf(
            "path" to Schema.string("Absolute path, or a path relative to the project root."),
            "offset" to Schema.integer("1-based line number to start reading from."),
            "limit" to Schema.integer("Maximum number of lines to read."),
        ),
        required = listOf("path"),
    )

    override suspend fun execute(arguments: String): String {
        val args = parseJsonObject(arguments)
        val path = args.stringArg("path") ?: return "Error: 'path' is required."
        val file = ProjectFiles.findFile(project, path) ?: return "Error: file not found: $path"
        if (file.isDirectory) return "Error: '$path' is a directory, not a file."

        val content = ProjectFiles.readText(file) ?: return "Error: '$path' is not a readable text file."
        val lines = content.lines()
        val offset = (args.intArg("offset") ?: 1).coerceAtLeast(1)
        val limit = args.intArg("limit") ?: 2000
        val end = (offset - 1 + limit).coerceAtMost(lines.size)
        if (offset > lines.size) return "File '$path' has ${lines.size} lines; offset $offset is past the end."

        val slice = lines.subList((offset - 1).coerceAtMost(lines.size), end)
        val numbered = slice.mapIndexed { index, line -> "${(offset + index).toString().padStart(5)}| $line" }
        val header = buildString {
            append("File: ${file.path}\n")
            append("Lines: $offset-$end of ${lines.size}\n")
        }
        return header + numbered.joinToString("\n")
    }
}

/** Writes a file, creating it when it does not exist. */
class WriteFileTool(private val project: Project) : AgentTool {
    override val name: String = "write_file"
    override val description: String =
        "Write a text file, replacing its entire content. The file is created if it does not exist."

    override val parameters: JsonObject = Schema.obj(
        mapOf(
            "path" to Schema.string("Absolute path, or a path relative to the project root."),
            "content" to Schema.string("The full content to write."),
        ),
        required = listOf("path", "content"),
    )

    override suspend fun execute(arguments: String): String {
        val args = parseJsonObject(arguments)
        val path = args.stringArg("path") ?: return "Error: 'path' is required."
        val content = (args["content"] as? JsonPrimitive)?.content ?: return "Error: 'content' is required."

        val resolved = ProjectFiles.resolvePath(project, path)
        val existing = ProjectFiles.findFile(project, path)
        val target: com.intellij.openapi.vfs.VirtualFile
        if (existing != null) {
            target = existing
        } else {
            return try {
                ProjectFiles.ensureParentDirectory(resolved)
                val parentPath = resolved.parent ?: return "Error: cannot create '$path' - no parent directory."
                val parent = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                    .refreshAndFindFileByPath(parentPath.toString())
                    ?: return "Error: cannot create '$path' - parent directory does not exist."
                val created = parent.createChildData(this, resolved.fileName.toString())
                ProjectFiles.writeFile(created, content)
                "Created ${created.path} (${content.length} characters)."
            } catch (e: Exception) {
                "Error writing '$path': ${e.message}"
            }
        }

        return try {
            ProjectFiles.writeFile(target, content)
            val action = if (existing == null) "Created" else "Updated"
            "$action ${target.path} (${content.length} characters)."
        } catch (e: Exception) {
            "Error writing '$path': ${e.message}"
        }
    }
}

/** Replaces an exact string inside a file. */
class EditFileTool(private val project: Project) : AgentTool {
    override val name: String = "edit_file"
    override val description: String =
        "Replace an exact string inside a file. The old_string must appear exactly once unless " +
            "replace_all is true."

    override val parameters: JsonObject = Schema.obj(
        mapOf(
            "path" to Schema.string("Absolute path, or a path relative to the project root."),
            "old_string" to Schema.string("The exact text to replace."),
            "new_string" to Schema.string("The replacement text."),
            "replace_all" to Schema.boolean("Replace every occurrence instead of requiring a unique match."),
        ),
        required = listOf("path", "old_string", "new_string"),
    )

    override suspend fun execute(arguments: String): String {
        val args = parseJsonObject(arguments)
        val path = args.stringArg("path") ?: return "Error: 'path' is required."
        val oldString = args.stringArg("old_string") ?: return "Error: 'old_string' is required."
        val newString = (args["new_string"] as? JsonPrimitive)?.content ?: ""
        val replaceAll = args.boolArg("replace_all")

        val file = ProjectFiles.findFile(project, path) ?: return "Error: file not found: $path"
        val content = ProjectFiles.readText(file) ?: return "Error: '$path' is not a readable text file."

        val occurrences = countOccurrences(content, oldString)
        if (occurrences == 0) return "Error: old_string was not found in '$path'."
        if (occurrences > 1 && !replaceAll) {
            return "Error: old_string appears $occurrences times in '$path'. Provide more context or set replace_all."
        }

        val updated = if (replaceAll) content.replace(oldString, newString) else content.replaceFirst(oldString, newString)
        return try {
            ProjectFiles.writeFile(file, updated)
            val count = if (replaceAll) occurrences else 1
            "Edited ${file.path} ($count replacement${if (count == 1) "" else "s"})."
        } catch (e: Exception) {
            "Error editing '$path': ${e.message}"
        }
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }
}

/** Lists the entries of a directory. */
class ListDirectoryTool(private val project: Project) : AgentTool {
    override val name: String = "list_directory"
    override val description: String =
        "List the files and folders in a directory. Use it to explore the project layout."

    override val parameters: JsonObject = Schema.obj(
        mapOf(
            "path" to Schema.string("Directory to list. Defaults to the project root."),
            "max_entries" to Schema.integer("Maximum number of entries to return."),
        ),
        required = emptyList(),
    )

    override suspend fun execute(arguments: String): String {
        val args = parseJsonObject(arguments)
        val path = args.stringArg("path") ?: ProjectFiles.root(project)
        val maxEntries = args.intArg("max_entries") ?: 300

        val directory = ProjectFiles.findFile(project, path)
            ?: return "Error: directory not found: $path"
        if (!directory.isDirectory) return "Error: '$path' is not a directory."

        val children = directory.children.orEmpty()
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .take(maxEntries)

        if (children.isEmpty()) return "Directory '$path' is empty."

        val listing = children.joinToString("\n") { child ->
            val type = if (child.isDirectory) "dir " else "file"
            val size = if (child.isDirectory) "" else " ${child.length} bytes"
            "$type ${child.name}$size"
        }
        return "Directory: ${directory.path}\n$listing"
    }
}

/** Finds text across the project. */
class SearchInFilesTool(private val project: Project) : AgentTool {
    override val name: String = "search_in_files"
    override val description: String =
        "Search the project for a regular expression and return the matching lines with file paths."

    override val parameters: JsonObject = Schema.obj(
        mapOf(
            "query" to Schema.string("Regular expression to search for."),
            "path" to Schema.string("Restrict the search to this directory. Defaults to the whole project."),
            "max_results" to Schema.integer("Maximum number of matches to return."),
        ),
        required = listOf("query"),
    )

    override suspend fun execute(arguments: String): String {
        val args = parseJsonObject(arguments)
        val query = args.stringArg("query") ?: return "Error: 'query' is required."
        val maxResults = args.intArg("max_results") ?: 100

        val regex = runCatching { Regex(query) }.getOrElse { return "Error: invalid regular expression: ${it.message}" }
        val scopePath = args.stringArg("path")
        val scope = scopePath?.let { ProjectFiles.findFile(project, it) }

        val matches = mutableListOf<String>()
        val index = ProjectFileIndex.getInstance(project)
        index.iterateContent { file ->
            if (matches.size >= maxResults) return@iterateContent false
            if (file.isDirectory || file.fileType.isBinary) return@iterateContent true
            if (scope != null && !file.path.startsWith(scope.path)) return@iterateContent true
            if (file.length > 2_000_000) return@iterateContent true

            val text = ProjectFiles.readText(file) ?: return@iterateContent true
            text.lineSequence().forEachIndexed { index, line ->
                if (matches.size >= maxResults) return@forEachIndexed
                if (regex.containsMatchIn(line)) {
                    matches += "${file.path}:${index + 1}: ${line.trim().take(300)}"
                }
            }
            true
        }

        if (matches.isEmpty()) return "No matches for '$query'."
        return matches.joinToString("\n")
    }
}

/** Keeps a todo list for the agent run. */
class TodoWriteTool : AgentTool {
    private val entries = mutableListOf<TodoEntry>()

    override val name: String = "todo_write"
    override val description: String =
        "Record or update the task list for this run. Send the complete list every time; " +
            "an item is done when its status is 'completed'."

    override val parameters: JsonObject = Schema.obj(
        mapOf(
            "todos" to Schema.stringArray(
                "The task list. Each entry is '<status>: <description>' where status is pending, " +
                    "in_progress or completed.",
            ),
        ),
        required = listOf("todos"),
    )

    override suspend fun execute(arguments: String): String {
        val args = parseJsonObject(arguments)
        val todos = (args["todos"] as? kotlinx.serialization.json.JsonArray).orEmpty()
        entries.clear()
        todos.forEach { element ->
            val text = (element as? JsonPrimitive)?.content.orEmpty()
            val status = text.substringBefore(':').trim().lowercase()
            val description = text.substringAfter(':', text).trim()
            entries += TodoEntry(description, status)
        }
        val rendered = entries.joinToString("\n") { "[${it.status}] ${it.description}" }
        return "Todo list updated:\n$rendered"
    }

    fun snapshot(): List<TodoEntry> = entries.toList()

    data class TodoEntry(val description: String, val status: String)
}

/** Builds the JSON payload the model receives for the todo tool. */
internal fun todoPayload(entries: List<TodoWriteTool.TodoEntry>): JsonObject = buildJsonObject {
    put(
        "todos",
        buildJsonArray {
            entries.forEach { entry ->
                add(
                    buildJsonObject {
                        put("content", entry.description)
                        put("status", entry.status)
                    },
                )
            }
        },
    )
}
