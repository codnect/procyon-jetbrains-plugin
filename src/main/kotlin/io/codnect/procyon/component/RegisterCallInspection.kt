package io.codnect.procyon.component

import com.goide.psi.GoCallExpr
import com.goide.psi.GoReferenceExpression
import com.goide.psi.GoVisitor
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementVisitor
import io.codnect.procyon.Procyon
import io.codnect.procyon.ProcyonBundle

/**
 * Warns about `component.Register(NewFoo())`, which registers what the constructor returns instead of
 * the constructor. The quick fix removes the call.
 */
class RegisterCallInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : GoVisitor() {
            override fun visitCallExpr(call: GoCallExpr) {
                val callee = call.expression as? GoReferenceExpression ?: return
                if (callee.identifier.text != "Register" ||
                    !Procyon.isImport(call.containingFile, callee.qualifier?.text, Procyon.COMPONENT)
                ) return

                val argument = call.argumentList.expressionList.firstOrNull() as? GoCallExpr ?: return
                holder.registerProblem(
                    argument,
                    ProcyonBundle.message("register.call"),
                    RemoveCallFix,
                )
            }
        }

    private object RemoveCallFix : LocalQuickFix {
        override fun getFamilyName(): String = ProcyonBundle.message("register.call.fix")

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val call = descriptor.psiElement as? GoCallExpr ?: return
            val function = call.expression
            call.replace(function.copy())
        }
    }
}
