package com.github.senocak.autocommit.settings

import com.github.senocak.autocommit.CustomCommitAiNotifications
import com.github.senocak.autocommit.http.CommitMessageClient
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea

class CustomCommitAiConfigurable : Configurable {
    private var panel: JPanel? = null
    private var urlField: JBTextField? = null
    private var modelField: JBTextField? = null
    private var keyField: JBPasswordField? = null
    private var promptField: JTextArea? = null

    override fun getDisplayName() = "Custom Commit AI"

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

        urlField = JBTextField()
        modelField = JBTextField()
        keyField = JBPasswordField()
        promptField = JTextArea(6, 48).apply { lineWrap = true; wrapStyleWord = true }
        addRow(0, "API URL:", urlField!!)
        addRow(1, "Model:", modelField!!)
        addRow(2, "API Key:", keyField!!)
        addRow(3, "System Prompt:", JBScrollPane(promptField!!), GridBagConstraints.BOTH)

        val testButton = JButton("Test Connection")
        testButton.addActionListener { testConnection(testButton) }
        constraints.gridx = 1; constraints.gridy = 4; constraints.weightx = 0.0; constraints.fill = GridBagConstraints.NONE
        settingsPanel.add(testButton, constraints)

        panel = JPanel(BorderLayout()).apply { add(settingsPanel, BorderLayout.NORTH) }
        reset()
        return panel!!
    }

    override fun isModified(): Boolean {
        val configuration = CustomCommitAiSettings.getInstance().configuration()
        return urlField?.text?.trim() != configuration.apiUrl ||
            modelField?.text?.trim() != configuration.model ||
            String(keyField?.password ?: CharArray(0)) != configuration.apiKey ||
            promptField?.text?.trim() != configuration.systemPrompt
    }

    override fun apply() = save()

    override fun reset() {
        val configuration = CustomCommitAiSettings.getInstance().configuration()
        urlField?.text = configuration.apiUrl
        modelField?.text = configuration.model
        keyField?.text = configuration.apiKey
        promptField?.text = configuration.systemPrompt
    }

    override fun disposeUIResources() {
        panel = null; urlField = null; modelField = null; keyField = null; promptField = null
    }

    private fun currentConfiguration() = ApiConfiguration(
        urlField?.text?.trim().orEmpty(),
        modelField?.text?.trim().orEmpty(),
        String(keyField?.password ?: CharArray(0)),
        promptField?.text?.trim().orEmpty(),
    )

    private fun save() {
        val current = currentConfiguration()
        CustomCommitAiSettings.getInstance().update(current.apiUrl, current.model, current.apiKey, current.systemPrompt)
    }

    private fun testConnection(button: JButton) {
        val configuration = currentConfiguration()
        configuration.validationError()?.let {
            CustomCommitAiNotifications.error(null, it)
            return
        }
        button.isEnabled = false
        object : Task.Backgroundable(null, "Testing Custom Commit AI connection...", true) {
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
