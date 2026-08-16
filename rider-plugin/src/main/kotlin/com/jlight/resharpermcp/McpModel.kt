package com.jlight.resharpermcp

/**
 * Shared data model mirroring the backend's internal/monitor JSON contract.
 * Field names match RequestLogEntry.ToJObject() in the .NET backend.
 */
enum class Role { PRIMARY, PEER, UNKNOWN }

enum class LogKind { LOCAL, FORWARDED, OTHER }

data class MonitorState(
    val online: Boolean,
    val role: Role,
    val port: Int,
    val solutions: List<String>,
    val nextIndex: Long,
    val counts: Map<String, Long>
) {
    companion object {
        val offline = MonitorState(false, Role.UNKNOWN, 0, emptyList(), 0, emptyMap())
    }
}

data class RequestLogEntry(
    val index: Long,
    val ts: Long,
    val method: String,
    val tool: String?,
    val kind: LogKind,
    val viaPrimary: Boolean,
    val durationMs: Long,
    val solution: String?,
    val peerPort: Int,
    val args: String,
    val result: String,
    val argsPreview: String,
    val resultPreview: String,
    val isError: Boolean,
    val errorText: String?,
    val argsPreviewTruncated: Boolean,
    val resultPreviewTruncated: Boolean
)

data class MonitorSnapshot(
    val state: MonitorState,
    val logs: List<RequestLogEntry>
)
