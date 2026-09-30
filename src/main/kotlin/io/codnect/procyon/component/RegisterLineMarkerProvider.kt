package io.codnect.procyon.component

import com.goide.psi.GoCallExpr
import com.goide.psi.GoFunctionDeclaration
import com.goide.psi.GoReferenceExpression
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import io.codnect.procyon.ProcyonBundle
import io.codnect.procyon.ProcyonIcons
import javax.swing.Icon

/**
 * Puts an icon on a valid `component.Register(...)` that navigates to the component it registers, so
 * the registration and the component can be reached from each other. It is not shown when the call
 * is wrong, e.g. when it registers a name that is already taken.
 */
class RegisterLineMarkerProvider : LineMarkerProviderDescriptor() {

    override fun getName(): String = ProcyonBundle.message("gutter.register.name")

    override fun getIcon(): Icon = ProcyonIcons.Component

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    // The registry needs the index, so this is done in the slow pass.
    override fun collectSlowLineMarkers(
        elements: List<PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>,
    ) {
        for (element in elements) {
            ProgressManager.checkCanceled()

            val call = registrationOf(element) ?: continue
            val registry = ComponentFinder.registry(element.project)
            val component = ComponentFinder.componentAt(registry, call) ?: continue
            if (ComponentFinder.isDuplicate(registry, component)) continue

            // The icon is the one the registered type itself has: controller, properties, or component.
            val spec = constructorOf(call)?.let(ComponentFinder::returnedGoType)?.let(ComponentFinder::resolve)
            val role = Roles.roleName(spec)

            result.add(
                LineMarkerInfo(
                    element,
                    element.textRange,
                    Roles.registeredIcon(spec),
                    { ProcyonBundle.message("gutter.register.navigate", role, component.typeName) },
                    { _, source -> registrationOf(source)?.let(::navigate) },
                    GutterIconRenderer.Alignment.LEFT,
                    { ProcyonBundle.message("gutter.register.name") },
                )
            )
        }
    }

    /** Returns the `component.Register(...)` call when [element] is its `Register` name. */
    private fun registrationOf(element: PsiElement): GoCallExpr? {
        val callee = element.parent as? GoReferenceExpression ?: return null
        if (callee.identifier != element) return null
        val call = callee.parent as? GoCallExpr ?: return null
        return call.takeIf { it.expression == callee }
    }

    private fun constructorOf(call: GoCallExpr): GoFunctionDeclaration? =
        (call.argumentList.expressionList.firstOrNull() as? GoReferenceExpression)?.reference?.resolve() as? GoFunctionDeclaration

    /** Goes to the type the constructor returns, or to the constructor when the type cannot be found. */
    private fun navigate(call: GoCallExpr) {
        val constructor = constructorOf(call) ?: return
        val type = ComponentFinder.returnedGoType(constructor)?.let(ComponentFinder::resolve)

        val target = type ?: constructor
        val file = target.containingFile.originalFile.virtualFile ?: return
        OpenFileDescriptor(call.project, file, target.textOffset).navigate(true)
    }
}
