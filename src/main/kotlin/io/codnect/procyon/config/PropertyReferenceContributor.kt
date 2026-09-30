package io.codnect.procyon.config

import com.goide.psi.GoMethodDeclaration
import com.goide.psi.GoReturnStatement
import com.goide.psi.GoStringLiteral
import com.goide.psi.GoTag
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext

/** Makes the name in `property:"name"` a reference, so Ctrl/Cmd+click goes to the YAML key. */
class PropertyReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(GoStringLiteral::class.java),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(
                    element: PsiElement,
                    context: ProcessingContext,
                ): Array<PsiReference> {
                    val literal = element as? GoStringLiteral ?: return PsiReference.EMPTY_ARRAY
                    return when (val parent = literal.parent) {
                        is GoTag -> tagReference(literal, parent)
                        is GoReturnStatement -> prefixReference(literal)
                        else -> PsiReference.EMPTY_ARRAY
                    }
                }
            },
        )
    }

    /** The name in `property:"name"` refers to the key of that field. */
    private fun tagReference(literal: GoStringLiteral, tag: GoTag): Array<PsiReference> {
        val name = ProcyonProperties.nameOf(tag) ?: return PsiReference.EMPTY_ARRAY

        val start = literal.text.indexOf("property:\"").takeIf { it >= 0 }
            ?.plus("property:\"".length) ?: return PsiReference.EMPTY_ARRAY
        if (!literal.text.startsWith(name, start)) return PsiReference.EMPTY_ARRAY

        return arrayOf(PropertyReference(literal, TextRange.from(start, name.length)) { ProcyonProperties.keysOf(tag) })
    }

    /** The text returned by `Prefix() string` refers to the key of the whole properties struct. */
    private fun prefixReference(literal: GoStringLiteral): Array<PsiReference> {
        val method = PsiTreeUtil.getParentOfType(literal, GoMethodDeclaration::class.java)
        if (method == null || !ProcyonProperties.isPrefixMethod(method)) return PsiReference.EMPTY_ARRAY

        val prefix = literal.decodedText
        if (prefix.isBlank() || literal.textLength < 2) return PsiReference.EMPTY_ARRAY

        val range = TextRange(1, literal.textLength - 1)
        return arrayOf(PropertyReference(literal, range) { ProcyonProperties.split(prefix) })
    }
}

private class PropertyReference(
    literal: GoStringLiteral,
    range: TextRange,
    private val keys: () -> List<String>?,
) : PsiPolyVariantReferenceBase<GoStringLiteral>(literal, range, true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val keys = keys() ?: return ResolveResult.EMPTY_ARRAY
        return ProcyonProperties.findYamlKeys(element, keys)
            .map { PsiElementResolveResult(it) }
            .toTypedArray()
    }
}
