package io.codnect.procyon.component

import com.goide.psi.GoFunctionDeclaration
import com.goide.psi.GoFunctionOrMethodDeclaration
import com.goide.psi.GoVisitor
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import io.codnect.procyon.ProcyonBundle

/**
 * Underlines a parameter of a registered component constructor when no component of that type is
 * registered, like Spring's "Could not autowire".
 */
class CouldNotInjectInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : GoVisitor() {
            override fun visitFunctionOrMethodDeclaration(function: GoFunctionOrMethodDeclaration) {
                if (function !is GoFunctionDeclaration) return

                for (injection in Injections.of(function)) {
                    val name = injection.spec.name.orEmpty()
                    val qualifier = injection.qualifier

                    when {
                        injection.candidates.isEmpty() -> holder.registerProblem(
                            injection.type,
                            ProcyonBundle.message("could.not.inject.none", name),
                        )

                        // A qualifier picks a component by name, so a name that nobody has fails at startup.
                        qualifier != null && injection.candidates.none { it.name == qualifier } -> holder.registerProblem(
                            injection.type,
                            ProcyonBundle.message("could.not.inject.qualifier", name, qualifier),
                        )
                    }
                }
            }
        }
}
