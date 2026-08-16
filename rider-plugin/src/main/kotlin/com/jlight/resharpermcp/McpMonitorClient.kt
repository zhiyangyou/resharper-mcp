package com.jlight.resharpermcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Low-level transport to the .NET backend: JSON-RPC over HTTP plus the SSE log stream.
 * Owns the port (env var override, then PID sidecar discovery), the Jackson parser, and
 * a reconnecting SSE reader. Thread-safe for use by [McpMonitorService].
 */
class McpMonitorClient {
    companion object {
        const val DEFAULT_PORT = 23741
        private const val POLL_TIMEOUT_MS = 3000
        private const val SSE_RECONNECT_MS = 3000L
    }

    private val mapper = ObjectMapper()
    private val sseRunning = AtomicBoolean(false)

    @Volatile var port: Int = resolvePort()
    @Volatile var connected: Boolean = false
    @Volatile var lastIndex: Long = 0

    /** Fetches a monitor snapshot since [after]; null when the backend is unreachable. */
    fun fetchMonitor(after: Long = 0, limit: Int = 200): MonitorSnapshot? {
        val body = """{"jsonrpc":"2.0","id":1,"method":"internal/monitor","params":{"after":$after,"limit":$limit}}"""
        val json = httpPost(body) ?: return null
        return try {
            parseSnapshot(json)
        } catch (e: Exception) {
            connected = false
            null
        }
    }

    fun restartServer(): Boolean {
        val json = httpPost("""{"jsonrpc":"2.0","id":1,"method":"internal/restart","params":{}}""")
        return json != null
    }

    /** Reads the PID sidecar file after a failed poll — handles promotion changing this process's port. */
    fun refreshPortIfNeeded() {
        readPidPortFile()?.let { port = it }
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

    private fun parseSnapshot(json: String): MonitorSnapshot {
        val result = mapper.readTree(json)["result"] ?: throw IllegalStateException("no result")
        val state = MonitorState(
            online = true,
            role = Role.valueOf(result["role"].asText().uppercase()),
            port = result["port"].asInt(),
            solutions = result["solutions"].map { it["name"].asText() },
            nextIndex = result["nextIndex"].asLong(),
            counts = result["counts"].fields().asSequence().associate { it.key to it.value.asLong() }
        )
        val logs = result["logs"].mapNotNull { parseLog(it) }
        result["nextIndex"].asLong().let { if (it > lastIndex) lastIndex = it }
        connected = true
        return MonitorSnapshot(state, logs)
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
            peerPort = node["peerPort"].asInt(0),
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

    private fun httpPost(body: String): String? {
        val url = URI("http://127.0.0.1:$port/").toURL()
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
