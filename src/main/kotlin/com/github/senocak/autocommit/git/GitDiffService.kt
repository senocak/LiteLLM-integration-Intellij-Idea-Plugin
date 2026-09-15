package com.github.senocak.autocommit.git

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import java.io.IOException
import java.nio.file.Files

/**
 * Creates a unified, file-oriented diff from the exact [Change] instances supplied by the
 * Commit tool window. It deliberately does not invoke Git or inspect the working tree, so files
 * which are not included in the active commit cannot be uploaded.
 */
object GitDiffService {
    private const val CONTEXT_LINES = 3
    private const val MAX_DIFF_CHARACTERS = 200_000
    private const val MAX_FILE_DIFF_CHARACTERS = 40_000
    private const val MAX_UNVERSIONED_FILE_BYTES = 1_000_000
    private val LOG = Logger.getInstance(GitDiffService::class.java)

    fun buildDiff(
        changes: List<Change>,
        unversionedFiles: List<FilePath>,
        indicator: ProgressIndicator,
    ): String {
        val result = StringBuilder()
        val patches = changes.asSequence().map { it.toUnifiedDiff() } +
            unversionedFiles.asSequence().filterNot { it.isDirectory }.map { it.toNewFileDiff() }
        val total = changes.size + unversionedFiles.size
        for ((index, patch) in patches.withIndex()) {
            indicator.checkCanceled()
            indicator.text2 = "Reading included file ${index + 1} of $total"
            val remaining = MAX_DIFF_CHARACTERS - result.length
            if (remaining <= 0) {
                result.append("\n# Remaining selected file diffs omitted after $MAX_DIFF_CHARACTERS characters.\n")
                break
            }
            val fileLimit = minOf(remaining, MAX_FILE_DIFF_CHARACTERS)
            result.append(patch.truncatedTo(fileLimit))
            if (patch.length > fileLimit) {
                result.append("# Diff for this selected file truncated after $fileLimit characters.\n\n")
            }
        }
        return result.toString().trim()
    }

    /** Keep complete lines so the model receives valid, readable diff text even when capped. */
    private fun String.truncatedTo(limit: Int): String {
        if (length <= limit) return this
        val lastNewline = lastIndexOf('\n', startIndex = limit - 1)
        return take(if (lastNewline > 0) lastNewline + 1 else limit)
    }

    private fun Change.toUnifiedDiff(): String {
        val beforePath = beforeRevision?.file?.path
        val afterPath = afterRevision?.file?.path
        val sourcePath = beforePath ?: afterPath ?: return ""
        val targetPath = afterPath ?: sourcePath
        val before = beforeRevision.readContent()
        val after = afterRevision.readContent()

        return buildString {
            append("diff --git a/").append(sourcePath).append(" b/").append(targetPath).append('\n')
            append("--- ").append(beforePath?.let { "a/$it" } ?: "/dev/null").append('\n')
            append("+++ ").append(afterPath?.let { "b/$it" } ?: "/dev/null").append('\n')
            if (before == null || after == null) {
                append("Binary or unavailable file content changed: ").append(targetPath).append('\n')
            } else {
                append(unifiedHunks(before.linesForDiff(), after.linesForDiff()))
            }
            append('\n')
        }
    }

    private fun FilePath.toNewFileDiff(): String {
        val content = readUnversionedContent()
        return buildString {
            append("diff --git a/").append(path).append(" b/").append(path).append('\n')
            append("new file mode 100644\n")
            append("--- /dev/null\n")
            append("+++ b/").append(path).append('\n')
            if (content == null) {
                append("Binary, large, or unavailable unversioned file added: ").append(path).append('\n')
            } else {
                append(unifiedHunks(emptyList(), content.linesForDiff()))
            }
            append('\n')
        }
    }

    private fun ContentRevision?.readContent(): String? {
        if (this == null) return ""
        return try {
            content
        } catch (exception: VcsException) {
            LOG.debug("Could not read selected change content", exception)
            null
        }
    }

    private fun FilePath.readUnversionedContent(): String? = try {
        val file = ioFile.toPath()
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_UNVERSIONED_FILE_BYTES) return null
        val bytes = Files.readAllBytes(file)
        if (bytes.any { it == 0.toByte() }) return null
        String(bytes, charset)
    } catch (exception: IOException) {
        LOG.debug("Could not read included unversioned file content", exception)
        null
    }

    private fun String.linesForDiff(): List<String> =
        if (isEmpty()) emptyList() else replace("\r\n", "\n").split('\n')

    private fun unifiedHunks(before: List<String>, after: List<String>): String {
        val operations = lcsOperations(before, after)
        val changedIndices = operations.indices.filter { operations[it].kind != Kind.EQUAL }
        if (changedIndices.isEmpty()) return ""

        val hunks = mutableListOf<IntRange>()
        var first = changedIndices.first()
        var last = first
        for (changed in changedIndices.drop(1)) {
            if (changed - last > CONTEXT_LINES * 2 + 1) {
                hunks += maxOf(0, first - CONTEXT_LINES)..minOf(operations.lastIndex, last + CONTEXT_LINES)
                first = changed
            }
            last = changed
        }
        hunks += maxOf(0, first - CONTEXT_LINES)..minOf(operations.lastIndex, last + CONTEXT_LINES)

        return buildString {
            for (range in hunks) {
                val oldBefore = operations.take(range.first).count { it.kind != Kind.ADD }
                val newBefore = operations.take(range.first).count { it.kind != Kind.REMOVE }
                val oldCount = operations.slice(range).count { it.kind != Kind.ADD }
                val newCount = operations.slice(range).count { it.kind != Kind.REMOVE }
                val oldStart = if (oldCount == 0) 0 else oldBefore + 1
                val newStart = if (newCount == 0) 0 else newBefore + 1
                append("@@ -").append(oldStart).append(',').append(oldCount)
                    .append(" +").append(newStart).append(',').append(newCount).append(" @@\n")
                for (operation in operations.slice(range)) {
                    append(operation.kind.prefix).append(operation.line).append('\n')
                }
            }
        }
    }

    private fun lcsOperations(before: List<String>, after: List<String>): List<LineOperation> {
        // A quadratic matrix keeps normal source-file diffs accurate. Large files fall back to a
        // single replacement hunk rather than risking excessive IDE memory use.
        if (before.size.toLong() * after.size > 1_000_000L) {
            return before.map { LineOperation(Kind.REMOVE, it) } + after.map { LineOperation(Kind.ADD, it) }
        }
        val matrix = Array(before.size + 1) { IntArray(after.size + 1) }
        for (i in before.indices.reversed()) {
            for (j in after.indices.reversed()) {
                matrix[i][j] = if (before[i] == after[j]) matrix[i + 1][j + 1] + 1
                else maxOf(matrix[i + 1][j], matrix[i][j + 1])
            }
        }

        val result = mutableListOf<LineOperation>()
        var i = 0
        var j = 0
        while (i < before.size && j < after.size) {
            when {
                before[i] == after[j] -> {
                    result += LineOperation(Kind.EQUAL, before[i]); i++; j++
                }
                matrix[i + 1][j] >= matrix[i][j + 1] -> {
                    result += LineOperation(Kind.REMOVE, before[i]); i++
                }
                else -> {
                    result += LineOperation(Kind.ADD, after[j]); j++
                }
            }
        }
        while (i < before.size) result += LineOperation(Kind.REMOVE, before[i++])
        while (j < after.size) result += LineOperation(Kind.ADD, after[j++])
        return result
    }

    private data class LineOperation(val kind: Kind, val line: String)
    private enum class Kind(val prefix: Char) { EQUAL(' '), REMOVE('-'), ADD('+') }
}
