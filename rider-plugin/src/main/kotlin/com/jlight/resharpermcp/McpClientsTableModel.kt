package com.jlight.resharpermcp

import javax.swing.table.AbstractTableModel
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Table model for the "MCP 客户端" tab — lists connected MCP client sessions with their
 * identity (name/version), remote address, first/last activity, request count, and online state.
 */
class McpClientsTableModel : AbstractTableModel() {
    private val columns = listOf("Client", "Version", "远程地址", "首次连接", "最近活动", "请求数", "状态")
    private val rows = mutableListOf<ClientSession>()

    fun setClients(all: List<ClientSession>) {
        rows.clear()
        rows.addAll(all.sortedBy { it.firstSeen })
        fireTableDataChanged()
    }

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column]

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
        val s = rows[rowIndex]
        return when (columnIndex) {
            0 -> s.clientName
            1 -> s.clientVersion.ifEmpty { "—" }
            2 -> s.remoteAddress.ifEmpty { "—" }
            3 -> formatTime(s.firstSeen)
            4 -> formatTime(s.lastActive)
            5 -> s.requestCount
            6 -> if (s.online) "● 在线" else "○ 离线"
            else -> null
        }
    }

    private fun formatTime(ts: Long): String {
        return SimpleDateFormat("HH:mm:ss.SSS").format(Date(ts))
    }
}
