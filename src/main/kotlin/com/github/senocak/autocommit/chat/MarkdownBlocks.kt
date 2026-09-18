package com.github.senocak.autocommit.chat

/**
 * Minimal Markdown handling for the chat transcript.
 *
 * Deliberately not a full Markdown implementation. The transcript only needs to tell code apart
 * from prose — code goes into a real editor with syntax highlighting, prose into an HTML pane —
 * and to render the handful of inline marks models actually emit. Pure functions over strings so
 * every awkward case can be tested without a running IDE.
 */

/** One renderable region of a message. */
sealed interface Block {
    data class Prose(val text: String) : Block

    /**
     * @param closed whether the fence was terminated. False while a reply is still streaming and
     *   the model has opened a block it has not finished — the content is still worth rendering.
     */
    data class Code(val language: String, val code: String, val closed: Boolean) : Block
}

private val FENCE = Regex("^(\\s{0,3})(`{3,}|~{3,})\\s*([A-Za-z0-9+#._-]*)\\s*$")

/**
 * Splits a message into prose and fenced code blocks.
 *
 * The streaming case drives the design: this runs against a partial message many times per
 * response, so an unterminated fence has to produce a `Code` block rather than being discarded or
 * — worse — rendered as prose, which would make the block flicker between the two styles the
 * moment the model emits the closing fence.
 */
fun parseBlocks(markdown: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val pending = StringBuilder()
    var fence: String? = null
    var language = ""
    val code = StringBuilder()

    fun flushProse() {
        if (pending.isNotBlank()) blocks += Block.Prose(pending.toString().trim('\n'))
        pending.setLength(0)
    }

    for (line in markdown.lines()) {
        val match = FENCE.matchEntire(line)
        if (fence == null) {
            // A fence of at least the same character and length opens a block.
            if (match != null) {
                flushProse()
                fence = match.groupValues[2]
                language = match.groupValues[3]
                code.setLength(0)
            } else {
                pending.append(line).append('\n')
            }
            continue
        }

        // Inside a block: only a fence of the same character and at least the same length closes
        // it, which is what lets an attached snippet containing ``` survive a ```` wrapper.
        val closes = match != null &&
            match.groupValues[2].first() == fence.first() &&
            match.groupValues[2].length >= fence.length &&
            match.groupValues[3].isEmpty()
        if (closes) {
            blocks += Block.Code(language, code.toString().trimEnd('\n'), closed = true)
            fence = null
            language = ""
            code.setLength(0)
        } else {
            code.append(line).append('\n')
        }
    }

    if (fence != null) blocks += Block.Code(language, code.toString().trimEnd('\n'), closed = false)
    else flushProse()

    return blocks
}

/**
 * Renders a prose run as an HTML fragment.
 *
 * Escaping happens first and unconditionally: a reply is model output, and a model that has been
 * asked about HTML will happily emit `<script>`. Everything after this point is our own markup.
 */
fun inlineHtml(prose: String): String = prose
    .lines()
    .let(::groupIntoHtmlLines)
    .joinToString("\n")

private fun groupIntoHtmlLines(lines: List<String>): List<String> {
    val html = mutableListOf<String>()
    var listTag: String? = null

    fun closeList() {
        listTag?.let { html += "</$it>" }
        listTag = null
    }

    fun openList(tag: String) {
        if (listTag != tag) {
            closeList()
            html += "<$tag>"
            listTag = tag
        }
    }

    for (line in lines) {
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> {
                closeList()
                html += "<br>"
            }

            BULLET.matches(trimmed) -> {
                openList("ul")
                html += "<li>${inline(trimmed.substring(2))}</li>"
            }

            NUMBERED.matches(trimmed) -> {
                openList("ol")
                html += "<li>${inline(trimmed.substringAfter('.').trim())}</li>"
            }

            trimmed.startsWith("#") -> {
                closeList()
                val level = trimmed.takeWhile { it == '#' }.length.coerceAtMost(6)
                // Headings in a narrow side panel only need to read as emphasis, not as a
                // document hierarchy, so they all collapse to bold.
                html += "<b>${inline(trimmed.drop(level).trim())}</b><br>"
            }

            else -> {
                closeList()
                html += "${inline(trimmed)}<br>"
            }
        }
    }
    closeList()
    return html
}

private val BULLET = Regex("^[-*+] .*")
private val NUMBERED = Regex("^\\d+[.)] .*")

// Inline code first: whatever is inside a backtick span must not then be read as bold or italic.
private val INLINE_CODE = Regex("`([^`]+)`")
private val BOLD = Regex("\\*\\*([^*]+)\\*\\*")
private val ITALIC = Regex("(?<![*\\w])[*_]([^*_\\n]+)[*_](?![*\\w])")
private val LINK = Regex("\\[([^]]+)]\\((https?://[^)\\s]+)\\)")

private fun inline(text: String): String {
    val escaped = escapeHtml(text)
    // Placeholders keep code spans out of reach of the emphasis passes without writing a real
    // parser. The marker is NUL-delimited rather than anything textual so it cannot collide with
    // the model's own output: an asterisk inside `a*b` has to survive as a literal asterisk.
    val spans = mutableListOf<String>()
    val withoutCode = INLINE_CODE.replace(escaped) { match ->
        spans += match.groupValues[1]
        "\u0000${spans.size - 1}\u0000"
    }

    var result = LINK.replace(withoutCode) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }
    result = BOLD.replace(result) { "<b>${it.groupValues[1]}</b>" }
    result = ITALIC.replace(result) { "<i>${it.groupValues[1]}</i>" }

    spans.forEachIndexed { index, span -> result = result.replace("\u0000$index\u0000", "<code>$span</code>") }
    return result
}

internal fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
