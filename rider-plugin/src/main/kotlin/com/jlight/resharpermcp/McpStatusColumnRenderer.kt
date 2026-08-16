package com.jlight.resharpermcp

import java.awt.Color
import java.awt.Component
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer

/**
 * Renders the status column (✓ / ✗): success in green, failure in red.
 * Mirrors the "success green / failure red" decision for the request-status column.
 */
class McpStatusColumnRenderer : DefaultTableCellRenderer() {

    companion object {
        private val SUCCESS_COLOR = Color(0x2E7D32)   // dark green
        private val ERROR_COLOR = Color(0xC62828)     // dark red
    }

    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        foreground = if (value == "✗") ERROR_COLOR else SUCCESS_COLOR
        return c
    }
}
