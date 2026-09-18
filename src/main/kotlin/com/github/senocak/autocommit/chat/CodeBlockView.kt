package com.github.senocak.autocommit.chat

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * One fenced code block, rendered in a real read-only editor so it gets the IDE's own syntax
 * highlighting, font and colour scheme rather than an approximation of them.
 *
 * The two links are the point of the component: an answer containing code you cannot get into the
 * file without re-selecting and copying it by hand is only half an answer.
 */
class CodeBlockView(private val project: Project, language: String, code: String) : JPanel(BorderLayout()) {
    private var currentCode = code

    private val editor = EditorTextField(code, project, fileTypeFor(language)).apply {
        setOneLineMode(false)
        isViewer = true
        addSettingsProvider { configure(it) }
    }

    private val header = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(2, 6)
        add(JLabel(language.ifBlank { "text" }).apply { foreground = JBColor.GRAY }, BorderLayout.WEST)
        add(
            JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                isOpaque = false
                add(ActionLink("Copy") { copyToClipboard() })
                add(ActionLink("Insert") { insertAtCaret() })
            },
            BorderLayout.EAST,
        )
    }

    init {
        isOpaque = false
        border = JBUI.Borders.compound(
            JBUI.Borders.empty(4, 0),
            JBUI.Borders.customLine(JBColor.border(), 1),
        )
        add(header, BorderLayout.NORTH)
        add(editor, BorderLayout.CENTER)
    }

    /** Replaces the text in place, so a block that is still streaming reuses one editor. */
    fun setCode(code: String) {
        if (code == currentCode) return
        currentCode = code
        editor.text = code
        revalidate()
    }

    private fun copyToClipboard() = CopyPasteManager.getInstance().setContents(StringSelection(currentCode))

    private fun insertAtCaret() {
        val target = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        val text = currentCode
        // A single command, so a paste in the wrong place is undone with one Ctrl+Z rather than
        // line by line.
        WriteCommandAction.runWriteCommandAction(project, "Insert Code from LiteLLM Chat", null, {
            val document = target.document
            val caret = target.caretModel
            val selection = target.selectionModel
            val offset = if (selection.hasSelection()) {
                document.replaceString(selection.selectionStart, selection.selectionEnd, text)
                selection.selectionStart + text.length
            } else {
                document.insertString(caret.offset, text)
                caret.offset + text.length
            }
            selection.removeSelection()
            caret.moveToOffset(offset)
        })
    }

    private fun configure(editorEx: EditorEx) {
        // Soft wrap is deliberately off. Wrapping rewraps indentation and makes the structure of
        // the snippet read wrongly; a horizontal scrollbar is the lesser evil in a narrow dock,
        // and it also keeps the height calculation below exact.
        editorEx.setHorizontalScrollbarVisible(true)
        editorEx.setVerticalScrollbarVisible(false)
        editorEx.settings.apply {
            isLineNumbersShown = false
            isLineMarkerAreaShown = false
            isFoldingOutlineShown = false
            isRightMarginShown = false
            isCaretRowShown = false
            isUseSoftWraps = false
            isVirtualSpace = false
            isAdditionalPageAtBottom = false
            additionalLinesCount = 0
            additionalColumnsCount = 0
            setGutterIconsShown(false)
        }
        // A code block needs a raised surface. Matching the tool-window background is still too
        // dark in the new IntelliJ dark theme, where panel and editor backgrounds are almost
        // black. This softer charcoal keeps syntax colours readable without making the block a
        // bright light-theme island.
        editorEx.setBackgroundColor(CODE_BACKGROUND)
        editorEx.setBorder(JBUI.Borders.empty(4))
    }

    /**
     * An [EditorTextField] does not report the height of its content, so inside a vertical stack
     * with no height to hand out it would collapse to a single line. The block therefore measures
     * itself from its own line count.
     */
    override fun getPreferredSize(): Dimension {
        val lines = currentCode.count { it == '\n' } + 1
        val lineHeight = editor.editor?.lineHeight ?: JBUI.scale(18)
        val chrome = header.preferredSize.height + JBUI.scale(28)   // padding, border, scrollbar
        // Width stays small on purpose: the transcript stretches blocks to the viewport, and a
        // long line here must scroll rather than widen the whole panel.
        return Dimension(JBUI.scale(50), chrome + lines * lineHeight)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

/**
 * Maps a fence's language tag onto a registered [FileType], which is what actually drives the
 * highlighting.
 *
 * Models write the human name ("python", "kotlin") while the IDE indexes by extension, so the
 * common tags are translated before the tag itself is tried as an extension. Anything still
 * unrecognised renders as plain text, which is the right outcome — no highlighting beats wrong
 * highlighting.
 */
internal fun fileTypeFor(language: String): FileType {
    val tag = language.trim().lowercase()
    if (tag.isEmpty()) return PlainTextFileType.INSTANCE
    val extension = LANGUAGE_EXTENSIONS[tag] ?: tag
    val byName = FileTypeManager.getInstance().getFileTypeByFileName("snippet.$extension")
    return if (byName is UnknownFileType) PlainTextFileType.INSTANCE else byName
}

private val LANGUAGE_EXTENSIONS = mapOf(
    "kotlin" to "kt",
    "java" to "java",
    "python" to "py",
    "python3" to "py",
    "javascript" to "js",
    "typescript" to "ts",
    "typescriptreact" to "tsx",
    "csharp" to "cs",
    "c++" to "cpp",
    "golang" to "go",
    "rust" to "rs",
    "ruby" to "rb",
    "shell" to "sh",
    "bash" to "sh",
    "zsh" to "sh",
    "console" to "sh",
    "yml" to "yaml",
    "markdown" to "md",
    "text" to "txt",
    "plaintext" to "txt",
)

private val CODE_BACKGROUND = JBColor(Color(0xF6, 0xF8, 0xFA), Color(0x3C, 0x3F, 0x41))
