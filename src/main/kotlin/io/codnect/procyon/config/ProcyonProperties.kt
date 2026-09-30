package io.codnect.procyon.config

import com.goide.psi.GoFieldDeclaration
import com.goide.psi.GoMethodDeclaration
import com.goide.psi.GoReturnStatement
import com.goide.psi.GoStringLiteral
import com.goide.psi.GoStructType
import com.goide.psi.GoTag
import com.goide.psi.GoTypeSpec
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import io.codnect.procyon.GoFiles
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.YAMLUtil
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue

/** Shared lookups for Procyon configuration properties. */
internal object ProcyonProperties {

    private const val TAG = "property"

    /** Returns the property name of a tag such as `property:"port,default=8080"`, i.e. `port`. */
    fun nameOf(tag: GoTag): String? =
        tag.getValue(TAG)?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }

    /** Returns the full YAML key path of the field the tag belongs to. */
    fun keysOf(tag: GoTag): List<String>? {
        val name = nameOf(tag) ?: return null
        val typeSpec = PsiTreeUtil.getParentOfType(tag, GoTypeSpec::class.java) ?: return null
        val prefix = prefixOf(typeSpec) ?: return null
        return split(prefix) + split(name)
    }

    /** Returns whether the method is `Prefix() string`. */
    fun isPrefixMethod(method: GoMethodDeclaration): Boolean =
        method.name == "Prefix" &&
            method.signature?.parameters?.parameterDeclarationList.isNullOrEmpty() &&
            method.signature?.result?.type?.text == "string"

    /** Returns the string literal that the type's `Prefix() string` method returns. */
    fun prefixLiteral(typeSpec: GoTypeSpec): GoStringLiteral? {
        val method = PsiTreeUtil.findChildrenOfType(typeSpec.containingFile, GoMethodDeclaration::class.java)
            .firstOrNull { isPrefixMethod(it) && it.receiverType?.text?.removePrefix("*") == typeSpec.name }
            ?: return null

        return PsiTreeUtil.findChildOfType(method.block, GoReturnStatement::class.java)
            ?.expressionList?.singleOrNull() as? GoStringLiteral
    }

    fun prefixOf(typeSpec: GoTypeSpec): String? = prefixLiteral(typeSpec)?.decodedText

    /**
     * Returns the fields Procyon binds under the struct's prefix: its own tagged fields and, as the
     * binder does, those of the structs it embeds by value, as if they were declared in it.
     */
    fun boundFields(typeSpec: GoTypeSpec, seen: MutableSet<GoTypeSpec> = mutableSetOf()): List<GoFieldDeclaration> {
        if (!seen.add(typeSpec)) return emptyList()
        val struct = PsiTreeUtil.findChildOfType(typeSpec, GoStructType::class.java) ?: return emptyList()

        return struct.fieldDeclarationList.flatMap { field ->
            if (field.tag != null) listOf(field) else embeddedStruct(field)?.let { boundFields(it, seen) }.orEmpty()
        }
    }

    /** Returns the struct type embedded by value in the field, or null (interfaces and pointers are not bound). */
    private fun embeddedStruct(field: GoFieldDeclaration): GoTypeSpec? {
        val embedded = field.anonymousFieldDefinition ?: return null
        if (field.text.trimStart().startsWith("*")) return null

        val spec = embedded.typeReferenceExpression?.reference?.resolve() as? GoTypeSpec ?: return null
        return spec.takeIf { it.specType.type is GoStructType }
    }

    fun split(key: String): List<String> = key.split('.').filter { it.isNotEmpty() }

    /** Finds the key in the YAML files under a `resources` directory. Nothing is returned when it is not there. */
    fun findYamlKeys(context: PsiElement, keys: List<String>): List<PsiElement> {
        val project = context.project
        val psiManager = PsiManager.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)

        val files = FileTypeIndex.getFiles(YAMLFileType.YML, scope)
            .filter { "/resources/" in it.path }
            .mapNotNull { psiManager.findFile(it) as? YAMLFile }

        return files.mapNotNull { YAMLUtil.getQualifiedKeyInFile(it, keys) }
    }

    /** Finds the Go struct or field that binds the given YAML key, e.g. `server.port`. */
    fun findGoTargets(project: Project, keys: List<String>): List<PsiElement> {
        val targets = mutableListOf<PsiElement>()

        // The properties are usually declared by libraries (Procyon's own `server.port`), not by the project.
        val files = GoFiles.withWord(project, "Prefix", GlobalSearchScope.allScope(project), "property:\"")
        for (file in files) {
            for (typeSpec in PsiTreeUtil.findChildrenOfType(file, GoTypeSpec::class.java)) {
                val prefix = prefixOf(typeSpec)?.let(::split) ?: continue
                if (keys.size < prefix.size || keys.subList(0, prefix.size) != prefix) continue

                val rest = keys.drop(prefix.size)
                if (rest.isEmpty()) {
                    // The key is the prefix itself, so it goes to the string that `Prefix()` returns.
                    targets += prefixLiteral(typeSpec) ?: typeSpec
                    continue
                }

                for (field in boundFields(typeSpec)) {
                    val tag = field.tag ?: continue
                    if (nameOf(tag)?.let(::split) == rest) {
                        targets += field.fieldDefinitionList.firstOrNull() ?: field
                    }
                }
            }
        }

        return targets
    }
}
