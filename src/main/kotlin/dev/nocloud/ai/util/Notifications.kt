package dev.nocloud.ai.util

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/** Balloon notifications shown in the IDE. */
object Notifications {
    private const val GROUP_ID = "NoCloudAi.NotificationGroup"

    fun error(project: Project?, message: String) {
        notify(project, message, NotificationType.ERROR)
    }

    fun warning(project: Project?, message: String) {
        notify(project, message, NotificationType.WARNING)
    }

    fun info(project: Project?, message: String) {
        notify(project, message, NotificationType.INFORMATION)
    }

    private fun notify(project: Project?, message: String, type: NotificationType) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
        group.createNotification("No Cloud AI", message, type).notify(project)
    }
}
