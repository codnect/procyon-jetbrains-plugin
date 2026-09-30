package io.codnect.procyon.component

import com.goide.psi.GoCallExpr
import com.goide.psi.GoInterfaceType
import com.goide.psi.GoReferenceExpression
import com.goide.psi.GoTypeOwner
import com.goide.psi.GoTypeReferenceExpression
import com.goide.psi.GoTypeSpec
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import io.codnect.procyon.GoFiles
import io.codnect.procyon.Procyon

/**
 * The types Procyon puts into the container itself, not through `component.Register`: what its own code
 * passes to `RegisterDependency(reflect.TypeFor[T](), ...)` and `RegisterSingleton(name, value)`, such as
 * `runtime.Environment`, `runtime.Context` and `component.Container`. Constructors may ask for these.
 * They are read from Procyon's sources, so they follow the version the project uses.
 */
internal object FrameworkComponents {

    private val KEY = Key.create<CachedValue<List<ComponentFinder.Component>>>("procyon.framework.components")

    fun of(project: Project): List<ComponentFinder.Component> =
        CachedValuesManager.getManager(project).getCachedValue(project, KEY, {
            CachedValueProvider.Result.create(find(project), ProjectRootModificationTracker.getInstance(project))
        }, false)

    private fun find(project: Project): List<ComponentFinder.Component> {
        val scope = GlobalSearchScope.allScope(project)
        val index = ProjectFileIndex.getInstance(project)

        val files = (GoFiles.withWord(project, "RegisterDependency", scope, Procyon.MODULE) +
            GoFiles.withWord(project, "RegisterSingleton", scope, Procyon.MODULE))
            .distinct()
            .filter { file -> !file.name.endsWith("_test.go") && file.virtualFile?.let { !index.isInContent(it) } == true }

        return files.flatMap(::providedBy).distinctBy { it.typeKey }
    }

    private fun providedBy(file: PsiFile): List<ComponentFinder.Component> =
        PsiTreeUtil.findChildrenOfType(file, GoCallExpr::class.java).mapNotNull { call ->
            val callee = call.expression as? GoReferenceExpression ?: return@mapNotNull null
            val arguments = call.argumentList.expressionList

            val spec = when (callee.identifier.text) {
                // RegisterDependency(reflect.TypeFor[T](), value): the type argument is the type.
                "RegisterDependency" -> arguments.firstOrNull()
                    ?.let { PsiTreeUtil.findChildOfType(it, GoTypeReferenceExpression::class.java) }
                    ?.reference?.resolve() as? GoTypeSpec

                // RegisterSingleton(name, value): the value's declared type is the type.
                "RegisterSingleton" -> (arguments.getOrNull(1) as? GoReferenceExpression)
                    ?.reference?.resolve()
                    ?.let { (it as? GoTypeOwner)?.getGoType(null) }
                    ?.let(ComponentFinder::resolve)

                else -> null
            } ?: return@mapNotNull null

            componentOf(spec, call)
        }

    private fun componentOf(spec: GoTypeSpec, call: GoCallExpr): ComponentFinder.Component? {
        val file = call.containingFile.originalFile.virtualFile ?: return null
        val methods = (spec.specType.type as? GoInterfaceType)?.getAllMethods(spec)?.mapNotNull { it.name }
            ?: spec.methods.mapNotNull { it.name }

        return ComponentFinder.Component(
            constructor = "framework:${ComponentFinder.keyOf(spec)}",
            typeKey = ComponentFinder.keyOf(spec),
            typeName = spec.name.orEmpty(),
            methods = methods.toSet(),
            qualifiedIndexes = emptyMap(),
            qualifiedTypes = emptyMap(),
            // Not a name anyone can write, so it never clashes with a registered component.
            name = "framework:${spec.name}",
            registration = ComponentFinder.Registration(file, call.textOffset),
        )
    }
}
