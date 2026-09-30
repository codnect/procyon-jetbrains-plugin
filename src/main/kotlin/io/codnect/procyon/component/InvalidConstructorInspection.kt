package io.codnect.procyon.component

import com.goide.psi.GoCallExpr
import com.goide.psi.GoFunctionDeclaration
import com.goide.psi.GoReferenceExpression
import com.goide.psi.GoVisitor
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import io.codnect.procyon.Procyon
import io.codnect.procyon.ProcyonBundle

/**
 * Reports a function passed to `component.Register` that Procyon rejects as a constructor: it must return
 * exactly one result, and that result must be a struct, a pointer to a struct or an interface.
 */
class InvalidConstructorInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : GoVisitor() {
            override fun visitCallExpr(call: GoCallExpr) {
                val callee = call.expression as? GoReferenceExpression ?: return
                if (callee.identifier.text != "Register" ||
                    !Procyon.isImport(call.containingFile, callee.qualifier?.text, Procyon.COMPONENT)
                ) return

                val argument = call.argumentList.expressionList.firstOrNull() as? GoReferenceExpression ?: return
                val constructor = argument.reference.resolve() as? GoFunctionDeclaration ?: return

                val message = when (val violation = ConstructorRules.violation(constructor)) {
                    is ConstructorRules.Violation.Results ->
                        ProcyonBundle.message("invalid.constructor.results", constructor.name.orEmpty(), violation.count)
                    is ConstructorRules.Violation.ResultType ->
                        ProcyonBundle.message("invalid.constructor.type", violation.type)
                    null -> return
                }
                holder.registerProblem(argument, message)
            }
        }
}
