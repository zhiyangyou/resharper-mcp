package com.jlight.resharpermcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Low-level transport to the .NET backend: JSON-RPC over HTTP plus the SSE log stream.
 * Owns the port (env var override, then PID sidecar discovery), the Jackson parser, and
 * a reconnecting SSE reader. Thread-safe for use by [McpMonitorService].
 */
class McpMonitorClient(private val projectBasePath: String? = null) {
    companion object {
        const val DEFAULT_PORT = 23741
        private const val POLL_TIMEOUT_MS = 3000
        private const val SSE_RECONNECT_MS = 3000L
    }

    private val mapper = ObjectMapper()
    private val sseRunning = AtomicBoolean(false)

    @Volatile var port: Int = resolvePort()
    @Volatile var connected: Boolean = false
    @Volatile var lastIndex: Long = -1

    /** Fetches a monitor snapshot since [after]; null when the backend is unreachable. */
    fun fetchMonitor(after: Long = -1, limit: Int = 200): MonitorSnapshot? {
        val body = """{"jsonrpc":"2.0","id":1,"method":"internal/monitor","params":{"after":$after,"limit":$limit}}"""
        val json = httpPostForProject(body) ?: return null
        return try {
            parseSnapshot(json)
        } catch (e: Exception) {
            connected = false
            null
        }
    }

    fun restartServer(): Boolean {
        discoverProjectPort()
        val json = httpPost(port, """{"jsonrpc":"2.0","id":1,"method":"internal/restart","params":{}}""")
        return json != null
    }

    /** Reads the PID sidecar file after a failed poll — handles promotion changing this process's port. */
    fun refreshPortIfNeeded() {
        readPidPortFile()?.let { port = it }
    }

    fun advanceLastIndex(index: Long) {
        if (index <= lastIndex)
            return
        synchronized(this) {
            if (index > lastIndex)
                lastIndex = index
        }
    }

    internal fun isResponseForCurrentProject(json: String): Boolean {
        return belongsToProject(json)
    }

    /**
     * Opens the SSE stream on a background thread and calls [onLog] for each `event: log` frame.
     * Reconnects with a fixed backoff; on reconnect the caller (service) replays gaps via fetchMonitor(after=lastIndex).
     */
    fun startSse(onLog: (RequestLogEntry) -> Unit) {
        if (!sseRunning.compareAndSet(false, true)) return
        AppExecutorUtil.getAppExecutorService().submit {
            try {
                while (sseRunning.get() && !Thread.currentThread().isInterrupted) {
                    try {
                        readSseStream(onLog)
                    } catch (e: Exception) {
                        if (!sseRunning.get()) break
                        // Fatal / connection dropped — back off and reconnect
                    }
                    Thread.sleep(SSE_RECONNECT_MS)
                }
            } catch (_: InterruptedException) {
                // shutting down
            }
        }
    }

    fun stopSse() {
        sseRunning.set(false)
    }

    fun dispose() {
        stopSse()
    }

    // --- SSE reading ---

    private fun readSseStream(onLog: (RequestLogEntry) -> Unit) {
        discoverProjectPort()
        val url = URI("http://127.0.0.1:$port/").toURL()
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = POLL_TIMEOUT_MS
            conn.readTimeout = 0 // block until server writes or connection dies
            if (conn.responseCode != 200) return

            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var event: String? = null
                val data = StringBuilder()
                while (sseRunning.get()) {
                    val line = reader.readLine() ?: break // EOF — connection closed
                    when {
                        line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> data.append(line.removePrefix("data:").trim()).append('\n')
                        line.isBlank() -> {
                            if (event == "log" && data.isNotEmpty()) {
                                try {
                                    val entry = parseLog(mapper.readTree(data.toString().trim()))
                                    if (entry != null) onLog(entry)
                                } catch (_: Exception) {
                                    // Malformed frame — skip
                                }
                            }
                            event = null
                            data.setLength(0)
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    // --- Jackson parsing ---

    internal fun parseSnapshot(json: String): MonitorSnapshot {
        val result = mapper.readTree(json)["result"] ?: throw IllegalStateException("no result")
        val rawSolutions = parseSolutions(result["solutions"], SolutionSource.UNKNOWN)
        val localSolutions = parseSolutions(result["localSolutions"], SolutionSource.LOCAL)
        val resolvedLocalSolutions = if (localSolutions.isEmpty())
            rawSolutions.map { it.copy(source = SolutionSource.LOCAL) }
        else
            localSolutions
        val localIds = resolvedLocalSolutions.map { it.id }.toSet()
        val legacyLocalNames = resolvedLocalSolutions
            .filter { it.id == it.name }
            .map { it.name }
            .toSet()
        val solutions = rawSolutions.map { solution ->
            when {
                solution.id in localIds || solution.name in legacyLocalNames -> solution.copy(source = SolutionSource.LOCAL)
                solution.source == SolutionSource.LOCAL -> solution
                else -> solution.copy(source = SolutionSource.PEER)
            }
        }
        val peerSolutions = solutions.filter { it.source == SolutionSource.PEER }
        val peerProcessCount = peerSolutions.mapNotNull { it.peerPort.takeIf { port -> port > 0 } }
            .distinct()
            .count()
            .let { count -> if (count > 0) count else peerSolutions.map { it.id }.distinct().count() }
        val state = MonitorState(
            online = true,
            role = Role.valueOf(result["role"].asText().uppercase()),
            port = result["port"].asInt(),
            solutions = solutions,
            localSolutions = resolvedLocalSolutions,
            peerSolutions = peerSolutions,
            peerProcessCount = peerProcessCount,
            clientCount = result["clientCount"].asInt(0),
            clients = result["clients"].map { parseClient(it) },
            toolStats = result["toolStats"].map { parseToolStat(it) },
            nextIndex = result["nextIndex"].asLong(),
            counts = result["counts"].fields().asSequence().associate { it.key to it.value.asLong() }
        )
        val logs = result["logs"].mapNotNull { parseLog(it) }
        connected = true
        return MonitorSnapshot(state, logs)
    }

    private fun parseSolutions(node: JsonNode?, fallbackSource: SolutionSource): List<SolutionInfo> {
        if (node == null || !node.isArray)
            return emptyList()

        return node.map { parseSolution(it, fallbackSource) }
    }

    private fun parseSolution(node: JsonNode, fallbackSource: SolutionSource): SolutionInfo {
        val name = node["name"].textOrEmpty()
        val path = node["path"].textOrEmpty()
        val id = node["id"].textOrEmpty().ifBlank { path }.ifBlank { name }
        val source = when (node["source"].textOrEmpty().lowercase()) {
            "local" -> SolutionSource.LOCAL
            "peer" -> SolutionSource.PEER
            else -> fallbackSource
        }
        return SolutionInfo(
            id = id,
            name = name.ifBlank { id },
            path = path.ifBlank { id },
            source = source,
            peerPort = node["peerPort"]?.asInt(0) ?: 0
        )
    }

    private fun JsonNode?.textOrEmpty(): String {
        return if (this == null || this.isNull) "" else this.asText("").trim()
    }

    private fun parseClient(node: JsonNode): ClientSession {
        return ClientSession(
            clientName = node["clientName"].asText("unknown"),
            clientVersion = node["clientVersion"].asText(""),
            remoteAddress = node["remoteAddress"].asText(""),
            firstSeen = node["firstSeen"].asLong(),
            lastActive = node["lastActive"].asLong(),
            requestCount = node["requestCount"].asLong(),
            online = node["online"].asBoolean(false),
            offlineSince = node["offlineSince"].asLong(0)
        )
    }

    private fun parseToolStat(node: JsonNode): ToolStat {
        return ToolStat(
            name = node["name"].asText(),
            callCount = node["callCount"].asLong(0),
            totalDurationMs = node["totalDurationMs"].asLong(0),
            errorCount = node["errorCount"].asLong(0)
        )
    }

    private fun parseLog(node: JsonNode): RequestLogEntry? {
        return RequestLogEntry(
            index = node["index"].asLong(),
            ts = node["ts"].asLong(),
            method = node["method"].asText(),
            tool = node["tool"].takeIf { !it.isNull }?.asText(),
            kind = node["kind"].asText().let { runCatching { LogKind.valueOf(it.uppercase()) }.getOrDefault(LogKind.OTHER) },
            viaPrimary = node["viaPrimary"].asBoolean(false),
            durationMs = node["durationMs"].asLong(),
            solution = node["solution"].takeIf { !it.isNull }?.asText(),
            solutionId = node["solutionId"].takeIf { !it.isNull }?.asText()?.takeIf { it.isNotBlank() },
            peerPort = node["peerPort"]?.asInt(0) ?: 0,
            args = node["args"].asText(""),
            result = node["result"].asText(""),
            argsPreview = node["argsPreview"].asText(""),
            resultPreview = node["resultPreview"].asText(""),
            isError = node["isError"].asBoolean(false),
            errorText = node["errorText"].takeIf { !it.isNull }?.asText(),
            argsPreviewTruncated = node["argsPreviewTruncated"].asBoolean(false),
            resultPreviewTruncated = node["resultPreviewTruncated"].asBoolean(false)
        )
    }

    // --- HTTP ---

    private fun httpPostForProject(body: String): String? {
        val candidates = candidatePorts()
        var fallback: String? = null
        for (candidate in candidates) {
            val response = httpPost(candidate, body) ?: continue
            if (fallback == null)
                fallback = response
            if (belongsToProject(response)) {
                port = candidate
                return response
            }
        }
        return fallback
    }

    private fun discoverProjectPort() {
        if (projectBasePath.isNullOrBlank())
            return

        val body = """{"jsonrpc":"2.0","id":1,"method":"internal/status","params":{}}"""
        httpPostForProject(body)
    }

    private fun candidatePorts(): List<Int> {
        val ports = LinkedHashSet<Int>()
        ports.add(port)
        System.getenv("RESHARPER_MCP_PORT")?.toIntOrNull()?.takeIf { it > 0 }?.let { ports.add(it) }
        ports.addAll(readSidecarPorts())
        ports.add(DEFAULT_PORT)
        return ports.toList()
    }

    private fun readSidecarPorts(): List<Int> {
        return try {
            val temp = Path.of(System.getProperty("java.io.tmpdir"))
            Files.list(temp).use { files ->
                files.iterator().asSequence()
                    .filter { it.fileName.toString().startsWith("resharper-mcp-port-") }
                    .mapNotNull { file -> Files.readString(file).trim().toIntOrNull() }
                    .filter { it > 0 }
                    .distinct()
                    .toList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun belongsToProject(json: String): Boolean {
        val basePath = projectBasePath?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        return try {
            val result = mapper.readTree(json)["result"] ?: return false
            val localSolutions = result["localSolutions"]
            val solutions = localSolutions.takeIf { it != null && it.isArray }
                ?: result["solutions"]
            solutions != null && solutions.isArray && solutions.any { node ->
                val source = node["source"].asText("").trim().lowercase()
                val path = node["path"].asText("").trim()
                (source.isBlank() || source == "local") && pathMatchesProject(path, basePath)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun pathMatchesProject(solutionPath: String, basePath: String): Boolean {
        if (solutionPath.isBlank())
            return false
        val solution = normalizePath(solutionPath)
        val project = normalizePath(basePath)
        return solution == project || solution.startsWith("$project\\") ||
            Path.of(solution).parent?.toString()?.equals(project, ignoreCase = true) == true
    }

    private fun normalizePath(value: String): String {
        return value.replace('/', '\\').trimEnd('\\').lowercase()
    }

    private fun httpPost(candidatePort: Int, body: String): String? {
        val url = URI("http://127.0.0.1:$candidatePort/").toURL()
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = POLL_TIMEOUT_MS
            conn.readTimeout = POLL_TIMEOUT_MS

            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode != 200) return null
            return BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // --- Port discovery ---

    private fun resolvePort(): Int {
        System.getenv("RESHARPER_MCP_PORT")?.toIntOrNull()?.let { return it }
        readPidPortFile()?.let { return it }
        return DEFAULT_PORT
    }

    private fun readPidPortFile(): Int? {
        return try {
            val pid = ProcessHandle.current().pid()
            val file = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "resharper-mcp-port-$pid.txt")
            java.nio.file.Files.readString(file).trim().toIntOrNull()
        } catch (e: Exception) {
            null
        }
    }
}
