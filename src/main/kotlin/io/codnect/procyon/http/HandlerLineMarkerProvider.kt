package io.codnect.procyon.http

import com.goide.psi.GoFunctionOrMethodDeclaration
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import io.codnect.procyon.ProcyonBundle
import io.codnect.procyon.ProcyonIcons
import javax.swing.Icon

/**
 * Puts a handler icon on a function or method that is mapped correctly by a `Map*` call of
 * `http.Endpoints` or of an endpoint group. It navigates to that mapping.
 */
class HandlerLineMarkerProvider : LineMarkerProviderDescriptor() {

    override fun getName(): String = ProcyonBundle.message("gutter.handler.name")

    override fun getIcon(): Icon = ProcyonIcons.Handler

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    // Finding the mappings needs the index, so this is done in the slow pass.
    override fun collectSlowLineMarkers(
        elements: List<PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>,
    ) {
        for (element in elements) {
            ProgressManager.checkCanceled()

            val function = element.parent as? GoFunctionOrMethodDeclaration ?: continue
            if (function.identifier != element) continue

            val mappings = HandlerFinder.mappings(element.project)[HandlerFinder.keyOf(function)] ?: continue
            val routes = mappings.joinToString { "${it.verb} ${it.path}" }
            val first = mappings.first()

            result.add(
                LineMarkerInfo(
                    element,
                    element.textRange,
                    ProcyonIcons.Handler,
                    { ProcyonBundle.message("gutter.handler", routes) },
                    { _, source -> OpenFileDescriptor(source.project, first.file, first.offset).navigate(true) },
                    GutterIconRenderer.Alignment.LEFT,
                    { ProcyonBundle.message("gutter.handler.name") },
                )
            )
        }
    }
}
