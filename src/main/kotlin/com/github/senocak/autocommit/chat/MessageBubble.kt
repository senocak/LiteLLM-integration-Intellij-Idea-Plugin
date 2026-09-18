package com.github.senocak.autocommit.chat

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.datatransfer.StringSelection
import javax.swing.JPanel

/**
 * One message in the transcript: a role header and the message's blocks stacked underneath.
 *
 * The important behaviour is [update]. A streaming answer is re-rendered many times a second, and
 * rebuilding every child each time would flicker, drop the scroll position and throw away the
 * editors inside code blocks. So the block list is diffed against what is already on screen and
 * only the parts that genuinely changed — in practice the last one — are touched.
 */
class MessageBubble(private val project: Project, val role: Role) : JPanel(BorderLayout()) {
    private val body = JPanel(VerticalLayout(JBUI.scale(2))).apply { isOpaque = false }
    private val header = JBLabel().apply {
        foreground = JBColor.GRAY
        font = JBUI.Fonts.smallFont().asBold()
    }
    private val copyLink = ActionLink("Copy") { copyWholeMessage() }

    /** What each child component currently renders, so [update] can tell a change from a repaint. */
    private val rendered = mutableListOf<Block>()
    private var message = ChatMessage(role, "")

    init {
        isOpaque = true
        background = if (role == Role.USER) userBackground() else UIUtil.getPanelBackground()
        border = JBUI.Borders.compound(
            JBUI.Borders.empty(4, 6),
            JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(6, 8),
            ),
        )

        val top = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(header, BorderLayout.WEST)
            add(copyLink, BorderLayout.EAST)
        }
        add(top, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
    }

    fun update(message: ChatMessage) {
        this.message = message
        val requested = message.model?.takeIf { it.isNotBlank() }
        val provider = message.providerModel?.takeIf { it.isNotBlank() }
        val source = when {
            requested != null && provider != null && requested != provider ->
                "${label()} · $requested → $provider"
            provider != null -> "${label()} · $provider"
            requested != null -> "${label()} · $requested"
            else -> label()
        }
        header.toolTipText = when {
            requested != null && provider != null && requested != provider ->
                "Requested $requested; LiteLLM routed this reply to $provider."
            requested != null -> "Requested model: $requested"
            else -> null
        }
        header.text = when {
            message.error != null -> "$source — failed"
            message.streaming -> "$source — thinking…"
            else -> source
        }
        copyLink.isVisible = message.error == null && message.content.isNotBlank()

        // An error replaces the answer rather than sitting beside it: a half-answer plus a reason
        // is more confusing than the reason alone.
        val blocks = message.error
            ?.let { listOf<Block>(Block.Prose(it)) }
            ?: parseBlocks(message.content).ifEmpty {
                // A streaming message that has not produced a token yet still needs a body, or
                // the bubble collapses to its header and jumps when the first token lands.
                if (message.streaming) listOf(Block.Prose("…")) else emptyList()
            }

        applyBlocks(blocks)
    }

    private fun applyBlocks(blocks: List<Block>) {
        // Anything past the new length, or whose kind changed, has to be rebuilt. Everything
        // before that point can be reused, which for a streaming answer is all of it but the tail.
        val reusable = blocks.indices.takeWhile { it < rendered.size && sameKind(rendered[it], blocks[it]) }.count()

        while (rendered.size > reusable) {
            body.remove(rendered.size - 1)
            rendered.removeAt(rendered.size - 1)
        }

        blocks.forEachIndexed { index, block ->
            if (index < reusable) {
                if (rendered[index] != block) refresh(body.getComponent(index), block)
                rendered[index] = block
            } else {
                body.add(create(block))
                rendered += block
            }
        }

        body.revalidate()
        body.repaint()
    }

    private fun sameKind(left: Block, right: Block): Boolean = when {
        left is Block.Prose && right is Block.Prose -> true
        // The language tag drives the highlighting, which an EditorTextField fixes at
        // construction, so a change there means a new component.
        left is Block.Code && right is Block.Code -> left.language == right.language
        else -> false
    }

    private fun create(block: Block): Component = when (block) {
        is Block.Prose -> ProseView(inlineHtml(block.text))
        is Block.Code -> CodeBlockView(project, block.language, block.code)
    }

    private fun refresh(component: Component, block: Block) {
        when {
            component is ProseView && block is Block.Prose -> component.setHtml(inlineHtml(block.text))
            component is CodeBlockView && block is Block.Code -> component.setCode(block.code)
        }
    }

    private fun copyWholeMessage() =
        com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(StringSelection(message.content))

    private fun label(): String = if (role == Role.USER) "You" else "LiteLLM"

    /**
     * Selections often contain a full editor-sized code block. A full `Color.darker()` step made
     * that large surface muddy in light themes, so role is distinguished by the header and border
     * rather than by darkening the code's background.
     */
    private fun userBackground(): JBColor = JBColor.lazy {
        UIUtil.getPanelBackground()
    }
}
