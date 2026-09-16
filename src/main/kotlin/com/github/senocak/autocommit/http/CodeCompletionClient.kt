package com.github.senocak.autocommit.http

import com.github.senocak.autocommit.completion.CaretContext
import com.github.senocak.autocommit.settings.ApiConfiguration
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger

/**
 * Fill-in-the-middle code completion over the same Chat Completions route the commit-message
 * feature uses.
 *
 * Unlike that feature this one runs while someone is typing, so it is deliberately impatient:
 * a small token cap, a short read timeout, and no Anthropic fallback. If the chat route is
 * missing, completion simply stays quiet rather than doubling the latency to find out.
 */
object CodeCompletionClient {
    private const val MAX_OUTPUT_TOKENS = 256

    /** Well under the 60s used for commit messages — a completion nobody waited for is useless. */
    private const val READ_TIMEOUT_MS = 20_000

    private val LOG = Logger.getInstance(CodeCompletionClient::class.java)

    private val SYSTEM_PROMPT = """
        You are a code completion engine. Continue the code at the <CURSOR> marker.

        Rules:
        Output only the code that replaces <CURSOR>.
        Do not repeat any code before or after the marker.
        Do not use Markdown code fences.
        Do not explain anything.
        Match the surrounding indentation and style.
        If nothing sensible follows, output nothing at all.
    """.trimIndent()

    fun complete(configuration: ApiConfiguration, model: String, context: CaretContext): String {
        val user = buildString {
            append("File: ").append(context.path).append('\n')
            append("Language: ").append(context.language).append("\n\n")
            append(context.prefix).append("<CURSOR>").append(context.suffix)
        }

        val payload = GatewayClient.chatPayload(model, SYSTEM_PROMPT, user, MAX_OUTPUT_TOKENS)
        val response = GatewayClient.post(configuration, configuration.chatUrl, payload, READ_TIMEOUT_MS)
        val raw = extractContent(response)
        return sanitize(raw, context)
    }

    private fun extractContent(response: String): String = try {
        JsonParser.parseString(response).asJsonObject
            .getAsJsonArray("choices")
            ?.firstOrNull()?.asJsonObject?.getAsJsonObject("message")
            ?.get("content")?.takeIf { it.isJsonPrimitive }?.asString
            .orEmpty()
    } catch (exception: Exception) {
        LOG.debug("LiteLLM Integration: completion response was not valid JSON", exception)
        ""
    }
}

/**
 * Turns a model's reply into something safe to render as ghost text.
 *
 * Models ignore "no fences, no prose, do not repeat the prefix" often enough that the prompt
 * alone cannot be relied on, and a bad suggestion here is not a wrong answer in a chat window —
 * it is duplicated text pasted into the user's file on Tab. Internal so it can be tested directly.
 */
internal fun sanitize(raw: String, context: CaretContext): String {
    var text = unwrapCodeFences(raw)
    if (text.isBlank()) return ""

    // Some models restate the line the caret sits on before continuing it, which on accept would
    // duplicate what is already typed.
    val currentLine = context.prefix.substringAfterLast('\n')
    if (currentLine.isNotBlank() && text.startsWith(currentLine)) {
        text = text.removePrefix(currentLine)
    }

    // A reply that opens with prose ("Here is the completion:") is not code; drop that line.
    // Matched against a narrow list of English openers rather than "ends with a colon", which
    // silently ate `if n < 0:`, `for x in y:` and every other Python block header.
    val firstLine = text.lineSequence().firstOrNull().orEmpty()
    if (PROSE_PREAMBLE.matches(firstLine)) {
        text = text.substringAfter('\n', "")
    }

    // Mid-line completions stay on one line: rendering a multi-line block from the middle of an
    // expression produces ghost text that cannot be read against the code it overlaps.
    if (currentLine.isNotBlank()) {
        text = text.substringBefore('\n')
    } else {
        // At the start of a fresh line a block is fine, but stop at the first blank line so one
        // over-eager model does not write the rest of the file.
        val blank = text.indexOf("\n\n")
        if (blank >= 0) text = text.substring(0, blank)
    }

    // Never propose text the file already has immediately after the caret.
    val suffixStart = context.suffix.trimStart().substringBefore('\n').trim()
    if (suffixStart.isNotBlank()) {
        if (text.trim() == suffixStart) return ""
        // Models routinely close a block the file already closes — completing a function body
        // and adding the "}" that is sitting on the next line. Accepting that leaves a stray
        // brace behind, so drop a final line that merely repeats what follows.
        val lines = text.lines()
        if (lines.size > 1 && lines.last().trim() == suffixStart) {
            text = lines.dropLast(1).joinToString("\n")
        }
    }

    return text.trimEnd()
}

/**
 * Unwraps Markdown fences without touching indentation.
 *
 * Deliberately not [GatewayClient.stripFences]: that one trims the whole string, which is right
 * for a commit message and wrong for code — it would strip the leading spaces off the first line
 * and paste the suggestion flush against the margin.
 */
/**
 * Conversational openers a model sometimes prefixes to the code. Kept to an explicit list of
 * English phrases: any broader rule (such as "a line ending in a colon") also matches ordinary
 * block headers in Python, YAML and Kotlin `when` branches, and deletes real code.
 */
private val PROSE_PREAMBLE = Regex(
    """\s*(here'?s?( is)?|sure|certainly|of course|okay|ok|the following|completion)\b[^\n]*:\s*""",
    RegexOption.IGNORE_CASE,
)

private fun unwrapCodeFences(raw: String): String {
    val withoutBlankEdges = raw.trim('\n', '\r')
    if (!withoutBlankEdges.trimStart().startsWith("```")) return withoutBlankEdges.trimEnd()

    return withoutBlankEdges
        .substringAfter("```")
        .substringBeforeLast("```")
        .lineSequence()
        .drop(1)              // the fence's opening line, language tag and all
        .joinToString("\n")
        .trim('\n', '\r')
        .trimEnd()
}
