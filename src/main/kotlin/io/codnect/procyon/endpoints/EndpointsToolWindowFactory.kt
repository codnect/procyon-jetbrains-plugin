package io.codnect.procyon.endpoints

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import io.codnect.procyon.ProcyonBundle
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.Callable

/** Lists the HTTP endpoints mapped in the project. */
class EndpointsToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val model = ListTableModel<Endpoint>(
            column(ProcyonBundle.message("endpoints.column.method")) { it.verb },
            column(ProcyonBundle.message("endpoints.column.path")) { it.path },
            column(ProcyonBundle.message("endpoints.column.handler")) { it.controller },
        )
        val table = JBTable(model).apply {
            setShowGrid(false)
            emptyText.text = ProcyonBundle.message("endpoints.empty")
        }

        fun open() {
            val row = table.selectedRow.takeIf { it >= 0 } ?: return
            val endpoint = model.getItem(row)
            OpenFileDescriptor(project, endpoint.file, endpoint.offset).navigate(true)
        }
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) open()
            }
        })
        table.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    e.consume()
                    open()
                }
            }
        })

        fun refresh() {
            ReadAction.nonBlocking(Callable { EndpointFinder.find(project) })
                .inSmartMode(project)
                .expireWith(toolWindow.disposable)
                .finishOnUiThread(ModalityState.any()) { model.items = it }
                .submit(AppExecutorUtil.getAppExecutorService())
        }

        val actions = DefaultActionGroup(object : AnAction(
            ProcyonBundle.message("endpoints.refresh"),
            ProcyonBundle.message("endpoints.refresh.description"),
            AllIcons.Actions.Refresh,
        ) {
            override fun actionPerformed(e: AnActionEvent) = refresh()
        })

        val panel = SimpleToolWindowPanel(false, true)
        val toolbar = ActionManager.getInstance().createActionToolbar("ProcyonEndpoints", actions, false)
        toolbar.targetComponent = panel
        panel.toolbar = toolbar.component
        panel.setContent(ScrollPaneFactory.createScrollPane(table))

        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false))
        refresh()
    }

    private fun column(name: String, value: (Endpoint) -> String) =
        object : ColumnInfo<Endpoint, String>(name) {
            override fun valueOf(item: Endpoint): String = value(item)
        }
}
