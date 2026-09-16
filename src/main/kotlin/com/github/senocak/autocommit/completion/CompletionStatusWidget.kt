package com.github.senocak.autocommit.completion

import com.github.senocak.autocommit.settings.CustomCommitAiSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import java.awt.Component
import java.awt.event.MouseEvent
import javax.swing.SwingConstants

/**
 * Status-bar readout for inline completion: whether a request is in flight, how long the last
 * one took, and why the last one failed.
 *
 * Text is kept to a few characters so it does not crowd the status bar; the detail — model,
 * timing, error message — lives in the tooltip.
 */
class CompletionStatusWidget : StatusBarWidget, StatusBarWidget.TextPresentation {
    override fun ID(): String = COMPLETION_STATUS_WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) = Unit

    override fun dispose() = Unit

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getText(): String = when (val state = CompletionStatus.state) {
        CompletionState.Off -> "LiteLLM: off"
        CompletionState.Idle -> "LiteLLM: idle"
        CompletionState.Running -> "LiteLLM: thinking…"
        is CompletionState.Done -> "LiteLLM: ${format(state.millis)}" + if (state.empty) " (none)" else ""
        is CompletionState.Failed -> "LiteLLM: failed"
    }

    override fun getTooltipText(): String {
        // Preferences, not the full configuration: this runs on the EDT and the credential store
        // must not be touched here.
        val model = CustomCommitAiSettings.getInstance().completionPreferences().model.ifBlank { "not set" }
        return when (val state = CompletionStatus.state) {
            CompletionState.Off ->
                "Inline completion is off or unconfigured. Set an API base URL, key and completion model " +
                    "in Settings | Tools | LiteLLM Integration."

            CompletionState.Idle -> "Inline completion ready ($model). Nothing requested yet."

            CompletionState.Running -> "Asking $model for a suggestion…"

            is CompletionState.Done ->
                if (state.empty) "$model answered in ${format(state.millis)} with no suggestion."
                else "$model suggested in ${format(state.millis)}."

            // The whole point of the widget: a reason, not just silence.
            is CompletionState.Failed -> "${state.reason}\n(after ${format(state.millis)}, model $model)"
        }
    }

    override fun getClickConsumer(): com.intellij.util.Consumer<MouseEvent>? = null

    private fun format(millis: Long): String =
        if (millis < 1000) "${millis}ms" else String.format("%.1fs", millis / 1000.0)
}

class CompletionStatusWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = COMPLETION_STATUS_WIDGET_ID

    override fun getDisplayName(): String = "LiteLLM Completion"

    override fun createWidget(project: Project): StatusBarWidget = CompletionStatusWidget()

    override fun disposeWidget(widget: StatusBarWidget) = widget.dispose()

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
