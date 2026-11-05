package dev.nocloud.ai.agent.tools

import com.intellij.execution.runners.ExecutionUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.project.Project
import dev.nocloud.ai.agent.AgentTool
import dev.nocloud.ai.agent.Schema
import dev.nocloud.ai.agent.stringArg
import dev.nocloud.ai.util.parseJsonObject
import kotlinx.serialization.json.JsonObject

/** Starts an IntelliJ run configuration. */
class RunConfigurationTool(private val project: Project) : AgentTool {
    override val name: String = "run_configuration"
    override val description: String =
        "Run an IntelliJ run configuration by name. Call without a name to list the available configurations."

    override val parameters: JsonObject = Schema.obj(
        mapOf(
            "name" to Schema.string("The run configuration name. Leave empty to list the available ones."),
        ),
        required = emptyList(),
    )

    override suspend fun execute(arguments: String): String {
        val args = parseJsonObject(arguments)
        val runManager = com.intellij.execution.RunManager.getInstance(project)
        val configurations = runManager.allSettings

        val requested = args.stringArg("name")
        if (requested.isNullOrBlank()) {
            if (configurations.isEmpty()) return "No run configurations are defined in this project."
            return "Available run configurations:\n" + configurations.joinToString("\n") { it.name }
        }

        val settings = configurations.firstOrNull { it.name == requested }
            ?: return "Error: no run configuration named '$requested'. " +
                "Available: " + configurations.joinToString(", ") { it.name }

        return try {
            val executor = DefaultRunExecutor.getRunExecutorInstance()
            ExecutionUtil.runConfiguration(settings, executor)
            "Started run configuration '${settings.name}'."
        } catch (e: Exception) {
            "Error starting run configuration '$requested': ${e.message}"
        }
    }
}
