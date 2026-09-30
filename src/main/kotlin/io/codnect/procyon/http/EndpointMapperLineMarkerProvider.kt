package io.codnect.procyon.http

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
import io.codnect.procyon.component.ComponentFinder
import javax.swing.Icon

/**
 * Puts a controller icon in the gutter of every Go type that implements Procyon's
 * `EndpointMapper`, i.e. has a `MapEndpoints(endpoints Endpoints)` method.
 */
class EndpointMapperLineMarkerProvider : LineMarkerProviderDescriptor() {

    override fun getName(): String = ProcyonBundle.message("gutter.controller.name")

    override fun getIcon(): Icon = ProcyonIcons.Controller

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    // Whether the controller is registered needs the index, so this is done in the slow pass.
    override fun collectSlowLineMarkers(
        elements: List<PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>,
    ) {
        for (element in elements) {
            ProgressManager.checkCanceled()

            // The name leaf sits under a GoSpecType, which is itself under the GoTypeSpec.
            if (element.parent !is GoSpecType) continue
            val typeSpec = element.parent.parent as? GoTypeSpec ?: continue
            if (typeSpec.identifier != element || !EndpointMappers.isEndpointMapper(typeSpec)) continue

            val registration = ComponentFinder.registry(element.project).components[ComponentFinder.keyOf(typeSpec)]
            val tooltip = registration?.let { ProcyonBundle.message("gutter.controller.registered", it.file.name) }
                ?: ProcyonBundle.message("gutter.controller.not.registered")

            result.add(
                LineMarkerInfo(
                    element,
                    element.textRange,
                    if (registration != null) ProcyonIcons.ControllerComponent else ProcyonIcons.Controller,
                    { tooltip },
                    registration?.let { r ->
                        { _, source -> OpenFileDescriptor(source.project, r.file, r.offset).navigate(true) }
                    },
                    GutterIconRenderer.Alignment.LEFT,
                    { ProcyonBundle.message("gutter.controller.name") },
                )
            )
        }
    }
}
