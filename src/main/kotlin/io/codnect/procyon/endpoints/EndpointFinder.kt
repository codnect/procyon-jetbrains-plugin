package io.codnect.procyon.endpoints

import com.goide.psi.GoCallExpr
import com.goide.psi.GoMethodDeclaration
import com.goide.psi.GoReferenceExpression
import com.goide.psi.GoStringLiteral
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.PsiTreeUtil
import io.codnect.procyon.GoFiles
import io.codnect.procyon.http.EndpointMappers
import io.codnect.procyon.http.HandlerFinder

data class Endpoint(
    val verb: String,
    val path: String,
    val controller: String,
    val file: VirtualFile,
    val offset: Int,
)

/** Finds the endpoints mapped in every `MapEndpoints(endpoints Endpoints)` method. */
internal object EndpointFinder {

    private val VERBS = mapOf(
        "MapGet" to "GET",
        "MapPost" to "POST",
        "MapPut" to "PUT",
        "MapDelete" to "DELETE",
        "MapPatch" to "PATCH",
        "MapHead" to "HEAD",
        "MapOptions" to "OPTIONS",
    )

    fun find(project: Project): List<Endpoint> {
        return GoFiles.withWord(project, "MapEndpoints")
            .flatMap { PsiTreeUtil.findChildrenOfType(it, GoMethodDeclaration::class.java) }
            .filter(EndpointMappers::isMapEndpoints)
            .flatMap { method ->
                val controller = method.receiverType?.text?.removePrefix("*").orEmpty()
                PsiTreeUtil.findChildrenOfType(method.block, GoCallExpr::class.java).mapNotNull { call ->
                    val verb = (call.expression as? GoReferenceExpression)?.identifier?.text
                        ?.let(VERBS::get) ?: return@mapNotNull null
                    val path = (call.argumentList.expressionList.firstOrNull() as? GoStringLiteral)
                        ?.decodedText ?: return@mapNotNull null
                    val file = call.containingFile.originalFile.virtualFile ?: return@mapNotNull null
                    // The handler is shown after the controller, like Spring's `WelcomeController#hello`.
                    val handler = call.argumentList.expressionList.lastOrNull()?.let(HandlerFinder::referencedHandler)
                    val handlerFile = handler?.containingFile?.originalFile?.virtualFile

                    // Opening an endpoint goes to its handler, or to the mapping when the handler is not known.
                    Endpoint(
                        verb,
                        path,
                        handler?.let(HandlerFinder::labelOf) ?: controller,
                        handlerFile ?: file,
                        if (handler != null && handlerFile != null) (handler.identifier?.textOffset ?: handler.textOffset) else call.textOffset,
                    )
                }
            }
            .sortedWith(compareBy({ it.path }, { it.verb }))
    }
}
