package io.codnect.procyon.component

import com.goide.psi.GoCallExpr
import com.goide.psi.GoFunctionDeclaration
import com.goide.psi.GoParType
import com.goide.psi.GoPointerType
import com.goide.psi.GoReferenceExpression
import com.goide.psi.GoType
import com.goide.psi.GoTypeSpec
import com.goide.psi.impl.GoTypeUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import io.codnect.procyon.GoFiles
import io.codnect.procyon.Procyon

/** Finds the types that are components, i.e. returned by a function passed to `component.Register`. */
internal object ComponentFinder {

    private val NAME = Regex("""WithName\(\s*"([^"]*)"""")
    private val QUALIFIER_AT = Regex("""WithQualifierAt\(\s*(\d+)\s*(?:,\s*"([^"]*)")?""")
    private val QUALIFIER_FOR = Regex("""WithQualifierFor\[\s*\*?(?:\w+\.)?(\w+)\s*](?:\(\s*"([^"]*)")?""")

    private val LIBRARIES_KEY = Key.create<CachedValue<Registry>>("procyon.library.components")

    /** Identifies a type by file and name, because the same type can come back as different PSI instances. */
    fun keyOf(typeSpec: GoTypeSpec): String =
        "${typeSpec.containingFile.originalFile.virtualFile?.path}#${typeSpec.name}"

    /** Identifies a function by file and name, like [keyOf]. */
    fun keyOf(function: GoFunctionDeclaration): String =
        "${function.containingFile.originalFile.virtualFile?.path}#${function.name}"

    /**
     * The registered components: [components] maps every component type and [constructors] every
     * registered function, each by key, to the `component.Register(...)` call that registers it.
     */
    class Registry(
        val components: Map<String, Registration>,
        val constructors: Map<String, Registration>,
        val all: List<Component> = emptyList(),
    )

    /**
     * A registered component, as text so that nothing PSI is kept: the constructor and the type it returns
     * (by key), that type's method names, and the qualifiers given to `component.Register` with their names.
     */
    class Component(
        val constructor: String,
        val typeKey: String?,
        val typeName: String,
        val methods: Set<String>,
        val qualifiedIndexes: Map<Int, String?>,
        val qualifiedTypes: Map<String, String?>,
        val name: String,
        val registration: Registration,
    )

    /** Where a component is registered. It holds no PSI, so it cannot outlive an edit of the file. */
    class Registration(val file: VirtualFile, val offset: Int)

    /** Returns the component that [call], a `component.Register(...)`, registers, or null when it registers none. */
    fun componentAt(registry: Registry, call: GoCallExpr): Component? {
        val file = call.containingFile.originalFile.virtualFile ?: return null
        return registry.all.firstOrNull { it.registration.file == file && it.registration.offset == call.textOffset }
    }

    /** Returns whether an earlier registration already uses the component's name, so Procyon would panic. */
    fun isDuplicate(registry: Registry, component: Component): Boolean =
        registry.all.first { it.name == component.name } !== component

    /** The components registered by the project's own code and by the libraries it uses. */
    fun registry(project: Project): Registry {
        val own = projectRegistry(project)
        val libraries = libraryRegistry(project)
        return Registry(
            own.components + libraries.components,
            own.constructors + libraries.constructors,
            libraries.all + FrameworkComponents.of(project) + own.all,
        )
    }

    private fun projectRegistry(project: Project): Registry =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(
                registryOf(GoFiles.withWord(project, "Register")),
                PsiModificationTracker.MODIFICATION_COUNT,
            )
        }

    // Libraries register their components in init functions and change rarely, so they are read once
    // per change of the project roots. Only files that import the component package are parsed.
    private fun libraryRegistry(project: Project): Registry =
        CachedValuesManager.getManager(project).getCachedValue(project, LIBRARIES_KEY, {
            CachedValueProvider.Result.create(
                registryOf(libraryFiles(project)),
                ProjectRootModificationTracker.getInstance(project),
            )
        }, false)

    private fun libraryFiles(project: Project): List<PsiFile> {
        val index = ProjectFileIndex.getInstance(project)
        return GoFiles.withWord(project, "Register", GlobalSearchScope.allScope(project), Procyon.COMPONENT)
            .filter { file -> file.virtualFile?.let { !index.isInContent(it) } ?: false }
    }

    /** Returns the functions passed to `component.Register` in the project and in its libraries. */
    fun constructors(project: Project): List<GoFunctionDeclaration> =
        (GoFiles.withWord(project, "Register") + libraryFiles(project))
            .flatMap { registrations(it) }
            .map { it.second }
            .distinctBy(::keyOf)

    private fun registryOf(files: List<PsiFile>): Registry {
        val components = linkedMapOf<String, Registration>()
        val constructors = linkedMapOf<String, Registration>()
        val all = mutableListOf<Component>()

        for ((call, constructor) in files.flatMap { registrations(it) }) {
            val registration = Registration(
                call.containingFile.originalFile.virtualFile ?: continue,
                call.textOffset,
            )
            constructors.putIfAbsent(keyOf(constructor), registration)
            val type = returnedType(constructor)
            type?.let { components.putIfAbsent(keyOf(it), registration) }
            all += componentOf(call, constructor, type, registration)
        }

        return Registry(components, constructors, all)
    }

    private fun componentOf(
        call: GoCallExpr,
        constructor: GoFunctionDeclaration,
        type: GoTypeSpec?,
        registration: Registration,
    ): Component {
        val returned = returnedGoType(constructor)
        val options = call.argumentList.expressionList.drop(1).map { it.text }

        // Procyon names a component after the type its constructor returns (`*pkg.UserService` becomes
        // `userService`), unless `WithName` gives another name.
        val typeName = returned?.text?.removePrefix("*")?.substringAfterLast('.').orEmpty()
        val name = options.firstNotNullOfOrNull { NAME.find(it)?.groupValues?.get(1) }
            ?: typeName.replaceFirstChar { it.lowercaseChar() }

        return Component(
            constructor = keyOf(constructor),
            typeKey = type?.let(::keyOf),
            typeName = type?.name ?: returned?.text.orEmpty(),
            methods = returned?.let { GoTypeUtil.getMethodSet(it, constructor).mapNotNull { m -> m.name }.toSet() }.orEmpty(),
            // The value is the qualifier's name, or null when it is not a string literal.
            qualifiedIndexes = options.mapNotNull { option ->
                QUALIFIER_AT.find(option)?.let { it.groupValues[1].toInt() to it.groups[2]?.value }
            }.toMap(),
            qualifiedTypes = options.mapNotNull { option ->
                QUALIFIER_FOR.find(option)?.let { it.groupValues[1] to it.groups[2]?.value }
            }.toMap(),
            name = name,
            registration = registration,
        )
    }

    /** Returns every `component.Register(fn)` call of the file with the function it registers. */
    private fun registrations(file: PsiFile): List<Pair<GoCallExpr, GoFunctionDeclaration>> =
        PsiTreeUtil.findChildrenOfType(file, GoCallExpr::class.java).mapNotNull { call ->
            val callee = call.expression as? GoReferenceExpression ?: return@mapNotNull null
            if (callee.identifier.text != "Register" ||
                !Procyon.isImport(file, callee.qualifier?.text, Procyon.COMPONENT)
            ) return@mapNotNull null

            val constructor = (call.argumentList.expressionList.firstOrNull() as? GoReferenceExpression)
                ?.reference?.resolve() as? GoFunctionDeclaration ?: return@mapNotNull null
            call to constructor
        }

    /** Returns the type as declared by the first result of the function, e.g. `*Foo` for `func New() *Foo`. */
    fun returnedGoType(function: GoFunctionDeclaration): GoType? {
        val result = function.signature?.result ?: return null
        return result.type ?: result.parameters?.parameterDeclarationList?.firstOrNull()?.type
    }

    /** Returns the type declared by the first result, e.g. `Foo` for `func New() (*Foo, error)`. */
    private fun returnedType(function: GoFunctionDeclaration): GoTypeSpec? = resolve(returnedGoType(function))

    /** Resolves a possibly pointer or parenthesized type to its declaration. */
    fun resolve(type: GoType?): GoTypeSpec? = when (type) {
        null -> null
        is GoPointerType -> resolve(type.type)
        is GoParType -> resolve(type.type)
        else -> type.typeReferenceExpression?.reference?.resolve() as? GoTypeSpec
    }
}
