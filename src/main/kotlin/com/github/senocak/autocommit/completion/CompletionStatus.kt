package com.github.senocak.autocommit.completion

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.WindowManager

internal const val COMPLETION_STATUS_WIDGET_ID = "LiteLlmCompletionStatus"

/**
 * What inline completion is doing right now, surfaced in the status bar.
 *
 * Completion is otherwise completely silent — failures are logged rather than notified, so a
 * flaky gateway does not produce a balloon per keystroke. That silence makes "slow" and "broken"
 * look identical from the outside, which is what this exists to fix: the widget distinguishes
 * waiting from failed, and reports how long the last request actually took.
 */
sealed interface CompletionState {
    /** Nothing has been asked yet this session. */
    object Idle : CompletionState

    /** Inline completion is switched off or not configured. */
    object Off : CompletionState

    object Running : CompletionState

    /** [millis] is the round trip; [empty] means the model answered but had nothing to suggest. */
    data class Done(val millis: Long, val empty: Boolean) : CompletionState

    data class Failed(val millis: Long, val reason: String) : CompletionState
}

object CompletionStatus {
    @Volatile
    var state: CompletionState = CompletionState.Idle
        private set

    fun running() = set(CompletionState.Running)

    fun done(millis: Long, empty: Boolean) = set(CompletionState.Done(millis, empty))

    fun failed(millis: Long, reason: String) = set(CompletionState.Failed(millis, reason))

    fun off() = set(CompletionState.Off)

    private fun set(next: CompletionState) {
        state = next
        // The widget has no way to observe the state on its own, so every transition repaints it.
        // Cheap: a handful of status bars, and only on request boundaries rather than per keystroke.
        ApplicationManager.getApplication().invokeLater {
            for (project in ProjectManager.getInstance().openProjects) {
                if (project.isDisposed) continue
                WindowManager.getInstance().getStatusBar(project)?.updateWidget(COMPLETION_STATUS_WIDGET_ID)
            }
        }
    }
}
