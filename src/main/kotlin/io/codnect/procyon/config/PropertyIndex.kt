package io.codnect.procyon.config

import com.goide.psi.GoTypeSpec
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import io.codnect.procyon.GoFiles

/** A property bound by a Procyon properties struct, e.g. `server.port` from `ServerProperties.Port`. */
internal class Property(
    val keys: List<String>,
    val type: String,
    val owner: String,
    val default: String?,
    val doc: String?,
    /** True for the prefix of a properties struct itself, which has no value but holds the fields below it. */
    val group: Boolean = false,
)

/** Collects the properties of every struct that has a `Prefix() string` method. It holds no PSI. */
internal object PropertyIndex {

    private val LIBRARIES_KEY = Key.create<CachedValue<List<Property>>>("procyon.library.properties")

    /** The properties of the project's own structs plus those of the libraries it depends on. */
    fun of(project: Project): List<Property> = project(project) + libraries(project)

    private fun project(project: Project): List<Property> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(
                collect(GoFiles.withWord(project, "Prefix")),
                PsiModificationTracker.MODIFICATION_COUNT,
            )
        }

    // Libraries change rarely, so they are read once per change of the project roots. The word is
    // very common in libraries, so only files that use the property tag are parsed.
    private fun libraries(project: Project): List<Property> =
        CachedValuesManager.getManager(project).getCachedValue(project, LIBRARIES_KEY, {
            val index = ProjectFileIndex.getInstance(project)
            val files = GoFiles.withWord(project, "Prefix", GlobalSearchScope.allScope(project), "property:\"")
                .filter { file -> file.virtualFile?.let { !index.isInContent(it) } ?: false }
            CachedValueProvider.Result.create(collect(files), ProjectRootModificationTracker.getInstance(project))
        }, false)

    private fun collect(files: List<PsiFile>): List<Property> {
        val properties = mutableListOf<Property>()

        for (file in files) {
            for (typeSpec in PsiTreeUtil.findChildrenOfType(file, GoTypeSpec::class.java)) {
                val prefix = ProcyonProperties.prefixOf(typeSpec)?.let(ProcyonProperties::split) ?: continue
                val owner = typeSpec.name.orEmpty()

                // The prefix is offered by itself too, so a struct without tagged fields is still found.
                properties += Property(prefix, owner, owner, null, docOf(typeSpec.parent ?: typeSpec), group = true)

                for (field in ProcyonProperties.boundFields(typeSpec)) {
                    val tag = field.tag ?: continue
                    val name = ProcyonProperties.nameOf(tag) ?: continue
                    val default = tag.getValue("property")?.split(',')
                        ?.firstOrNull { it.trim().startsWith("default=") }
                        ?.substringAfter("default=")?.trim()

                    properties += Property(
                        prefix + ProcyonProperties.split(name),
                        field.type?.text.orEmpty(),
                        owner,
                        default,
                        docOf(field),
                    )
                }
            }
        }

        return properties
    }

    /** Returns the first sentence of the comment right above the field, or null when there is none. */
    private fun docOf(field: PsiElement): String? {
        val lines = ArrayDeque<String>()
        var previous = field.prevSibling

        // Comments count only while they touch the field, so a blank line ends the doc comment.
        while (previous != null) {
            if (previous is PsiWhiteSpace) {
                if (previous.text.count { it == '\n' } > 1) break
            } else if (previous is PsiComment) {
                lines.addFirst(previous.text.removePrefix("//").removePrefix("/*").removeSuffix("*/").trim())
            } else {
                break
            }
            previous = previous.prevSibling
        }

        val text = lines.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return null
        val sentence = text.substringBefore(". ").removeSuffix(".")
        return if (sentence.length > MAX_DOC) sentence.take(MAX_DOC - 1) + "…" else sentence
    }

    private const val MAX_DOC = 80
}
