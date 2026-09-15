package com.github.senocak.autocommit.action

import com.github.senocak.autocommit.CustomCommitAiNotifications
import com.github.senocak.autocommit.git.GitDiffService
import com.github.senocak.autocommit.http.CommitMessageClient
import com.github.senocak.autocommit.settings.CustomCommitAiSettings
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.ui.AnimatedIcon
import com.intellij.vcs.commit.CommitWorkflowUi
import javax.swing.Icon
import java.util.concurrent.atomic.AtomicBoolean

class GenerateCommitMessageAction : AnAction(), DumbAware {
    override fun update(event: AnActionEvent) {
        val project = event.project
        val messageControl = event.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL)
        val generating = isGenerating.get()
        event.presentation.icon = if (generating) AnimatedIcon.Default.INSTANCE else GENERATE_ICON
        event.presentation.description = if (generating) "Generating commit message..." else DESCRIPTION
        event.presentation.isEnabledAndVisible = project != null && messageControl != null && !generating
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val messageControl = event.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) ?: return
        // This is the current Commit tool window's checked/included set, read at click time.
        // It changes immediately when the user includes or excludes another file.
        val workflowUi: CommitWorkflowUi? = event.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
        val changes: List<Change> = workflowUi?.getIncludedChanges()?.toList().orEmpty()
        val unversionedFiles: List<FilePath> = workflowUi?.getIncludedUnversionedFiles()?.toList().orEmpty()

        if (changes.isEmpty() && unversionedFiles.isEmpty()) {
            LOG.info("Custom Commit AI: generation skipped because no changes are included for commit.")
            CustomCommitAiNotifications.info(project, "No changes available to generate a commit message.")
            return
        }

        val configuration = CustomCommitAiSettings.getInstance().configuration()
        val validationError = configuration.validationError()
        if (validationError != null) {
            CustomCommitAiNotifications.error(project, validationError)
            return
        }
        if (!isGenerating.compareAndSet(false, true)) return
        event.presentation.icon = AnimatedIcon.Default.INSTANCE
        event.presentation.description = "Generating commit message..."
        event.presentation.isEnabled = false
        LOG.info(
            "Custom Commit AI: generating a commit message for ${changes.size} changed and " +
                "${unversionedFiles.size} unversioned included file(s)."
        )

        object : Task.Backgroundable(project, "Generating commit message...", true) {
            private var generatedMessage: String? = null
            private var failure: String? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Preparing selected Git changes..."
                    val diff = GitDiffService.buildDiff(changes, unversionedFiles, indicator)
                    if (diff.isBlank()) {
                        failure = "No changes available to generate a commit message."
                        return
                    }
                    LOG.info("Custom Commit AI: prepared selected-file diff (chars=${diff.length}).")

                    indicator.text = "Generating commit message..."
                    generatedMessage = CommitMessageClient.generateCommitMessage(configuration, diff)
                } catch (exception: Exception) {
                    LOG.warn("Custom Commit AI: generation failed (${exception.javaClass.simpleName}).")
                    failure = CommitMessageClient.userFacingMessage(exception)
                }
            }

            override fun onSuccess() {
                generatedMessage?.let {
                    messageControl.setCommitMessage(it)
                    LOG.info("Custom Commit AI: generated message inserted into the commit field.")
                }
                failure?.let { CustomCommitAiNotifications.error(project, it) }
            }

            override fun onFinished() {
                isGenerating.set(false)
                event.presentation.icon = GENERATE_ICON
                event.presentation.description = DESCRIPTION
                event.presentation.isEnabled = true
            }
        }.queue()
    }

    private companion object {
        private val LOG = Logger.getInstance(GenerateCommitMessageAction::class.java)
        private val isGenerating = AtomicBoolean(false)
        private const val DESCRIPTION = "Generate a commit message from the current Git changes using a custom HTTP API."
        private val GENERATE_ICON: Icon = IconLoader.getIcon("/icons/sparkle.svg", GenerateCommitMessageAction::class.java)
    }
}
