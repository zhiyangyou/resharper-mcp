package com.jlight.resharpermcp

/**
 * Shared data model mirroring the backend's internal/monitor JSON contract.
 * Field names match RequestLogEntry.ToJObject() and ClientSession.ToJObject() in the .NET backend.
 */
enum class Role { PRIMARY, PEER, UNKNOWN }

enum class LogKind { LOCAL, FORWARDED, OTHER }

enum class SolutionSource { LOCAL, PEER, UNKNOWN }

data class SolutionInfo(
    val id: String,
    val name: String,
    val path: String,
    val source: SolutionSource = SolutionSource.UNKNOWN,
    val peerPort: Int = 0
)

fun formatSolutionLabel(solution: SolutionInfo, allSolutions: List<SolutionInfo>): String {
    val duplicateName = allSolutions.count { it.name.equals(solution.name, ignoreCase = true) } > 1
    return if (duplicateName && solution.path.isNotBlank())
        "${solution.name} — ${solution.path}"
    else
        solution.name
}

data class ClientSession(
    val clientName: String,
    val clientVersion: String,
    val remoteAddress: String,
    val firstSeen: Long,
    val lastActive: Long,
    val requestCount: Long,
    val online: Boolean,
    val offlineSince: Long
)

/** Per-tool aggregate: cumulative call count, total duration, and error count. */
data class ToolStat(
    val name: String,
    val callCount: Long,
    val totalDurationMs: Long,
    val errorCount: Long
)

data class MonitorState(
    val online: Boolean,
    val role: Role,
    val port: Int,
    val solutions: List<SolutionInfo>,
    val localSolutions: List<SolutionInfo>,
    val peerSolutions: List<SolutionInfo>,
    val peerProcessCount: Int,
    val clientCount: Int,
    val clients: List<ClientSession>,
    val nextIndex: Long,
    val counts: Map<String, Long>,
    val toolStats: List<ToolStat>
) {
    companion object {
        val offline = MonitorState(false, Role.UNKNOWN, 0, emptyList(), emptyList(), emptyList(), 0, 0, emptyList(), 0, emptyMap(), emptyList())
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
    val solutionId: String?,
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
