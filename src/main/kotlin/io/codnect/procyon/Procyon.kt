package io.codnect.procyon

import com.goide.psi.GoFile
import com.goide.psi.GoType
import com.intellij.psi.PsiFile

/** What identifies Procyon's own declarations, so that same-named ones of other packages do not match. */
internal object Procyon {

    const val MODULE = "go.codnect.io/procyon"
    const val HTTP = "$MODULE/http"
    const val COMPONENT = "$MODULE/component"

    /**
     * Returns whether [qualifier], as written in [file], names the package imported from [path].
     * A null qualifier is the unqualified use of a dot import.
     */
    fun isImport(file: PsiFile, qualifier: String?, path: String): Boolean {
        val imports = (file as? GoFile)?.imports ?: return false
        return imports.any { spec ->
            spec.path == path &&
                if (qualifier == null) spec.alias == "." else (spec.alias ?: path.substringAfterLast('/')) == qualifier
        }
    }

    /** Returns whether [type] is `name` of the package imported from [path], e.g. `http.Endpoints`. */
    fun isType(file: PsiFile, type: GoType?, path: String, name: String): Boolean {
        val reference = type?.typeReferenceExpression ?: return false
        if (reference.identifier.text != name) return false
        return isImport(file, reference.qualifier?.text?.removeSuffix("."), path)
    }
}
