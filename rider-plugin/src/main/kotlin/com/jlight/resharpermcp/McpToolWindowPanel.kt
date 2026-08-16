package com.jlight.resharpermcp

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 * MCP Monitor tool window content: a status strip (role / port / online / restart) on top and
 * two request-log tabs below — 「本地请求」and「路由请求」. The routed tab is disabled while this
 * process is not the primary. Rows open a full-detail dialog on double-click.
 */
class McpToolWindowPanel(project: Project, toolWindow: ToolWindow) : JPanel(BorderLayout()), Disposable {

    private val service = project.service<McpMonitorService>()

    private val statusRole = JBLabel("—")
    private val statusPort = JBLabel("—")
    private val statusOnline = JBLabel("—")
    private val restartButton = JButton("Restart")

    private val localModel = McpLogsTableModel { it.kind == LogKind.LOCAL }
    private val routedModel = McpLogsTableModel { it.kind == LogKind.FORWARDED }

    private val localTable = JBTable(localModel)
    private val routedTable = JBTable(routedModel)

    private val tabs = JBTabbedPane()
    private var subscription: AutoCloseable? = null
    private var latestState: MonitorState = MonitorState.offline

    init {
        buildUi()
        installBehavior(project)
    }

    private fun buildUi() {
        val strip = JPanel(BorderLayout())
        strip.border = BorderFactory.createEmptyBorder(6, 10, 6, 10)
        statusRole.border = BorderFactory.createEmptyBorder(0, 0, 0, 14)
        statusPort.border = BorderFactory.createEmptyBorder(0, 0, 0, 14)
        statusOnline.border = BorderFactory.createEmptyBorder(0, 0, 0, 14)

        val left = JPanel()
        left.add(statusRole)
        left.add(statusPort)
        left.add(statusOnline)
        strip.add(left, BorderLayout.WEST)
        strip.add(restartButton, BorderLayout.EAST)

        tabs.addTab("本地请求", JScrollPane(localTable))
        tabs.addTab("路由请求", JScrollPane(routedTable))
        updateTabAvailability()

        add(strip, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)
    }

    private fun installBehavior(project: Project) {
        localTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount >= 2 && localTable.selectedRow >= 0) {
                    RequestDetailDialog(project, localModel.entryAt(localTable.selectedRow)).show()
                }
            }
        })
        routedTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount >= 2 && routedTable.selectedRow >= 0) {
                    RequestDetailDialog(project, routedModel.entryAt(routedTable.selectedRow)).show()
                }
            }
        })

        restartButton.addActionListener { service.restartServer() }

        subscription = service.register { state, entries ->
            ApplicationManager.getApplication().invokeLater {
                latestState = state
                renderState(state)
                localModel.setEntries(entries)
                routedModel.setEntries(entries)
            }
        }
        service.refresh()
    }

    private fun renderState(state: MonitorState) {
        statusRole.text = when (state.role) {
            Role.PRIMARY -> "Primary"
            Role.PEER -> "Peer"
            else -> "未知"
        }
        statusPort.text = if (state.port > 0) "端口 ${state.port}" else "端口 —"
        statusOnline.text = if (state.online) "● 在线" else "○ 离线"
        updateTabAvailability()
    }

    private fun updateTabAvailability() {
        tabs.setEnabledAt(1, latestState.role == Role.PRIMARY)
    }

    override fun dispose() {
        subscription?.close()
    }
}
