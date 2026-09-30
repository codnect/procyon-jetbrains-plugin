package io.codnect.procyon.component

import com.goide.psi.GoFunctionDeclaration
import com.goide.psi.GoSpecType
import com.goide.psi.GoTypeSpec
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import io.codnect.procyon.ProcyonBundle
import io.codnect.procyon.ProcyonIcons
import io.codnect.procyon.config.ProcyonProperties
import io.codnect.procyon.http.EndpointMappers
import javax.swing.Icon

/**
 * Puts an icon in the gutter of every function passed to `component.Register` and of the type it
 * returns. Both navigate to the registration.
 */
class ComponentLineMarkerProvider : LineMarkerProviderDescriptor() {

    override fun getName(): String = ProcyonBundle.message("gutter.component.name")

    override fun getIcon(): Icon = ProcyonIcons.Component

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    // Resolving the registered functions needs the index, so this is done in the slow pass.
    override fun collectSlowLineMarkers(
        elements: List<PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>,
    ) {
        for (element in elements) {
            ProgressManager.checkCanceled()

            val parent = element.parent
            val info = when {
                // The name leaf of a type sits under a GoSpecType, which is under the GoTypeSpec.
                parent is GoSpecType -> componentMarker(element, parent.parent as? GoTypeSpec)
                parent is GoFunctionDeclaration && parent.identifier == element -> constructorMarker(element, parent)
                else -> null
            }
            info?.let(result::add)
        }
    }

    private fun componentMarker(element: PsiElement, typeSpec: GoTypeSpec?): LineMarkerInfo<*>? {
        if (typeSpec == null || typeSpec.identifier != element) return null
        // Controllers and configuration properties have their own marker.
        if (EndpointMappers.isEndpointMapper(typeSpec) || ProcyonProperties.prefixOf(typeSpec) != null) return null
        val registration = ComponentFinder.registry(element.project).components[ComponentFinder.keyOf(typeSpec)]
            ?: return null
        return marker(element, ProcyonIcons.Component, "gutter.component.registered", registration)
    }

    private fun constructorMarker(element: PsiElement, function: GoFunctionDeclaration): LineMarkerInfo<*>? {
        val registration = ComponentFinder.registry(element.project).constructors[ComponentFinder.keyOf(function)]
            ?: return null
        return marker(element, ProcyonIcons.ComponentConstructor, "gutter.constructor.registered", registration)
    }

    private fun marker(element: PsiElement, icon: Icon, tooltip: String, registration: ComponentFinder.Registration) = LineMarkerInfo(
        element,
        element.textRange,
        icon,
        { ProcyonBundle.message(tooltip, registration.file.name) },
        { _, source -> OpenFileDescriptor(source.project, registration.file, registration.offset).navigate(true) },
        GutterIconRenderer.Alignment.LEFT,
        { ProcyonBundle.message("gutter.component.name") },
    )
}
