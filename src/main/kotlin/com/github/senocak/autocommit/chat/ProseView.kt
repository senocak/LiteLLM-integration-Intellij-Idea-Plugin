package com.github.senocak.autocommit.chat

import com.intellij.ide.BrowserUtil
import com.intellij.ui.ColorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import javax.swing.JEditorPane
import javax.swing.event.HyperlinkEvent
import javax.swing.plaf.TextUI
import javax.swing.text.View

/**
 * A run of prose between code blocks, rendered as HTML.
 *
 * Swing's HTML support is enough for what models actually emit — emphasis, inline code, lists,
 * links — and unlike a JCEF browser it costs nothing to create dozens of and it inherits the
 * IDE's fonts and theme for free.
 */
class ProseView(html: String) : JEditorPane() {
    private var currentHtml = ""

    init {
        contentType = "text/html"
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty(2, 0)
        // Without this the pane renders in the HTML default serif rather than the IDE's UI font.
        putClientProperty(HONOR_DISPLAY_PROPERTIES, true)
        font = UIUtil.getLabelFont()
        addHyperlinkListener { event ->
            if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) event.url?.let { BrowserUtil.browse(it) }
        }
        setHtml(html)
    }

    fun setHtml(html: String) {
        if (html == currentHtml) return
        currentHtml = html
        text = wrap(html)
        revalidate()
    }

    /**
     * Height depends on width for wrapped text, and a [JEditorPane] asked for its preferred size
     * before it has been laid out reports the height of a single unwrapped line.
     *
     * The root view is measured directly rather than by calling `setSize` on the component first:
     * resizing from inside `getPreferredSize` invalidates the component in the middle of the
     * layout pass that asked the question, which sends Swing round again.
     */
    override fun getPreferredSize(): Dimension {
        val available = if (width > 0) width else parent?.width?.takeIf { it > 0 } ?: JBUI.scale(300)
        // `JEditorPane.ui` is exposed as ComponentUI in Kotlin, even though this component
        // always installs a TextUI. Cast explicitly so the text-layout API is available.
        val root = (ui as? TextUI)?.getRootView(this) ?: return super.getPreferredSize()
        val insets = insets
        val content = (available - insets.left - insets.right).coerceAtLeast(JBUI.scale(50))
        root.setSize(content.toFloat(), Float.MAX_VALUE)
        val height = root.getPreferredSpan(View.Y_AXIS).toInt() + insets.top + insets.bottom
        // Width stays small: the transcript stretches children to the viewport, and reporting a
        // real width here would let one long line widen the whole panel.
        return Dimension(JBUI.scale(50), height)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    private fun wrap(html: String): String {
        val text = ColorUtil.toHtmlColor(UIUtil.getLabelForeground())
        val codeBackground = ColorUtil.toHtmlColor(UIUtil.getPanelBackground().let { ColorUtil.darker(it, 1) })
        val link = ColorUtil.toHtmlColor(JBUI.CurrentTheme.Link.Foreground.ENABLED)
        return """
            <html><head><style>
              body { color: $text; margin: 0; padding: 0; }
              code { background-color: $codeBackground; }
              a { color: $link; }
              ul, ol { margin-top: 0; margin-bottom: 0; padding-left: 18px; }
            </style></head><body>$html</body></html>
        """.trimIndent()
    }
}
