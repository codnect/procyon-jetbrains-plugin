package io.codnect.procyon.http

import com.goide.psi.GoCallExpr
import com.goide.psi.GoExpression
import com.goide.psi.GoFunctionOrMethodDeclaration
import com.goide.psi.GoMethodDeclaration
import com.goide.psi.GoParamDefinition
import com.goide.psi.GoParameterDeclaration
import com.goide.psi.GoPointerType
import com.goide.psi.GoReferenceExpression
import com.goide.psi.GoStringLiteral
import com.goide.psi.GoVarDefinition
import com.goide.psi.GoVarSpec
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import io.codnect.procyon.GoFiles
import io.codnect.procyon.Procyon
import io.codnect.procyon.component.ComponentFinder

/**
 * Finds the handlers that are mapped correctly: a function or method that `http.Handle` or
 * `http.HandleResult` wraps and that is passed to a `Map*` method of `http.Endpoints` or of an
 * `http.EndpointGroup`, from a `MapEndpoints` method.
 */
internal object HandlerFinder {

    /** A route that maps a handler, with the group prefixes already applied to its path. */
    class Mapping(val verb: String, val path: String, val file: VirtualFile, val offset: Int)

    private val VERBS = mapOf(
        "MapAny" to "ANY",
        "MapMethods" to "METHODS",
        "MapGet" to "GET",
        "MapPost" to "POST",
        "MapPut" to "PUT",
        "MapDelete" to "DELETE",
        "MapPatch" to "PATCH",
    )

    /** The mappings of every handler, by [keyOf]. Nothing PSI is kept. */
    fun mappings(project: Project): Map<String, List<Mapping>> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(collect(project), PsiModificationTracker.MODIFICATION_COUNT)
        }

    /**
     * Returns the function that a `Map*` call's handler argument wraps, `http.Handle(fn)` or
     * `http.HandleResult(fn)`, or null when it is anything else. It does not check the function's signature.
     */
    fun referencedHandler(argument: GoExpression): GoFunctionOrMethodDeclaration? {
        val wrapper = argument as? GoCallExpr ?: return null
        val callee = wrapper.expression as? GoReferenceExpression ?: return null
        if (callee.identifier.text != "Handle" && callee.identifier.text != "HandleResult") return null
        if (!Procyon.isImport(wrapper.containingFile, callee.qualifier?.text, Procyon.HTTP)) return null

        return (wrapper.argumentList.expressionList.singleOrNull() as? GoReferenceExpression)
            ?.reference?.resolve() as? GoFunctionOrMethodDeclaration
    }

    /** Returns the label of a handler, like `WelcomeController#hello` for a method and `hello` for a function. */
    fun labelOf(function: GoFunctionOrMethodDeclaration): String {
        val receiver = (function as? GoMethodDeclaration)?.receiverType?.text?.removePrefix("*")
        return if (receiver != null) "$receiver#${function.name}" else function.name.orEmpty()
    }

    /** Identifies a handler by file, receiver and name. */
    fun keyOf(function: GoFunctionOrMethodDeclaration): String {
        val receiver = (function as? GoMethodDeclaration)?.receiverType?.text?.removePrefix("*").orEmpty()
        return "${function.containingFile.originalFile.virtualFile?.path}#$receiver.${function.name}"
    }

    private fun collect(project: Project): Map<String, List<Mapping>> {
        val result = linkedMapOf<String, MutableList<Mapping>>()

        for (file in GoFiles.withWord(project, "MapEndpoints")) {
            for (method in PsiTreeUtil.findChildrenOfType(file, GoMethodDeclaration::class.java)) {
                if (!EndpointMappers.isMapEndpoints(method)) continue

                for (call in PsiTreeUtil.findChildrenOfType(method.block, GoCallExpr::class.java)) {
                    val (handler, mapping) = mappingOf(call) ?: continue
                    result.getOrPut(keyOf(handler)) { mutableListOf() } += mapping
                }
            }
        }

        return result
    }

    /** Returns the handler and route of a well-formed `Map*` call, or null when anything about it is off. */
    private fun mappingOf(call: GoCallExpr): Pair<GoFunctionOrMethodDeclaration, Mapping>? {
        val callee = call.expression as? GoReferenceExpression ?: return null
        val name = callee.identifier.text
        val verb = VERBS[name] ?: return null

        val receiver = callee.qualifier as? GoExpression ?: return null
        if (!isRouter(receiver)) return null

        // MapMethods takes the methods too: (path, methods, handler).
        val arguments = call.argumentList.expressionList
        if (arguments.size != (if (name == "MapMethods") 3 else 2)) return null

        val path = literalOf(arguments.first()) ?: return null
        val handler = handlerOf(arguments.last()) ?: return null
        val file = call.containingFile.originalFile.virtualFile ?: return null

        return handler to Mapping(verb, join(groupPrefix(receiver), path), file, call.textOffset)
    }

    /**
     * Returns whether the expression is Procyon's `http.Endpoints` or `*http.EndpointGroup`. It follows the
     * declaration instead of asking for the expression's type: a parameter of that type, a variable that was
     * assigned one, or a `MapGroup` call on one.
     */
    private fun isRouter(receiver: PsiElement): Boolean = when (receiver) {
        is GoCallExpr -> {
            val callee = receiver.expression as? GoReferenceExpression
            callee?.identifier?.text == "MapGroup" && (callee.qualifier?.let(::isRouter) ?: false)
        }

        is GoReferenceExpression -> when (val declaration = receiver.reference.resolve()) {
            is GoParamDefinition -> isRouterType(declaration)
            is GoVarDefinition -> {
                val spec = declaration.parent as? GoVarSpec
                val value = spec?.rightExpressionsList?.getOrNull(spec.definitionList.indexOf(declaration))
                value?.let(::isRouter) ?: false
            }

            else -> false
        }

        else -> false
    }

    private fun isRouterType(parameter: GoParamDefinition): Boolean {
        var type = (parameter.parent as? GoParameterDeclaration)?.type ?: return false
        if (type is GoPointerType) type = type.type ?: return false

        val file = parameter.containingFile
        return Procyon.isType(file, type, Procyon.HTTP, "Endpoints") ||
            Procyon.isType(file, type, Procyon.HTTP, "EndpointGroup")
    }

    /** Returns the function passed to `http.Handle(fn)` or `http.HandleResult(fn)` when its signature fits. */
    private fun handlerOf(argument: GoExpression): GoFunctionOrMethodDeclaration? {
        val wrapper = argument as? GoCallExpr ?: return null
        val callee = wrapper.expression as? GoReferenceExpression
            ?: return null
        val wrapperName = callee.identifier.text
        if (wrapperName != "Handle" && wrapperName != "HandleResult") return null
        if (!Procyon.isImport(wrapper.containingFile, callee.qualifier?.text, Procyon.HTTP)) {
            return null
        }

        val target = wrapper.argumentList.expressionList.singleOrNull() as? GoReferenceExpression
            ?: return null
        val function = target.reference.resolve() as? GoFunctionOrMethodDeclaration
            ?: return null

        val signature = function.signature ?: return null
        if (signature.parameters.parameterCount != 1) return null

        // Handle takes func(C) error and HandleResult takes func(C) (R, error).
        val result = signature.result ?: return null
        val results = result.parameters?.parameterDeclarationList?.map { it.type?.text }
            ?: listOf(result.type?.text)
        val fits = if (wrapperName == "Handle") results == listOf("error") else results.size == 2 && results.last() == "error"
        return function.takeIf { fits }
    }

    /** Returns the prefix of the group the receiver belongs to, following `MapGroup` calls and variables. */
    private fun groupPrefix(receiver: PsiElement): String = when (receiver) {
        is GoCallExpr -> {
            val callee = receiver.expression as? GoReferenceExpression
            if (callee?.identifier?.text == "MapGroup") {
                val outer = (callee.qualifier as? GoExpression)?.let(::groupPrefix).orEmpty()
                join(outer, receiver.argumentList.expressionList.firstOrNull()?.let(::literalOf).orEmpty())
            } else {
                ""
            }
        }

        is GoReferenceExpression -> {
            val definition = receiver.reference.resolve() as? GoVarDefinition
            val spec = definition?.parent as? GoVarSpec
            val value = spec?.rightExpressionsList?.getOrNull(spec.definitionList.indexOf(definition))
            value?.let(::groupPrefix).orEmpty()
        }

        else -> ""
    }

    private fun literalOf(expression: GoExpression): String? = (expression as? GoStringLiteral)?.decodedText

    private fun join(prefix: String, path: String): String = "/$prefix/$path".replace(Regex("/+"), "/")
}
