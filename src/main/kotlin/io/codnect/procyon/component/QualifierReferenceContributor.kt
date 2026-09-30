package io.codnect.procyon.component

import com.goide.psi.GoCallExpr
import com.goide.psi.GoStringLiteral
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import io.codnect.procyon.Procyon

/**
 * Makes the name in `component.WithQualifierAt(0, "name")` and `component.WithQualifierFor[T]("name")` a
 * reference to the component with that name, so Ctrl/Cmd+click goes to its `component.Register`.
 */
class QualifierReferenceContributor : PsiReferenceContributor() {

    private val qualifier = Regex("""^(\w+)\.WithQualifier(?:At|For)\b""")

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(GoStringLiteral::class.java),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                    val literal = element as? GoStringLiteral ?: return PsiReference.EMPTY_ARRAY
                    if (literal.textLength < 3 || !isQualifierName(literal)) return PsiReference.EMPTY_ARRAY

                    val range = TextRange(1, literal.textLength - 1)
                    return arrayOf(ComponentNameReference(literal, range))
                }
            },
        )
    }

    /** Returns whether the literal is the name given to a qualifier option of `component.Register`. */
    private fun isQualifierName(literal: GoStringLiteral): Boolean {
        val call = literal.parent?.parent as? GoCallExpr ?: return false
        if (call.argumentList.expressionList.lastOrNull() != literal) return false

        val qualifier = qualifier.find(call.expression.text)?.groupValues?.get(1) ?: return false
        return Procyon.isImport(literal.containingFile, qualifier, Procyon.COMPONENT)
    }
}

private class ComponentNameReference(literal: GoStringLiteral, range: TextRange) :
    PsiPolyVariantReferenceBase<GoStringLiteral>(literal, range, true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val name = element.decodedText
        val psiManager = PsiManager.getInstance(element.project)

        return ComponentFinder.registry(element.project).all
            .filter { it.name == name }
            .mapNotNull { component ->
                val file = psiManager.findFile(component.registration.file) ?: return@mapNotNull null
                val leaf = file.findElementAt(component.registration.offset) ?: return@mapNotNull null
                PsiTreeUtil.getParentOfType(leaf, GoCallExpr::class.java, false)
            }
            .map { PsiElementResolveResult(it) }
            .toTypedArray()
    }
}
