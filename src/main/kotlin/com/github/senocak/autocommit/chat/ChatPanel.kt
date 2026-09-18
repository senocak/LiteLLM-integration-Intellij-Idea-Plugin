package com.github.senocak.autocommit.chat

import com.github.senocak.autocommit.http.ModelCatalog
import com.github.senocak.autocommit.CustomCommitAiNotifications
import com.github.senocak.autocommit.settings.CustomCommitAiSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.KeyStroke
import javax.swing.Scrollable
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

/**
 * The chat tool window's contents: model picker and controls on top, transcript in the middle,
 * input at the bottom.
 *
 * The panel owns no conversation state — that lives in [ChatService], so hiding and reopening the
 * tool window does not lose the thread, and the editor's right-click action has somewhere to send
 * an attached selection without reaching into Swing.
 */
class ChatPanel(private val project: Project) : JPanel(BorderLayout()), ChatService.Listener, Disposable {
    private val service = ChatService.getInstance(project)

    private val transcript = TranscriptPanel()

    private val scroll = JBScrollPane(transcript).apply {
        border = JBUI.Borders.empty()
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        viewport.isOpaque = false
        isOpaque = false
    }

    private val input = JBTextArea(3, 10).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "Ask about your code…"
        border = JBUI.Borders.empty(6)
    }

    private val modelCombo = ComboBox<String>().apply {
        isEditable = false
        toolTipText = MODEL_TOOLTIP
    }

    private var loadingModels = false

    private val sendButton = JButton("Send").apply { addActionListener { send() } }

    private val stopButton = JButton("Stop").apply {
        isEnabled = false
        toolTipText = "Stop generating and keep what has arrived so far."
        addActionListener { service.stop() }
    }

    private val providerStatus = JBLabel().apply {
        font = JBUI.Fonts.smallFont()
    }

    private var conversations: List<ChatConversationSummary> = emptyList()
    private val historyLink = ActionLink("Chats (1)") { showConversationHistory() }.apply {
        toolTipText = "Open an earlier conversation from this project."
    }

    private val emptyState = JBLabel(
        "<html>No messages yet.<br><br>Select code in the editor and choose " +
            "<b>Add Selection to LiteLLM Chat</b> from the right-click menu to ask about it.</html>"
    ).apply {
        foreground = JBColor.GRAY
        border = JBUI.Borders.empty(12, 4)
    }

    init {
        background = UIUtil.getPanelBackground()
        add(toolbar(), BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        add(inputArea(), BorderLayout.SOUTH)

        bindEnterToSend()
        // Do not leave the picker blank while /models is still loading. This reads only persisted
        // non-secret state; the API key is read later, from the background task.
        setModels(emptyList(), CustomCommitAiSettings.getInstance().chatPreferences().model)
        seedModels()
        service.addListener(this)   // fires onTranscriptChanged immediately, painting the empty state
    }

    private fun toolbar(): JComponent = JPanel(BorderLayout(8, 0)).apply {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLineBottom(JBColor.border()),
            JBUI.Borders.empty(4, 6),
        )
        isOpaque = false
        add(modelCombo, BorderLayout.CENTER)
        add(
            JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false
                add(historyLink)
                add(ActionLink("Refresh") { seedModels(reportFailure = true) })
                add(ActionLink("Settings") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, "LiteLLM Integration")
                })
            },
            BorderLayout.EAST,
        )
        add(providerStatus, BorderLayout.SOUTH)
    }

    private fun inputArea(): JComponent = JPanel(BorderLayout(0, 4)).apply {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLineTop(JBColor.border()),
            JBUI.Borders.empty(6),
        )
        isOpaque = false
        add(
            JBScrollPane(input).apply {
                border = JBUI.Borders.customLine(JBColor.border(), 1)
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                // Three rows to start with; past ten the transcript is the more valuable space.
                preferredSize = Dimension(0, JBUI.scale(72))
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(220))
            },
            BorderLayout.CENTER,
        )
        add(
            JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false
                add(
                    JBLabel("Enter to send, Shift+Enter for a new line").apply {
                        foreground = JBColor.GRAY
                        font = JBUI.Fonts.smallFont()
                    }
                )
                add(stopButton)
                add(sendButton)
            },
            BorderLayout.SOUTH,
        )
    }

    /**
     * Enter sends and Shift+Enter inserts a newline.
     *
     * Bound through the input map rather than a key listener, so the text area's own Enter binding
     * is genuinely replaced instead of both firing and leaving a stray newline behind.
     */
    private fun bindEnterToSend() {
        input.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), SEND_ACTION)
        input.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "insert-break")
        input.actionMap.put(SEND_ACTION, object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) = send()
        })
    }

    private fun send() {
        if (service.busy) return
        val text = input.text
        if (text.isBlank()) return
        input.text = ""
        service.send(text, (modelCombo.selectedItem as? String).orEmpty())
    }

    override fun onTranscriptChanged(messages: List<ChatMessage>) {
        val pinned = isScrolledToBottom()
        if (emptyState.parent === transcript) transcript.remove(emptyState)

        // Bubbles map one-to-one onto messages, and messages are only ever appended, so the run
        // that still matches is reused and only the tail is rebuilt. Recreating every bubble on
        // each streamed token would throw away the code-block editors several times a second.
        var reusable = 0
        while (reusable < messages.size && reusable < transcript.componentCount) {
            val bubble = transcript.getComponent(reusable) as? MessageBubble ?: break
            if (bubble.role != messages[reusable].role) break
            reusable++
        }
        while (transcript.componentCount > reusable) transcript.remove(transcript.componentCount - 1)

        messages.forEachIndexed { index, message ->
            val bubble = if (index < reusable) transcript.getComponent(index) as MessageBubble
            else MessageBubble(project, message.role).also { transcript.add(it) }
            bubble.update(message)
        }

        if (messages.isEmpty()) transcript.add(emptyState)

        stopButton.isEnabled = service.busy
        sendButton.isEnabled = !service.busy

        transcript.revalidate()
        transcript.repaint()
        // After the layout has settled, not before — the new content has no height yet.
        if (pinned) SwingUtilities.invokeLater { scrollToBottom() }
    }

    override fun onInputRequested(text: String) {
        val existing = input.text
        // A trailing newline leaves the caret below the attached block, ready for the question.
        input.text = if (existing.isBlank()) "$text\n" else "${existing.trimEnd()}\n\n$text\n"
        input.caretPosition = input.document.length
        input.requestFocusInWindow()
    }

    override fun onProviderStatusChanged(status: ProviderStatus) {
        val (text, tooltip, color) = when (status) {
            ProviderStatus.Ready -> Triple("Provider: Ready", "No request is in progress.", JBColor.GRAY)
            ProviderStatus.Sending -> Triple("Provider: Sending…", "Waiting for the configured LiteLLM provider.", STATUS_PENDING)
            is ProviderStatus.Succeeded -> Triple(
                "Provider: Success (${formatElapsed(status.elapsedMillis)})",
                "The provider returned a response in ${formatElapsed(status.elapsedMillis)}.",
                STATUS_SUCCESS,
            )
            is ProviderStatus.Failed -> Triple("Provider: Failed", status.message, STATUS_FAILURE)
            is ProviderStatus.Stopped -> Triple(
                "Provider: Stopped (${formatElapsed(status.elapsedMillis)})",
                "Generation was stopped; any response already received was kept.",
                JBColor.GRAY,
            )
        }
        providerStatus.text = text
        providerStatus.toolTipText = tooltip
        providerStatus.foreground = color
    }

    override fun onConversationsChanged(conversations: List<ChatConversationSummary>) {
        this.conversations = conversations
        historyLink.text = "Chats (${conversations.size})"
    }

    private fun showConversationHistory() {
        val menu = JPopupMenu()
        menu.add(JMenuItem("New Chat").apply { addActionListener { newChat() } })
        menu.add(JMenuItem("Clear Current Chat").apply { addActionListener { clearChat() } })
        menu.add(JMenuItem("Delete Current Chat…").apply { addActionListener { deleteChat() } })
        menu.addSeparator()
        // Most recent first makes returning to the chat just left more convenient in a long list.
        conversations.asReversed().forEach { conversation ->
            val label = if (conversation.active) "${conversation.title}  ✓" else conversation.title
            menu.add(JMenuItem(label).apply {
                addActionListener { service.selectConversation(conversation.id) }
            })
        }
        menu.show(historyLink, 0, historyLink.height)
    }

    private fun newChat() {
        input.text = ""
        service.newConversation()
    }

    private fun clearChat() {
        input.text = ""
        service.clearCurrentConversation()
    }

    private fun deleteChat() {
        val answer = Messages.showYesNoDialog(
            project,
            "Delete this chat and all of its messages? This cannot be undone.",
            "Delete LiteLLM Chat",
            Messages.getQuestionIcon(),
        )
        if (answer != Messages.YES) return
        input.text = ""
        service.deleteCurrentConversation()
    }

    /** Only follow the stream when the user is already at the bottom; otherwise leave them reading. */
    private fun isScrolledToBottom(): Boolean {
        val bar = scroll.verticalScrollBar
        return bar.value + bar.visibleAmount >= bar.maximum - JBUI.scale(40)
    }

    private fun scrollToBottom() {
        transcript.scrollRectToVisible(Rectangle(0, transcript.height - 1, 1, 1))
    }

    /**
     * Fills the picker from the gateway, seeded with the configured model, so an unreachable
     * gateway still leaves something usable selected rather than an empty box.
     */
    private fun seedModels(reportFailure: Boolean = false) {
        val preferences = CustomCommitAiSettings.getInstance().chatPreferences()
        if (preferences.baseUrl.isBlank() || loadingModels) return
        loadingModels = true
        modelCombo.isEnabled = false

        object : Task.Backgroundable(project, "Loading LiteLLM models...", true) {
            private var models: List<String> = emptyList()
            private var selected = ""
            private var failure: String? = null

            override fun run(indicator: ProgressIndicator) {
                // Resolved here rather than on the EDT: this reads the key out of Password Safe,
                // which the platform treats as a slow operation.
                val configuration = CustomCommitAiSettings.getInstance().configuration()
                selected = configuration.effectiveChatModel
                models = try {
                    ModelCatalog.fetch(configuration)
                } catch (exception: Exception) {
                    failure = exception.message ?: "Could not load models."
                    emptyList()
                }
            }

            override fun onSuccess() {
                // Keep the saved model usable if the gateway list is temporarily unavailable,
                // but make that state visible instead of silently presenting an empty picker.
                setModels(models, selected)
                modelCombo.toolTipText = failure?.let { "Could not load models: $it" } ?: MODEL_TOOLTIP
                if (reportFailure && failure != null) {
                    CustomCommitAiNotifications.error(project, failure!!)
                }
            }

            override fun onFinished() {
                loadingModels = false
                modelCombo.isEnabled = true
            }
        }.queue()
    }

    private fun setModels(models: List<String>, selected: String) {
        val all = (models + selected).filter { it.isNotBlank() }.distinct()
        modelCombo.model = DefaultComboBoxModel(all.toTypedArray())
        modelCombo.selectedItem = selected.takeIf { it.isNotBlank() && it in all } ?: all.firstOrNull()
    }

    private fun formatElapsed(millis: Long): String = when {
        millis < 1_000 -> "${millis} ms"
        else -> "%.1f s".format(java.util.Locale.ROOT, millis / 1_000.0)
    }

    override fun dispose() = service.removeListener(this)

    private companion object {
        private const val SEND_ACTION = "litellm-chat-send"
        private const val MODEL_TOOLTIP = "Model used for this conversation. The default comes from " +
            "Settings | Tools | LiteLLM Integration."
        private val STATUS_PENDING = JBColor(Color(0x9A, 0x67, 0x00), Color(0xD8, 0xB2, 0x4C))
        private val STATUS_SUCCESS = JBColor(Color(0x2E, 0x7D, 0x32), Color(0x6A, 0xAB, 0x73))
        private val STATUS_FAILURE = JBColor(Color(0xC6, 0x28, 0x28), Color(0xFF, 0x6B, 0x68))
    }
}

/**
 * The transcript's viewport behaviour.
 *
 * `getScrollableTracksViewportWidth` is the load-bearing override: without it the viewport gives
 * the panel its own preferred width, so the HTML panes never learn how narrow the dock is and
 * long paragraphs run off the side instead of wrapping.
 */
private class TranscriptPanel : JPanel(VerticalLayout(JBUI.scale(6))), Scrollable {
    init {
        border = JBUI.Borders.empty(8)
        isOpaque = false
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)

    override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int) = visible.height

    override fun getScrollableTracksViewportWidth() = true

    override fun getScrollableTracksViewportHeight() = false
}
