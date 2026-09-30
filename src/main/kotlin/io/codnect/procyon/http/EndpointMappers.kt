package io.codnect.procyon.http

import com.goide.psi.GoMethodDeclaration
import com.goide.psi.GoTypeSpec
import com.intellij.psi.util.PsiTreeUtil
import io.codnect.procyon.Procyon

internal object EndpointMappers {

    // Reads the methods from the PSI of the file instead of Go's index, so it works
    // without a go.mod and before indexing has finished.
    fun isEndpointMapper(typeSpec: GoTypeSpec): Boolean =
        PsiTreeUtil.findChildrenOfType(typeSpec.containingFile, GoMethodDeclaration::class.java).any { method ->
            isMapEndpoints(method) && method.receiverType?.text?.removePrefix("*") == typeSpec.name
        }

    /** Returns whether the method is Procyon's `MapEndpoints(endpoints http.Endpoints)`. */
    fun isMapEndpoints(method: GoMethodDeclaration): Boolean =
        method.name == "MapEndpoints" &&
            Procyon.isType(
                method.containingFile,
                method.signature?.parameters?.parameterDeclarationList?.singleOrNull()?.type,
                Procyon.HTTP,
                "Endpoints",
            )
}
