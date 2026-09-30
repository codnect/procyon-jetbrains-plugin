package io.codnect.procyon.component

import com.goide.psi.GoCallExpr
import com.goide.psi.GoReferenceExpression
import com.goide.psi.GoVisitor
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import io.codnect.procyon.Procyon
import io.codnect.procyon.ProcyonBundle

/**
 * Reports a `component.Register` whose component name is already taken, which makes Procyon panic with
 * "duplicate component name" at startup. A component is named after the type its constructor returns,
 * so registering the same constructor twice, or two constructors that return the same type name, clashes
 * unless `WithName` gives them different names.
 */
class DuplicateComponentInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : GoVisitor() {
            override fun visitCallExpr(call: GoCallExpr) {
                val callee = call.expression as? GoReferenceExpression ?: return
                if (callee.identifier.text != "Register" ||
                    !Procyon.isImport(call.containingFile, callee.qualifier?.text, Procyon.COMPONENT)
                ) return

                val registry = ComponentFinder.registry(call.project)
                val component = ComponentFinder.componentAt(registry, call) ?: return
                if (!ComponentFinder.isDuplicate(registry, component)) return
                val first = registry.all.first { it.name == component.name }

                holder.registerProblem(
                    call.argumentList.expressionList.firstOrNull() ?: call,
                    ProcyonBundle.message(
                        "duplicate.component",
                        component.name,
                        first.constructor.substringAfter('#'),
                        first.registration.file.name,
                    ),
                )
            }
        }
}
