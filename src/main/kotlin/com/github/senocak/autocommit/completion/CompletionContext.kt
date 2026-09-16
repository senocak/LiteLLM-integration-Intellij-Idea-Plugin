package com.github.senocak.autocommit.completion

/**
 * The slice of the file sent with a completion request.
 *
 * Kept as a pure function over text and offset — no Document, Editor or PsiFile — so the
 * windowing rules can be tested without starting an IDE.
 */
data class CaretContext(
    val prefix: String,
    val suffix: String,
    val language: String,
    val path: String,
) {
    /** True when there is nothing to continue from; the caller should not bother asking a model. */
    val isEmpty: Boolean get() = prefix.isBlank() && suffix.isBlank()
}

private const val PREFIX_LINES = 60
private const val SUFFIX_LINES = 20

/**
 * A hard character ceiling on top of the line budget. Generated sources and minified files can
 * hold a single line of tens of thousands of characters, which would otherwise be sent whole.
 */
private const val PREFIX_CHARS = 6_000
private const val SUFFIX_CHARS = 2_000

fun caretContext(text: String, offset: Int, language: String, path: String): CaretContext {
    val safeOffset = offset.coerceIn(0, text.length)
    val before = text.substring(0, safeOffset)
    val after = text.substring(safeOffset)

    return CaretContext(
        prefix = before.takeLastLines(PREFIX_LINES).takeLast(PREFIX_CHARS),
        suffix = after.takeFirstLines(SUFFIX_LINES).take(SUFFIX_CHARS),
        language = language,
        path = path,
    )
}

/**
 * Trimming on line boundaries rather than mid-line: a prefix that starts halfway through an
 * identifier reads as a syntax error to the model and derails the completion.
 */
private fun String.takeLastLines(count: Int): String {
    var remaining = count
    var index = length
    while (index > 0) {
        val newline = lastIndexOf('\n', index - 1)
        if (newline < 0) return this
        if (--remaining <= 0) return substring(newline + 1)
        index = newline
    }
    return this
}

private fun String.takeFirstLines(count: Int): String {
    var remaining = count
    var index = 0
    while (index < length) {
        val newline = indexOf('\n', index)
        if (newline < 0) return this
        if (--remaining <= 0) return substring(0, newline)
        index = newline + 1
    }
    return this
}
