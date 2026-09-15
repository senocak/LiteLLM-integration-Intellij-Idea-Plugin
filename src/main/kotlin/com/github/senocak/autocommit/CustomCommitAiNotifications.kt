package com.github.senocak.autocommit

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object CustomCommitAiNotifications {
    private const val GROUP_ID = "Custom Commit AI"

    fun info(project: Project?, message: String) = notify(project, message, NotificationType.INFORMATION)

    fun error(project: Project?, message: String) = notify(project, message, NotificationType.ERROR)

    private fun notify(project: Project?, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(message, type)
            .notify(project)
    }
}
