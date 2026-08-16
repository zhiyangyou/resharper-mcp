package com.jlight.resharpermcp

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

/**
 * Creates the MCP Monitor tool window. App-level singleton; all state lives in the per-project
 * panel, disposed with the tool window's content manager.
 */
class McpToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = McpToolWindowPanel(project, toolWindow)
        Disposer.register(toolWindow.contentManager as Disposable, panel)
        toolWindow.contentManager.addContent(
            toolWindow.contentManager.factory.createContent(panel, null, false)
        )
    }
}
