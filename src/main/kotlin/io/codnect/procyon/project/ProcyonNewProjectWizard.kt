package io.codnect.procyon.project

import com.goide.project.GoProjectLifecycle
import com.goide.vgo.wizard.VgoModuleBuilder
import com.goide.vgo.wizard.VgoProjectGeneratorPeer
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardBaseData.Companion.baseData
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.language.LanguageGeneratorNewProjectWizard
import com.intellij.ide.wizard.setupProjectFromBuilder
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.util.ui.UIUtil
import io.codnect.procyon.ProcyonBundle
import io.codnect.procyon.ProcyonIcons
import java.nio.file.Path
import javax.swing.Icon

/**
 * Adds "Procyon" to the New Project dialog. It is registered like the Go one, so the
 * platform builds the same Name, Location and Git steps around it.
 */
class ProcyonNewProjectWizard : LanguageGeneratorNewProjectWizard {

    override val name: String = "Procyon"

    override val icon: Icon = ProcyonIcons.SmallLogo

    override fun createStep(parent: NewProjectWizardStep): NewProjectWizardStep = ProcyonStep(parent)
}

private class ProcyonStep(parent: NewProjectWizardStep) : AbstractNewProjectWizardStep(parent) {

    private val peer = VgoProjectGeneratorPeer(true)
    private val templateProperty = propertyGraph.property(ProcyonTemplate.HTTP)
    private var sampleCode: JBCheckBox? = null

    override fun setupUI(builder: Panel) {
        val settings = peer.createSettingsPanel(context.disposable, null)
        // The template only applies to the sample code, so it follows Go's own checkbox.
        sampleCode = UIUtil.findComponentsOfType(settings, JBCheckBox::class.java)
            .firstOrNull { it.text == "Add sample code" }

        with(builder) {
            row {
                cell(settings)
            }
            row("Template:") {
                val template = comboBox(ProcyonTemplate.entries).bindItem(templateProperty)
                sampleCode?.let { checkBox ->
                    template.component.isEnabled = checkBox.isSelected
                    checkBox.addItemListener { template.component.isEnabled = checkBox.isSelected }
                }
            }
        }
    }

    override fun setupProject(project: Project) {
        val data = baseData ?: return
        val go = peer.settings.sdk.executable?.path ?: return
        val root = Path.of(data.path, data.name)
        val name = data.name
        val template = templateProperty.get().takeIf { peer.settings.addSampleCode }

        // The CLI runs first, in a dialog, so that the editor does not open on an empty project.
        val generated = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            ThrowableComputable<ProcyonCli.Generated, RuntimeException> {
                ProcyonCli.generate(go, name, template, ProgressManager.getInstance().progressIndicator)
            },
            ProcyonBundle.message("project.creating"),
            false,
            project,
        )

        // Go's own builder creates go.mod and fails when it exists, so it is written after the builder.
        generated.copyTo(root) { it != "go.mod" && it != "go.sum" }

        // Go's own builder creates the module and sets up the SDK, so the project is a real Go
        // project. Its sample code is switched off because `procyon init` writes the sample.
        sampleCode?.isSelected = false
        val moduleBuilder = VgoModuleBuilder(peer, context.isCreatingNewProject)
        moduleBuilder.name = name
        moduleBuilder.contentEntryPath = data.contentEntryPath
        setupProjectFromBuilder(project, moduleBuilder)

        // Go creates go.mod in a later step of the same queue, so this runs after it and replaces it.
        GoProjectLifecycle.runWhenProjectSetupFinished(project) {
            ApplicationManager.getApplication().invokeLater {
                try {
                    generated.replaceIn(root, listOf("go.mod", "go.sum"))

                    generated.tidyProblem?.let {
                        NotificationGroupManager.getInstance().getNotificationGroup("Procyon")
                            .createNotification(ProcyonBundle.message("project.tidy.failed", it), NotificationType.WARNING)
                            .notify(project)
                    }
                } finally {
                    generated.delete()
                }
            }
        }
    }
}
