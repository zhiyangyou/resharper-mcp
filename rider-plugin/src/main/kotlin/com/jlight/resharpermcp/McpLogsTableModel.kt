package com.jlight.resharpermcp

import javax.swing.table.AbstractTableModel
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Table model for a request-log tab. Filters the shared entry list by [filter] and renders
 * table-row summaries (previews). Full args/result live in the entry and are shown in the detail dialog.
 *
 * Column layout:
 *   0 Time | 1 Tool | 2 Duration | 3 Target | 4 Source | 5 Status (✓/✗) | 6 Result | 7 Error
 */
class McpLogsTableModel(private val filter: (RequestLogEntry) -> Boolean) : AbstractTableModel() {
    private val columns = listOf("Time", "Tool", "Duration", "Target", "Source", "Status", "Result", "Error")
    private val rows = mutableListOf<RequestLogEntry>()

    fun setEntries(all: List<RequestLogEntry>) {
        rows.clear()
        rows.addAll(all.filter(filter).sortedBy { it.index })
        fireTableDataChanged()
    }

    fun entryAt(row: Int): RequestLogEntry = rows[row]

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column]

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
        val entry = rows[rowIndex]
        return when (columnIndex) {
            0 -> formatTime(entry.ts)
            1 -> entry.tool ?: entry.method
            2 -> "${entry.durationMs} ms"
            3 -> targetLabel(entry)
            4 -> when {
                entry.kind == LogKind.FORWARDED -> "route"
                entry.viaPrimary -> "viaPrimary"
                else -> "direct"
            }
            5 -> if (entry.isError) "✗" else "✓"
            6 -> entry.resultPreview.ifEmpty { "—" }
            7 -> entry.errorText ?: (if (entry.isError) "error" else "")
            else -> null
        }
    }

    private fun formatTime(ts: Long): String {
        return SimpleDateFormat("HH:mm:ss.SSS").format(Date(ts))
    }

    private fun targetLabel(entry: RequestLogEntry): String {
        val name = entry.solution?.takeIf { it.isNotBlank() }
        val id = entry.solutionId?.takeIf { it.isNotBlank() } ?: name
        val target = when {
            name != null && id != null && !name.equals(id, ignoreCase = true) -> "$name — $id"
            id != null -> id
            else -> "—"
        }
        return if (entry.kind == LogKind.FORWARDED && entry.peerPort > 0)
            "$target (peer:${entry.peerPort})"
        else
            target
    }
}
