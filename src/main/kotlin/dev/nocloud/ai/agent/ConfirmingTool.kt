package dev.nocloud.ai.agent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.nocloud.ai.agent.stringArg
import dev.nocloud.ai.settings.LocalAiSettings
import dev.nocloud.ai.util.parseJsonObject
import java.util.concurrent.CountDownLatch

/**
 * A tool that requires user confirmation before execution.
 *
 * Subclasses must define the [confirmationMessage] shown to the user.
 * If the user cancels, the tool returns an error message without executing.
 */
abstract class ConfirmingTool<Args : Any, R : Any> : AgentTool {

    /**
     * The message shown in the confirmation dialog.
     * Can include the tool arguments for context.
     */
    abstract fun confirmationMessage(args: Args): String

    constructor() {}

    override final suspend fun execute(arguments: String): String {
        val parsed = parseJsonObject(arguments)
        
        // Check if confirmation is required globally
        val settings = LocalAiSettings.instance().state
        if (!settings.requireToolConfirmation) {
            return doExecute(parsed as Args)
        }
        
        val confirm = runOnUiThread {
            val message = confirmationMessage(parsed as Args)
            Messages.showOkCancelDialog(
                null,
                message,
                "Confirm tool execution",
                Messages.getQuestionIcon(),
            ) == Messages.OK
        }
        if (!confirm) {
            return "User cancelled the tool execution."
        }
        return doExecute(parsed as Args)
    }

    /**
     * The actual implementation. Called only after the user confirms.
     */
    protected abstract suspend fun doExecute(args: Args): String

    private suspend fun <T> runOnUiThread(block: () -> T): T {
        val latch = CountDownLatch(1)
        var result: T? = null
        var exception: Throwable? = null
        ApplicationManager.getApplication().invokeLater {
            try {
                result = block()
            } catch (e: Throwable) {
                exception = e
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        if (exception != null) throw exception
        return result!!
    }
}