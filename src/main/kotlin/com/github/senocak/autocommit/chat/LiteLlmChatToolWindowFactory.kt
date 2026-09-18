package com.github.senocak.autocommit.chat

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Must match the `toolWindow` id in plugin.xml exactly: the editor action looks the window up by
 * this string, and a mismatch fails silently at runtime rather than at build time.
 */
const val CHAT_TOOL_WINDOW_ID = "LiteLLM Chat"

class LiteLlmChatToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ChatPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        // The panel registers a listener with the project-level service, so it has to be
        // unregistered when the content goes away — otherwise every reopen leaks another one.
        Disposer.register(content, panel)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
    }
}
