package dev.nocloud.ai.agent

import com.intellij.openapi.project.Project
import dev.nocloud.ai.agent.tools.BashTool
import dev.nocloud.ai.agent.tools.EditFileTool
import dev.nocloud.ai.agent.tools.ListDirectoryTool
import dev.nocloud.ai.agent.tools.ReadFileTool
import dev.nocloud.ai.agent.tools.RunConfigurationTool
import dev.nocloud.ai.agent.tools.SearchInFilesTool
import dev.nocloud.ai.agent.tools.TodoWriteTool
import dev.nocloud.ai.agent.tools.WriteFileTool

/** Builds the set of tools the agent can call for a project. */
object ToolRegistry {

    fun forProject(project: Project): List<AgentTool> = listOf(
        ReadFileTool(project),
        WriteFileTool(project),
        EditFileTool(project),
        ListDirectoryTool(project),
        SearchInFilesTool(project),
        BashTool(project),
        RunConfigurationTool(project),
        TodoWriteTool(),
    )
}
