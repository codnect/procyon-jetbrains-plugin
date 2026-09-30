package io.codnect.procyon.component

import com.goide.psi.GoFunctionDeclaration
import com.goide.psi.GoInterfaceType
import com.goide.psi.GoType
import com.goide.psi.GoTypeSpec
import com.intellij.openapi.roots.ProjectFileIndex

/** One parameter of a registered constructor and the registered components that could fill it. */
internal class Injection(
    val type: GoType,
    val spec: GoTypeSpec,
    val candidates: List<ComponentFinder.Component>,
    val qualified: Boolean,
    /** The name a qualifier gives, or null when there is none or it is not a string literal. */
    val qualifier: String?,
)

internal object Injections {

    /**
     * Returns the injections of a registered constructor, or nothing when it is not registered. A
     * component fills a parameter when it has the same type or, for an interface, all of its methods.
     */
    fun of(function: GoFunctionDeclaration): List<Injection> {
        val registry = ComponentFinder.registry(function.project)
        val self = ComponentFinder.keyOf(function)
        if (self !in registry.constructors) return emptyList()

        val components = registry.all.distinctBy { it.constructor }
        val own = components.firstOrNull { it.constructor == self }

        val injections = mutableListOf<Injection>()
        var index = 0

        for (parameter in function.signature?.parameters?.parameterDeclarationList.orEmpty()) {
            val position = index
            index += maxOf(1, parameter.paramDefinitionList.size)

            // A variadic parameter takes any number of components, including none.
            if (parameter.isVariadic) continue

            val type = parameter.type ?: continue
            val spec = ComponentFinder.resolve(type) ?: continue
            if (isStandardLibrary(spec)) continue

            val methods = (spec.specType.type as? GoInterfaceType)?.getAllMethods(function)?.mapNotNull { it.name }
            if (methods != null && methods.isEmpty()) continue

            val key = ComponentFinder.keyOf(spec)
            val candidates = components.filter { component ->
                component.constructor != self &&
                    (component.typeKey == key || (methods != null && component.methods.containsAll(methods)))
            }

            val byIndex = own?.qualifiedIndexes?.containsKey(position) == true
            val byType = own?.qualifiedTypes?.containsKey(spec.name) == true
            val qualifier = when {
                byIndex -> own.qualifiedIndexes[position]
                byType -> own.qualifiedTypes[spec.name]
                else -> null
            }
            injections += Injection(type, spec, candidates, byIndex || byType, qualifier)
        }

        return injections
    }

    // Standard library types are not registered by anyone, so they are not reported. The project's own
    // types are never standard library, whatever its module is called (it may have no dot, like `app`).
    private fun isStandardLibrary(spec: GoTypeSpec): Boolean {
        val file = spec.containingFile.originalFile.virtualFile ?: return false
        if (ProjectFileIndex.getInstance(spec.project).isInContent(file)) return false

        val path = spec.containingFile.getImportPath(false) ?: return false
        return '.' !in path.substringBefore('/')
    }
}
