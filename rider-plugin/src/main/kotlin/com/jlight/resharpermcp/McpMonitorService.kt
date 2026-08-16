package com.jlight.resharpermcp

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Project-level single source of truth for MCP monitor state. Polls the backend every few
 * seconds for status + log deltas (the durable source), and consumes SSE pushes for live updates.
 * Both the status bar and the Tool Window subscribe here — no duplicated polling or connections.
 */
@Service(Service.Level.PROJECT)
class McpMonitorService(private val project: Project) : Disposable {

    private val client = McpMonitorClient()
    private val listeners = CopyOnWriteArrayList<(MonitorState, List<RequestLogEntry>) -> Unit>()
    private var pollFuture: ScheduledFuture<*>? = null

    @Volatile var state: MonitorState = MonitorState.offline
        private set

    @Volatile private var entries: List<RequestLogEntry> = emptyList()

    init {
        pollFuture = AppExecutorUtil.getAppScheduledExecutorService()
            .scheduleWithFixedDelay(::poll, 0, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
        client.startSse { onSseLog(it) }
    }

    /** Subscribes to state/log updates. Returns an AutoCloseable that unsubscribes. */
    fun register(listener: (MonitorState, List<RequestLogEntry>) -> Unit): AutoCloseable {
        listeners.add(listener)
        // Push the current snapshot immediately so subscribers render without waiting for a poll
        listener(state, entries)
        return AutoCloseable { listeners.remove(listener) }
    }

    /** Entries filtered by the given predicate, in index order. */
    fun entriesFor(filter: (RequestLogEntry) -> Boolean): List<RequestLogEntry> = entries.filter(filter)

    /** One-shot refresh; used on demand and after SSE reconnects. */
    fun refresh() {
        poll()
    }

    fun restartServer() {
        AppExecutorUtil.getAppExecutorService().submit {
            client.restartServer()
        }
    }

    override fun dispose() {
        pollFuture?.cancel(false)
        client.dispose()
        listeners.clear()
    }

    // --- Polling ---

    private fun poll() {
        val snapshot = client.fetchMonitor(after = client.lastIndex)
        if (snapshot == null) {
            // Backend unreachable — retry with a fresh port (promotion may have changed it)
            client.refreshPortIfNeeded()
            state = MonitorState.offline
            notifyListeners()
            return
        }
        state = snapshot.state
        val fresh = snapshot.logs.filter { it.index > client.lastIndex }
        if (fresh.isNotEmpty()) {
            client.lastIndex = fresh.maxOf { it.index }
            synchronized(this) {
                entries = (entries + fresh)
                    .sortedBy { it.index }
                    .takeLast(MAX_ENTRIES)
            }
        }
        notifyListeners()
    }

    private fun onSseLog(entry: RequestLogEntry) {
        if (entry.index <= client.lastIndex) return // duplicate / replay — drop
        client.lastIndex = entry.index
        synchronized(this) {
            entries = (entries + entry).sortedBy { it.index }.takeLast(MAX_ENTRIES)
        }
        notifyListeners()
    }

    private fun notifyListeners() {
        val snapshotState = state
        val snapshotEntries = entries
        ApplicationManager.getApplication().invokeLater {
            listeners.forEach { it(snapshotState, snapshotEntries) }
        }
    }

    private companion object {
        const val POLL_INTERVAL_SECONDS = 5L
        const val MAX_ENTRIES = 1000
    }
}
