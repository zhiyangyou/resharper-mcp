package com.jlight.resharpermcp

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.awt.Dimension
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Shows one request-log entry in full. Args and result are presented as read-only, selectable
 * text areas (the "编辑框" the requirement asked for) so contents can be inspected and copied.
 */
class RequestDetailDialog(project: Project?, private val entry: RequestLogEntry) :
    DialogWrapper(project) {

    init {
        title = "MCP 请求详情 — ${entry.tool ?: entry.method}"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val meta = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("工具"), JBLabel(entry.tool ?: entry.method))
            .addLabeledComponent(JBLabel("类型"), JBLabel(kindText()))
            .addLabeledComponent(JBLabel("耗时"), JBLabel("${entry.durationMs} ms"))
            .addLabeledComponent(JBLabel("目标"), JBLabel(targetText()))
            .addLabeledComponent(JBLabel("时间"), JBLabel(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(Date(entry.ts))))
            .addLabeledComponent(JBLabel("索引"), JBLabel(entry.index.toString()))
            .addVerticalGap(6)
            .panel

        val argsArea = readOnlyArea(entry.args)
        val resultArea = readOnlyArea(entry.result)

        val panel = JPanel(BorderLayout(8, 8))
        panel.add(meta, BorderLayout.NORTH)
        val body = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("参数"), JBScrollPane(argsArea).apply { preferredSize = Dimension(520, 140) })
            .addLabeledComponent(JBLabel("结果"), JBScrollPane(resultArea).apply { preferredSize = Dimension(520, 160) })
            .panel
        panel.add(body, BorderLayout.CENTER)
        panel.preferredSize = Dimension(600, 420)
        return panel
    }

    private fun kindText(): String = when (entry.kind) {
        LogKind.LOCAL -> "本地" + if (entry.viaPrimary) "（经 Primary 代理）" else ""
        LogKind.FORWARDED -> "路由（转发到 peer:${entry.peerPort}）"
        else -> "其他"
    }

    private fun targetText(): String = when {
        entry.kind == LogKind.FORWARDED -> "peer:${entry.peerPort}"
        entry.solution != null -> entry.solution
        else -> "—"
    }

    private fun readOnlyArea(text: String): JBTextArea {
        return JBTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = false
        }
    }
}
