package com.jlight.resharpermcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.util.Consumer
import org.jetbrains.annotations.Nls
import java.awt.Component
import java.awt.event.MouseEvent

class McpStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
    companion object {
        const val ID = "ReSharperMcp.StatusBar"
    }

    private var statusBar: StatusBar? = null
    private var subscription: AutoCloseable? = null

    // Cached status — read on EDT from the shared service
    private var connected: Boolean = false
    private var role: String = "unknown"
    private var solutions: List<SolutionInfo> = emptyList()
    private var port: Int = 0

    override fun ID(): String = ID

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        val service = project.service<McpMonitorService>()
        subscription = service.register { state, _ ->
            ApplicationManager.getApplication().invokeLater {
                connected = state.online
                role = when (state.role) {
                    Role.PRIMARY -> "primary"
                    Role.PEER -> "peer"
                    else -> "unknown"
                }
                solutions = state.solutions
                port = state.port
                statusBar?.updateWidget(ID)
            }
        }
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    // --- TextPresentation ---

    @Nls
    override fun getText(): String {
        return if (connected) "MCP: $port" else "MCP: offline"
    }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getTooltipText(): String = "ReSharper MCP Server"

    override fun getClickConsumer(): Consumer<MouseEvent>? = Consumer { event ->
        showPopup(event.component)
    }

    // --- Popup ---

    private fun showPopup(component: Component) {
        val items = mutableListOf<PopupItem>()

        if (connected) {
            items.add(PopupItem("Status: Running (${role.replaceFirstChar { it.uppercase() }})", false))
            items.add(PopupItem("Port: $port", false))
            if (solutions.isNotEmpty()) {
                items.add(PopupItem("───", false))
                items.add(PopupItem("Solutions:", false))
                solutions.forEach { solution ->
                    items.add(PopupItem("  ${formatSolutionLabel(solution, solutions)}", false))
                }
            }
            items.add(PopupItem("───", false))
            items.add(PopupItem("Restart Server", true))
        } else {
            items.add(PopupItem("Status: Offline", false))
            items.add(PopupItem("Port: $port", false))
        }

        val popup = JBPopupFactory.getInstance().createListPopup(
            object : BaseListPopupStep<PopupItem>("MCP Server", items) {
                override fun getTextFor(value: PopupItem): String = value.text

                override fun isSelectable(value: PopupItem): Boolean = value.actionable

                override fun onChosen(selectedValue: PopupItem, finalChoice: Boolean): PopupStep<*>? {
                    if (selectedValue.text == "Restart Server") {
                        project.service<McpMonitorService>().restartServer()
                    }
                    return FINAL_CHOICE
                }
            }
        )

        popup.showUnderneathOf(component)
    }

    // --- Lifecycle ---

    override fun dispose() {
        subscription?.close()
    }

    private data class PopupItem(val text: String, val actionable: Boolean)
}
