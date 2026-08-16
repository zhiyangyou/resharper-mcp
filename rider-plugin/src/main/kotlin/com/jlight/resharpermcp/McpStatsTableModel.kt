package com.jlight.resharpermcp

import javax.swing.table.AbstractTableModel

/**
 * Table model for the "工具统计" tab — cumulative per-tool aggregates: call count, total duration,
 * and error count, all lifetime values from the backend (not evicted with the ring buffer).
 *
 * Column layout: 0 Tool | 1 调用次数 | 2 总耗时 | 3 平均耗时 | 4 错误数
 */
class McpStatsTableModel : AbstractTableModel() {
    private val columns = listOf("Tool", "调用次数", "总耗时", "平均耗时", "错误数")
    private val rows = mutableListOf<ToolStat>()

    fun setStats(all: List<ToolStat>) {
        rows.clear()
        rows.addAll(all)
        fireTableDataChanged()
    }

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column]

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
        val s = rows[rowIndex]
        return when (columnIndex) {
            0 -> s.name
            1 -> s.callCount
            2 -> "${s.totalDurationMs} ms"
            3 -> if (s.callCount > 0) "${s.totalDurationMs / s.callCount} ms" else "—"
            4 -> s.errorCount
            else -> null
        }
    }
}
