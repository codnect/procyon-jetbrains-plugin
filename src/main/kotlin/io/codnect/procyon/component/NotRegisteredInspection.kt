package io.codnect.procyon.component

import com.goide.psi.GoTypeSpec
import com.goide.psi.GoVisitor
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import io.codnect.procyon.ProcyonBundle
import io.codnect.procyon.config.ProcyonProperties
import io.codnect.procyon.http.EndpointMappers

/**
 * Warns about a controller or properties struct that no `component.Register` returns, since Procyon
 * only uses the components that are registered.
 */
class NotRegisteredInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : GoVisitor() {
            override fun visitTypeSpec(typeSpec: GoTypeSpec) {
                val role = when {
                    EndpointMappers.isEndpointMapper(typeSpec) -> ProcyonBundle.message("role.controller")
                    ProcyonProperties.prefixOf(typeSpec) != null -> ProcyonBundle.message("role.properties")
                    else -> return
                }
                val name = typeSpec.identifier

                val registry = ComponentFinder.registry(typeSpec.project)
                if (ComponentFinder.keyOf(typeSpec) in registry.components) return

                holder.registerProblem(
                    name,
                    ProcyonBundle.message("not.registered", typeSpec.name.orEmpty(), role),
                )
            }
        }
}
