package com.github.senocak.autocommit.chat

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Editor right-click entry that drops the current selection into the chat input as a fenced code
 * block, tagged with the file and line range.
 *
 * The text lands in the input box rather than being sent straight away, so the question can be
 * typed around it — and so it is visible and editable before anything leaves the machine.
 */
class AddSelectionToChatAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val editor = event.getData(CommonDataKeys.EDITOR)
        // Hidden rather than disabled: a greyed-out entry in an already long editor menu is noise.
        event.presentation.isEnabledAndVisible = event.project != null && editor?.selectionModel?.hasSelection() == true
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val selection = editor.selectionModel
        val code = selection.selectedText?.takeIf { it.isNotBlank() } ?: return

        val document = editor.document
        // Editor line numbers are zero-based; the header is for a human reading it next to the
        // gutter, so it is not.
        val startLine = document.getLineNumber(selection.selectionStart) + 1
        // Selection end is exclusive. If it lands at the start of the following line (the usual
        // shape for selecting complete lines), label the code with the last line actually sent.
        val lastSelectedOffset = (selection.selectionEnd - 1).coerceAtLeast(selection.selectionStart)
        val endLine = document.getLineNumber(lastSelectedOffset) + 1
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        val fileName = file?.name ?: "selection"
        // The extension, not the language name: it is what the fence tag maps back from, and it
        // is right far more often than guessing a display name.
        val language = file?.extension.orEmpty()

        val attachment = fencedSelection(fileName, startLine, endLine, language, code)
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(CHAT_TOOL_WINDOW_ID)
        if (toolWindow == null) {
            ChatService.getInstance(project).requestInput(attachment)
            return
        }
        // activate() creates the content on first use, so the request is made from the callback —
        // by then a panel exists to receive it.
        toolWindow.activate { ChatService.getInstance(project).requestInput(attachment) }
    }
}
