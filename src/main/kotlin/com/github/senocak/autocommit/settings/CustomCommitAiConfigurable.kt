package com.github.senocak.autocommit.settings

import com.github.senocak.autocommit.CustomCommitAiNotifications
import com.github.senocak.autocommit.http.CommitMessageClient
import com.github.senocak.autocommit.http.ModelCatalog
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.Timer
import javax.swing.event.DocumentEvent

/**
 * Decides what the model dropdown should contain and which entry is selected.
 *
 * The dropdown is selection-only, so this is the sole thing standing between a saved model and
 * being silently dropped: an item that is not in the list cannot be chosen back by hand.
 *
 * @param allowUnlisted whether to keep [select] when [items] does not contain it. True while the
 *   gateway's list is unknown — the page is opening, or a fetch failed — so an unreachable gateway
 *   does not cost you your configured model. False once a real list has arrived: the allowlist is
 *   key-scoped, so after a key change the previous model is genuinely unusable, and keeping it
 *   selected would only defer the failure to generation time.
 * @return the items to show, and the one to select.
 */
internal fun modelChoice(items: List<String>, select: String, allowUnlisted: Boolean): Pair<List<String>, String> {
    val all = (if (allowUnlisted) items + select else items).filter { it.isNotBlank() }.distinct()
    return all to (select.takeIf { it.isNotBlank() && it in all } ?: all.firstOrNull().orEmpty())
}

class CustomCommitAiConfigurable : Configurable {
    private var panel: JPanel? = null
    private var urlField: JBTextField? = null
    private var modelCombo: ComboBox<String>? = null
    private var keyField: JBPasswordField? = null
    private var promptField: JTextArea? = null
    private var refreshButton: JButton? = null
    private var endpointHint: JBLabel? = null
    private var completionCombo: ComboBox<String>? = null
    private var completionToggle: JBCheckBox? = null

    /** Coalesces keystrokes so the gateway is asked once the user stops typing, not per character. */
    private var reloadDebounce: Timer? = null
    private var loading = false

    override fun getDisplayName() = "LiteLLM Integration"

    override fun createComponent(): JComponent {
        val settingsPanel = JPanel(GridBagLayout())
        val constraints = GridBagConstraints().apply {
            insets = Insets(4, 4, 4, 4)
            anchor = GridBagConstraints.WEST
        }
        fun addRow(row: Int, label: String, component: JComponent, fill: Int = GridBagConstraints.HORIZONTAL) {
            constraints.gridx = 0; constraints.gridy = row; constraints.weightx = 0.0; constraints.fill = GridBagConstraints.NONE
            settingsPanel.add(JLabel(label), constraints)
            constraints.gridx = 1; constraints.weightx = 1.0; constraints.fill = fill
            settingsPanel.add(component, constraints)
        }

        urlField = JBTextField().apply {
            emptyText.text = "https://gateway.example.com/v1"
            toolTipText = "Gateway root only. /models, /chat/completions and /messages are derived from it."
        }
        // Spells out what the plugin will actually call, so the derivation is not a black box.
        endpointHint = JBLabel().apply {
            foreground = UIUtil.getContextHelpForeground()
            font = JBUI.Fonts.smallFont()
        }
        // Selection only: a hand-typed id that the key cannot serve just fails later at
        // generation time, so the gateway's own list is the only source of valid values.
        modelCombo = ComboBox<String>().apply {
            isEditable = false
            toolTipText = "Loaded from the gateway's /models endpoint. Use Refresh if the list looks stale."
        }
        // Same list, different job: this one wants speed, not reasoning quality.
        completionCombo = ComboBox<String>().apply {
            isEditable = false
            toolTipText = "Model used for inline code suggestions. Prefer a small, fast one \u2014 " +
                "a reasoning model spends seconds thinking before it emits a line."
        }
        completionToggle = JBCheckBox("Suggest completions while typing").apply {
            toolTipText = "When off, suggestions appear only when you invoke inline completion explicitly."
        }
        keyField = JBPasswordField()
        promptField = JTextArea(6, 48).apply { lineWrap = true; wrapStyleWord = true }

        refreshButton = JButton("Refresh").apply { addActionListener { loadModels(reportSuccess = true) } }
        val modelRow = JPanel(BorderLayout(4, 0)).apply {
            add(modelCombo!!, BorderLayout.CENTER)
            add(refreshButton!!, BorderLayout.EAST)
        }

        val urlRow = JPanel(BorderLayout()).apply {
            add(urlField!!, BorderLayout.CENTER)
            add(endpointHint!!, BorderLayout.SOUTH)
        }

        addRow(0, "API Base URL:", urlRow)
        addRow(1, "API Key:", keyField!!)
        addRow(2, "Commit Model:", modelRow)
        addRow(3, "Completion Model:", completionCombo!!)
        addRow(4, "", completionToggle!!)
        addRow(5, "System Prompt:", JBScrollPane(promptField!!), GridBagConstraints.BOTH)

        val testButton = JButton("Test Connection")
        testButton.addActionListener { testConnection(testButton) }
        constraints.gridx = 1; constraints.gridy = 6; constraints.weightx = 0.0; constraints.fill = GridBagConstraints.NONE
        settingsPanel.add(testButton, constraints)

        panel = JPanel(BorderLayout()).apply { add(settingsPanel, BorderLayout.NORTH) }
        reset()

        // Registered after reset() so populating the fields from saved settings does not
        // itself count as an edit and queue a second, redundant fetch.
        val onEdit = object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) = scheduleReload()
        }
        urlField!!.document.addDocumentListener(onEdit)
        keyField!!.document.addDocumentListener(onEdit)
        urlField!!.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) = paintEndpointHint()
        })
        paintEndpointHint()

        loadModels(reportSuccess = false)   // fill the dropdown as soon as the page opens
        return panel!!
    }

    override fun isModified(): Boolean {
        val configuration = CustomCommitAiSettings.getInstance().configuration()
        return urlField?.text?.trim() != configuration.apiUrl ||
            selectedModel() != configuration.model ||
            selectedCompletionModel() != configuration.completionModel ||
            completionToggle?.isSelected != configuration.completionEnabled ||
            String(keyField?.password ?: CharArray(0)) != configuration.apiKey ||
            promptField?.text?.trim() != configuration.systemPrompt
    }

    override fun apply() = save()

    override fun reset() {
        val configuration = CustomCommitAiSettings.getInstance().configuration()
        urlField?.text = configuration.apiUrl
        // Seed each combo with its own saved value before any fetch, so an unreachable gateway
        // still shows what is configured.
        apply(modelCombo, emptyList(), configuration.model, allowUnlisted = true)
        apply(completionCombo, emptyList(), configuration.completionModel, allowUnlisted = true)
        completionToggle?.isSelected = configuration.completionEnabled
        keyField?.text = configuration.apiKey
        promptField?.text = configuration.systemPrompt
    }

    override fun disposeUIResources() {
        reloadDebounce?.stop()
        reloadDebounce = null
        panel = null; urlField = null; modelCombo = null; keyField = null
        promptField = null; refreshButton = null; endpointHint = null
        completionCombo = null; completionToggle = null
    }

    private fun selectedModel(): String = (modelCombo?.selectedItem as? String).orEmpty().trim()

    private fun selectedCompletionModel(): String = (completionCombo?.selectedItem as? String).orEmpty().trim()

    /** One fetch feeds both dropdowns; they differ only in which entry stays selected. */
    private fun setModelItems(items: List<String>, select: String, allowUnlisted: Boolean) {
        apply(modelCombo, items, select, allowUnlisted)
        apply(completionCombo, items, selectedCompletionModel().ifBlank { select }, allowUnlisted)
    }

    private fun apply(combo: ComboBox<String>?, items: List<String>, select: String, allowUnlisted: Boolean) {
        if (combo == null) return
        val (all, chosen) = modelChoice(items, select, allowUnlisted)
        combo.model = DefaultComboBoxModel(all.toTypedArray())
        combo.selectedItem = chosen
    }

    private fun paintEndpointHint() {
        val typed = urlField?.text.orEmpty()
        val base = normalizeBaseUrl(typed)
        endpointHint?.text = when {
            base.isBlank() -> " "
            // Say so explicitly when the typed value is not what gets called — otherwise pasting
            // a full ".../v1/messages" looks like it selects that endpoint, and it does not.
            base != typed.trim().trimEnd('/') -> "Uses $base — $base/chat/completions, $base/models"
            else -> "Calls $base/chat/completions and $base/models"
        }
    }

    private fun scheduleReload() {
        reloadDebounce?.stop()
        reloadDebounce = Timer(600) { loadModels(reportSuccess = false) }.apply {
            isRepeats = false
            start()
        }
    }

    private fun loadModels(reportSuccess: Boolean) {
        val configuration = currentConfiguration()
        // Nothing to ask with, and nothing worth warning about — the user is still filling the form.
        if (configuration.apiUrl.isBlank() || configuration.apiKey.isBlank()) return
        if (loading) return
        loading = true
        refreshButton?.isEnabled = false

        object : Task.Backgroundable(null, "Loading models...", true) {
            private var models: List<String> = emptyList()
            private var error: String? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    models = ModelCatalog.fetch(configuration)
                } catch (exception: Exception) {
                    error = CommitMessageClient.userFacingMessage(exception)
                }
            }

            override fun onSuccess() {
                ApplicationManager.getApplication().invokeLater {
                    if (modelCombo == null) return@invokeLater   // settings page closed meanwhile
                    if (models.isNotEmpty()) {
                        val previous = selectedModel()
                        setModelItems(models, previous, allowUnlisted = false)
                        val switched = previous.isNotBlank() && previous !in models
                        if (reportSuccess || switched) CustomCommitAiNotifications.info(
                            null,
                            if (switched) "This key does not serve $previous; selected ${selectedModel()}."
                            else "Loaded ${models.size} model(s)."
                        )
                    } else if (reportSuccess) {
                        // Silent on the automatic path: a half-typed URL should not nag.
                        CustomCommitAiNotifications.error(null, error ?: "Could not load models.")
                    }
                }
            }

            override fun onFinished() {
                loading = false
                ApplicationManager.getApplication().invokeLater { refreshButton?.isEnabled = true }
            }
        }.queue()
    }

    private fun currentConfiguration() = ApiConfiguration(
        urlField?.text?.trim().orEmpty(),
        selectedModel(),
        String(keyField?.password ?: CharArray(0)),
        promptField?.text?.trim().orEmpty(),
        selectedCompletionModel(),
        completionToggle?.isSelected ?: true,
    )

    private fun save() {
        val current = currentConfiguration()
        CustomCommitAiSettings.getInstance().update(
            current.apiUrl, current.model, current.apiKey, current.systemPrompt,
            current.completionModel, current.completionEnabled,
        )
        // Show the stored base back to the user: applying a full endpoint URL silently rewrites
        // it, and leaving the old text on screen would misreport what was saved.
        val stored = CustomCommitAiSettings.getInstance().configuration().apiUrl
        if (urlField?.text != stored) urlField?.text = stored
    }

    private fun testConnection(button: JButton) {
        val configuration = currentConfiguration()
        configuration.validationError()?.let {
            CustomCommitAiNotifications.error(null, it)
            return
        }
        button.isEnabled = false
        object : Task.Backgroundable(null, "Testing LiteLLM Integration connection...", true) {
            private var error: String? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    CommitMessageClient.testConnection(configuration)
                } catch (exception: Exception) {
                    error = CommitMessageClient.userFacingMessage(exception)
                }
            }

            override fun onSuccess() {
                if (error == null) CustomCommitAiNotifications.info(null, "Connection successful.")
                else CustomCommitAiNotifications.error(null, error!!)
            }

            override fun onFinished() {
                button.isEnabled = true
            }
        }.queue()
    }
}
