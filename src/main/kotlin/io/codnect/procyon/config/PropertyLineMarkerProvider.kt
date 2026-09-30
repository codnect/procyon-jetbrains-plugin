package io.codnect.procyon.config

import com.goide.psi.GoSpecType
import com.goide.psi.GoTypeSpec
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiElement
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.awt.RelativePoint
import io.codnect.procyon.ProcyonBundle
import io.codnect.procyon.ProcyonIcons
import io.codnect.procyon.component.ComponentFinder
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * Puts a gutter icon on a struct that implements Procyon's `Properties` (has a `Prefix() string`
 * method). It navigates to the prefix key in the YAML files under a `resources` directory.
 * Fields navigate to their own key through [PropertyReferenceContributor].
 */
class PropertyLineMarkerProvider : LineMarkerProviderDescriptor() {

    override fun getName(): String = ProcyonBundle.message("gutter.property.name")

    override fun getIcon(): Icon = ProcyonIcons.ConfigurationProperties

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    // Whether the struct is registered needs the index, so this is done in the slow pass.
    override fun collectSlowLineMarkers(
        elements: List<PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>,
    ) {
        for (element in elements) {
            ProgressManager.checkCanceled()

            // The name leaf sits under a GoSpecType, which is itself under the GoTypeSpec.
            if (element.parent !is GoSpecType) continue
            val typeSpec = element.parent.parent as? GoTypeSpec ?: continue
            if (typeSpec.identifier != element) continue

            val prefix = ProcyonProperties.prefixOf(typeSpec) ?: continue
            val keys = ProcyonProperties.split(prefix)
            val registration = ComponentFinder.registry(element.project).components[ComponentFinder.keyOf(typeSpec)]
            val tooltip = registration?.let { ProcyonBundle.message("gutter.properties.registered", prefix, it.file.name) }
                ?: ProcyonBundle.message("gutter.properties.not.registered", prefix)

            result.add(
                LineMarkerInfo(
                    element,
                    element.textRange,
                    if (registration != null) ProcyonIcons.ConfigurationPropertiesComponent else ProcyonIcons.ConfigurationProperties,
                    { tooltip },
                    { event, source -> navigate(source, keys, event) },
                    GutterIconRenderer.Alignment.LEFT,
                    { ProcyonBundle.message("gutter.properties.name") },
                )
            )
        }
    }

    // The YAML files are looked up on click, so the icon shows even when they do not exist.
    private fun navigate(source: PsiElement, keys: List<String>, event: MouseEvent) {
        val targets = ProcyonProperties.findYamlKeys(source, keys)
        when (targets.size) {
            0 -> {
                val editor = FileEditorManager.getInstance(source.project).selectedTextEditor ?: return
                HintManager.getInstance().showInformationHint(
                    editor, ProcyonBundle.message("property.not.found", keys.joinToString("."))
                )
            }

            1 -> open(targets.single())

            else -> JBPopupFactory.getInstance()
                .createPopupChooserBuilder(targets)
                .setTitle(ProcyonBundle.message("property.popup.title"))
                .setRenderer(SimpleListCellRenderer.create<PsiElement> { label, value, _ -> label.text = value.containingFile.virtualFile.presentableUrl })
                .setItemChosenCallback { open(it) }
                .createPopup()
                .show(RelativePoint(event))
        }
    }

    private fun open(target: PsiElement) {
        val file = target.containingFile.virtualFile ?: return
        OpenFileDescriptor(target.project, file, target.textOffset).navigate(true)
    }
}
