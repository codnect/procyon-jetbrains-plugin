package io.codnect.procyon.config

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import org.jetbrains.yaml.YAMLUtil
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * Ctrl/Cmd+click on a key in a YAML file under `resources` goes to the Go field or struct that
 * binds it. The Go side goes through [PropertyReferenceContributor] instead, so that clicking
 * a field name still shows its usages.
 */
class PropertyGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        sourceElement ?: return null
        val targets = fromYamlKey(sourceElement)
        return targets?.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    private fun fromYamlKey(element: PsiElement): List<PsiElement>? {
        val keyValue = element.parent as? YAMLKeyValue ?: return null
        if (keyValue.key != element) return null

        val file = element.containingFile.virtualFile ?: return null
        if ("/resources/" !in file.path) return null

        return ProcyonProperties.findGoTargets(element.project, YAMLUtil.getConfigFullNameParts(keyValue))
    }

    override fun getActionText(context: DataContext): String? = null
}
