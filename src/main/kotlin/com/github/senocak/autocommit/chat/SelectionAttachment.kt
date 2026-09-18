package com.github.senocak.autocommit.chat

/**
 * Formats an editor selection as the fenced block that goes into the chat input.
 *
 * Pure so the fencing rule can be tested directly — it is the part that quietly breaks. A snippet
 * that itself contains a Markdown fence (a README, a docstring with an example, this plugin's own
 * source) would terminate a three-backtick wrapper early, and everything after that point would
 * be read by the model as prose rather than code.
 */
fun fencedSelection(fileName: String, startLine: Int, endLine: Int, language: String, code: String): String {
    val trimmed = code.trimEnd()
    val fence = "`".repeat(maxOf(3, longestBacktickRun(trimmed) + 1))
    val range = if (startLine == endLine) "$fileName:$startLine" else "$fileName:$startLine-$endLine"
    return buildString {
        append(range).append('\n')
        append(fence).append(language).append('\n')
        append(trimmed).append('\n')
        append(fence)
    }
}

private fun longestBacktickRun(text: String): Int {
    var longest = 0
    var run = 0
    for (character in text) {
        if (character == '`') {
            run++
            if (run > longest) longest = run
        } else {
            run = 0
        }
    }
    return longest
}
