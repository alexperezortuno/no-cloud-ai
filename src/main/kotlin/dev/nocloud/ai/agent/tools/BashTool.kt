package dev.nocloud.ai.agent.tools

import com.intellij.openapi.project.Project
import dev.nocloud.ai.agent.AgentTool
import dev.nocloud.ai.agent.Schema
import dev.nocloud.ai.agent.intArg
import dev.nocloud.ai.agent.stringArg
import dev.nocloud.ai.util.ProjectFiles
import dev.nocloud.ai.util.parseJsonObject
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Runs a shell command in the project directory. */
class BashTool(private val project: Project) : AgentTool {
    override val name: String = "run_terminal_command"
    override val description: String =
        "Run a shell command in the project directory and return its output. " +
            "Commands run to completion or until the timeout expires."

    override val parameters: JsonObject = Schema.obj(
        mapOf(
            "command" to Schema.string("The shell command to run."),
            "working_directory" to Schema.string("Directory to run the command in. Defaults to the project root."),
            "timeout_ms" to Schema.integer("Maximum runtime in milliseconds. Defaults to 120000."),
        ),
        required = listOf("command"),
    )

    override suspend fun execute(arguments: String): String {
        val args = parseJsonObject(arguments)
        val command = args.stringArg("command") ?: return "Error: 'command' is required."
        val workingDirectory = args.stringArg("working_directory")?.let { ProjectFiles.resolvePath(project, it).toString() }
            ?: ProjectFiles.root(project)
        val timeoutMs = args.intArg("timeout_ms") ?: 120_000

        val shell = if (System.getProperty("os.name").lowercase().contains("win")) {
            listOf("cmd.exe", "/c")
        } else {
            listOf("/bin/sh", "-c")
        }

        return try {
            val process = ProcessBuilder(shell + command)
                .directory(File(workingDirectory))
                .redirectErrorStream(false)
                .start()

            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val outThread = Thread { process.inputStream.bufferedReader().forEachLine { stdout.appendLine(it) } }
            val errThread = Thread { process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } }
            outThread.isDaemon = true
            errThread.isDaemon = true
            outThread.start()
            errThread.start()

            val finished = process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return "Error: command timed out after ${timeoutMs}ms.\n$stdout$stderr"
            }
            outThread.join(2000)
            errThread.join(2000)

            buildString {
                append("Command: $command\n")
                append("Directory: $workingDirectory\n")
                append("Exit code: ${process.exitValue()}\n")
                if (stdout.isNotBlank()) {
                    append("--- stdout ---\n")
                    append(stdout.toString().trimEnd())
                    append('\n')
                }
                if (stderr.isNotBlank()) {
                    append("--- stderr ---\n")
                    append(stderr.toString().trimEnd())
                    append('\n')
                }
            }
        } catch (e: Exception) {
            "Error running command: ${e.message}"
        }
    }
}
