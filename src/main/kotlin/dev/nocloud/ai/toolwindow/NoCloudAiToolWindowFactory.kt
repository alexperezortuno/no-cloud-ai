package dev.nocloud.ai.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import dev.nocloud.ai.agent.AgentResponder
import dev.nocloud.ai.chat.ChatResponder
import dev.nocloud.ai.chat.ChatSurface

/** Creates the "No Cloud AI" tool window with a Chat tab and an Agent tab. */
class NoCloudAiToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val chatSurface = ChatSurface(
            project = project,
            handler = ChatResponder(),
            emptyStateText = "Ask a question about your code.",
        )
        val agentSurface = ChatSurface(
            project = project,
            handler = AgentResponder(project),
            emptyStateText = "Describe a task and the agent will use your local model to carry it out.",
        )

        val contentManager = toolWindow.contentManager
        contentManager.addContent(contentManager.factory.createContent(chatSurface.component, "Chat", false))
        contentManager.addContent(contentManager.factory.createContent(agentSurface.component, "Agent", false))

        Disposer.register(toolWindow.disposable) {
            chatSurface.dispose()
            agentSurface.dispose()
        }
    }
}
