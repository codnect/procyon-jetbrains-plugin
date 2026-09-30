package io.codnect.procyon.component

import com.goide.psi.GoFunctionDeclaration
import com.goide.psi.GoFunctionOrMethodDeclaration
import com.goide.psi.GoVisitor
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import io.codnect.procyon.ProcyonBundle

/**
 * Underlines a parameter of a registered component constructor when more than one registered component
 * could fill it, like Spring's "There is more than one bean of 'X' type". A qualifier given to
 * `component.Register` (`WithQualifierAt`, `WithQualifierFor`) chooses one, so it silences this.
 */
class AmbiguousInjectionInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : GoVisitor() {
            override fun visitFunctionOrMethodDeclaration(function: GoFunctionOrMethodDeclaration) {
                if (function !is GoFunctionDeclaration) return

                for (injection in Injections.of(function)) {
                    if (injection.candidates.size < 2 || injection.qualified) continue

                    val names = injection.candidates.joinToString { it.constructor.substringAfter('#') }
                    holder.registerProblem(
                        injection.type,
                        ProcyonBundle.message("ambiguous.injection", injection.spec.name.orEmpty(), names),
                    )
                }
            }
        }
}
