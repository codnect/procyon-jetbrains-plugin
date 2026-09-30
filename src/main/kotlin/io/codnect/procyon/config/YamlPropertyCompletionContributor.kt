package io.codnect.procyon.config

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import io.codnect.procyon.ProcyonIcons
import org.jetbrains.yaml.YAMLUtil
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

/**
 * Suggests the keys of Procyon properties in the YAML files under `resources`, like Spring does
 * for `application.yml`. The keys come from the `Properties` structs of the project.
 */
class YamlPropertyCompletionContributor : CompletionContributor() {

    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(
                parameters: CompletionParameters,
                context: ProcessingContext,
                result: CompletionResultSet,
            ) {
                val position = parameters.position
                val file = parameters.originalFile.virtualFile ?: return
                if ("/resources/" !in file.path) return

                val parent = parentPath(parameters.originalFile.text, parameters.offset) ?: return
                val yaml = parameters.originalFile as? YAMLFile
                val typedLength = result.prefixMatcher.prefix.length

                // Like Spring, every remaining property is offered by its full name, and it can be
                // found by any trailing part of it, so `port` finds `server.port`.
                for (property in PropertyIndex.of(position.project)) {
                    if (property.keys.size <= parent.size || property.keys.subList(0, parent.size) != parent) continue

                    val rest = property.keys.drop(parent.size)
                    val name = rest.joinToString(".")
                    val element = LookupElementBuilder.create(name)
                        .withLookupStrings(rest.indices.map { rest.drop(it).joinToString(".") })
                        .withIcon(ProcyonIcons.ConfigurationProperties)
                        .withTypeText(property.type, true)
                        .withTailText(
                            listOfNotNull(property.default?.let { "= $it" }, property.doc?.let { "— $it" })
                                .joinToString("  ", prefix = "  ").takeIf { it.isNotBlank() },
                            true,
                        )
                        .withInsertHandler { ctx, _ -> insertNested(ctx, yaml, parent, rest, parameters.offset, typedLength, property.group) }
                    result.addElement(element)
                }
            }
        })
    }

    /**
     * Writes `a.b.c` as nested keys. When `a` (or `a.b`) already exists in the file, only the missing
     * keys are added to the end of that existing block, like Spring does. Otherwise they are written
     * under the caret's indentation. The caret ends on the value of the last key.
     */
    private fun insertNested(
        ctx: InsertionContext,
        file: YAMLFile?,
        parent: List<String>,
        rest: List<String>,
        caret: Int,
        typedLength: Int,
        group: Boolean,
    ) {
        val document = ctx.document
        val text = document.immutableCharSequence

        // The longest leading part of `rest` that already exists, not counting the line being typed.
        var existing: YAMLKeyValue? = null
        var depth = 0
        if (file != null) {
            for (count in rest.size - 1 downTo 1) {
                // The line being typed can parse as a key too, so it is skipped and the next match is used.
                val found = keyValues(file, parent + rest.take(count)).firstOrNull { !it.textRange.containsOffset(caret) }
                if (found != null) {
                    existing = found
                    depth = count
                    break
                }
            }
        }

        val lineStart = document.getLineStartOffset(document.getLineNumber(ctx.startOffset))
        val indent = text.subSequence(lineStart, ctx.startOffset).takeWhile { it == ' ' }.toString()

        if (existing == null) {
            val insert = keys(rest, indent, firstIndent = "", group)
            document.replaceString(ctx.startOffset, ctx.tailOffset, insert)
            ctx.editor.caretModel.moveToOffset(ctx.startOffset + insert.length)
            // The keys of the group are offered right away, without asking for completion again.
            if (group) AutoPopupController.getInstance(ctx.project).scheduleAutoPopup(ctx.editor)
            return
        }

        // The block's offsets come from the file as it was before the prefix was replaced.
        val shift = (ctx.tailOffset - ctx.startOffset) - typedLength
        var blockEnd = existing.textRange.endOffset
        if (blockEnd > caret) blockEnd += shift

        val keyColumn = existing.textRange.startOffset.let { start ->
            start - text.subSequence(0, minOf(start, text.length)).lastIndexOf('\n').plus(1)
        }
        val childColumn = (existing.value as? YAMLMapping)?.keyValues?.firstOrNull()?.textRange?.startOffset
            ?.let { start -> start - (text.subSequence(0, minOf(start, text.length)).lastIndexOf('\n') + 1) }
            ?: (keyColumn + 2)

        val insert = "\n" + keys(rest.drop(depth), " ".repeat(childColumn), firstIndent = " ".repeat(childColumn), group)

        // Remove the line that was being typed, or just the typed text when other text shares the line.
        val lineEnd = document.getLineEndOffset(document.getLineNumber(ctx.startOffset))
        val onlyTyped = text.subSequence(lineStart, ctx.startOffset).isBlank() &&
            text.subSequence(ctx.tailOffset, lineEnd).isBlank()
        val deleteStart = if (onlyTyped) lineStart else ctx.startOffset
        val deleteEnd = if (onlyTyped) minOf(lineEnd + 1, text.length) else ctx.tailOffset

        // Apply the later edit first so the earlier offsets stay valid.
        val caretAfter: Int
        if (blockEnd >= deleteEnd) {
            document.insertString(blockEnd, insert)
            document.deleteString(deleteStart, deleteEnd)
            caretAfter = blockEnd + insert.length - (deleteEnd - deleteStart)
        } else {
            document.deleteString(deleteStart, deleteEnd)
            document.insertString(blockEnd, insert)
            caretAfter = blockEnd + insert.length
        }
        ctx.editor.caretModel.moveToOffset(caretAfter)
        if (group) AutoPopupController.getInstance(ctx.project).scheduleAutoPopup(ctx.editor)
    }

    /** Returns every key at [path] in the file, so that a key that is written twice is not missed. */
    private fun keyValues(file: YAMLFile, path: List<String>): List<YAMLKeyValue> =
        file.documents.flatMap { keyValues(it.topLevelValue as? YAMLMapping, path) }

    private fun keyValues(mapping: YAMLMapping?, path: List<String>): List<YAMLKeyValue> {
        mapping ?: return emptyList()
        val matches = mapping.keyValues.filter { it.keyText == path.first() }
        return if (path.size == 1) matches else matches.flatMap { keyValues(it.value as? YAMLMapping, path.drop(1)) }
    }

    /** Renders nested keys, one per line, each two spaces deeper than the previous. */
    private fun keys(keys: List<String>, indent: String, firstIndent: String, group: Boolean): String =
        keys.mapIndexed { level, key ->
            val line = (if (level == 0) firstIndent else indent + "  ".repeat(level)) + key
            when {
                level < keys.lastIndex -> "$line:"
                // A group has no value of its own, so the caret goes on the next line, one level deeper.
                group -> "$line:\n" + indent + "  ".repeat(level + 1)
                else -> "$line: "
            }
        }.joinToString("\n")

    /**
     * Returns the keys above the one being typed, found from the indentation of the text, or null when
     * the caret is on a value. The text is used instead of the parsed tree because a line that is still
     * being typed is not always parsed as being under the key above it.
     */
    private fun parentPath(text: CharSequence, offset: Int): List<String>? {
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        val before = text.subSequence(lineStart, offset).toString()
        if (!TYPING_KEY.matches(before)) return null

        var indent = before.takeWhile { it == ' ' }.length
        val path = ArrayDeque<String>()
        var lineEnd = lineStart

        // Go up, keeping each line that is less indented than the one below it: those are the parents.
        while (indent > 0 && lineEnd > 0) {
            val end = lineEnd - 1
            val start = text.lastIndexOf('\n', end - 1) + 1
            val line = text.subSequence(start, end).toString()
            lineEnd = start

            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val lineIndent = line.takeWhile { it == ' ' }.length
            if (lineIndent >= indent) continue

            val key = KEY.find(line)?.groupValues?.get(1) ?: continue
            path.addFirst(key)
            indent = lineIndent
        }

        return path.toList()
    }

    private companion object {
        val TYPING_KEY = Regex("""\s*[\w.\-]*""")
        val KEY = Regex("""^\s*([^\s#:][^:#]*?)\s*:(?:\s|$)""")
    }
}
