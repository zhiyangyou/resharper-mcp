using System;
using System.Collections.Generic;
using System.Text;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace ReSharperMcp
{
    /// <summary>
    /// Classification of a JSON-RPC request handled by this process.
    /// - <see cref="Local"/>: a tools/call executed in this process (either called directly
    ///   by an MCP client, or proxied in by the primary).
    /// - <see cref="Forwarded"/>: this process is primary and proxied the call out to a peer.
    /// - <see cref="Other"/>: any non-tools/call request (initialize, tools/list, internal/*, ...).
    /// </summary>
    public enum RequestKind
    {
        Local = 0,
        Forwarded = 1,
        Other = 2
    }

    /// <summary>
    /// A single JSON-RPC request logged by the monitor. Filled incrementally by the request
    /// envelope in <see cref="McpHttpServer.HandlePost"/>, finalized by <see cref="RequestLogBuffer.Commit"/>.
    /// </summary>
    public sealed class RequestLogEntry
    {
        public long Index;                 // process-local monotonic index, assigned on commit
        public long TimestampMs;           // DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
        public string Method;              // "tools/call" | "initialize" | ...
        public string Tool;                // for tools/call, the tool name; else null
        public RequestKind Kind;           // Local / Forwarded / Other
        public long DurationMs;            // Stopwatch, filled by the envelope
        public string Solution;            // resolved target solution name (local branch)
        public string SolutionId;          // resolved target solution path (stable id)
        public int PeerPort;               // > 0 when forwarded to a peer
        public bool ViaPrimary;            // true when this process received the call via the primary's Mcp-Proxy header
        public string Args;                // full arguments, for the detail view
        public string Result;              // full result text, for the detail view
        public string ArgsPreview;         // table-row summary, ~PreviewLength chars
        public string ResultPreview;       // table-row summary, ~PreviewLength chars
        public bool IsError;
        public string ErrorText;
        public bool ArgsPreviewTruncated;
        public bool ResultPreviewTruncated;

        /// <summary>
        /// Shared serializer used by both the internal/monitor response and the SSE log events,
        /// so the two payloads always carry identical field names.
        /// </summary>
        public JObject ToJObject()
        {
            return new JObject
            {
                ["index"] = Index,
                ["ts"] = TimestampMs,
                ["method"] = Method,
                ["tool"] = Tool,
                ["kind"] = Kind.ToString().ToLowerInvariant(),
                ["viaPrimary"] = ViaPrimary,
                ["durationMs"] = DurationMs,
                ["solution"] = Solution,
                ["solutionId"] = SolutionId,
                ["peerPort"] = PeerPort,
                ["args"] = Args,
                ["result"] = Result,
                ["argsPreview"] = ArgsPreview,
                ["resultPreview"] = ResultPreview,
                ["isError"] = IsError,
                ["errorText"] = ErrorText,
                ["argsPreviewTruncated"] = ArgsPreviewTruncated,
                ["resultPreviewTruncated"] = ResultPreviewTruncated
            };
        }
    }

    /// <summary>
    /// Per-tool aggregate statistics. Counters are cumulative from process start and never
    /// evicted with the ring buffer, so totals reflect the full process lifetime.
    /// Only <see cref="RequestKind.Local"/> tool executions are counted (executed in this process,
    /// including calls proxied in by the primary) — forwarded proxy hops are not double-counted.
    /// </summary>
    public sealed class ToolStat
    {
        public string Name;
        public long CallCount;
        public long TotalDurationMs;
        public long ErrorCount;
    }

    /// <summary>
    /// Thread-safe ring buffer of recent <see cref="RequestLogEntry"/> values plus a lightweight
    /// pub/sub for SSE push. Owned at the shell-component level so log history survives promotion
    /// (a peer that becomes primary gets a fresh <see cref="McpHttpServer"/> but keeps this buffer).
    /// </summary>
    public sealed class RequestLogBuffer
    {
        public const int Capacity = 512;             // ~500 entries, power of two
        public const int PreviewLength = 200;        // table-row summary length
        public const int MaxStoredLength = 5000;     // cap on stored args/result, guards against single huge results

        private readonly object _lock = new object();
        private readonly RequestLogEntry[] _ring = new RequestLogEntry[Capacity];
        private readonly long[] _counts = new long[3];
        private readonly Dictionary<string, ToolStat> _toolStats = new Dictionary<string, ToolStat>();
        private long _errors;
        private int _head;
        private int _count;
        private long _nextIndex;
        private readonly List<Action<RequestLogEntry>> _subscribers = new List<Action<RequestLogEntry>>();

        /// <summary>
        /// Allocates a detached entry stamped with the current time. The caller fills it in
        /// without holding the buffer lock (tool execution stays outside _lock), then calls <see cref="Commit"/>.
        /// </summary>
        public RequestLogEntry Begin()
        {
            return new RequestLogEntry
            {
                TimestampMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
            };
        }

        /// <summary>
        /// Assigns a monotonic index, writes the entry into the ring (evicting the oldest when full),
        /// bumps counters, then notifies subscribers outside the lock (SSE writes must not block request threads).
        /// </summary>
        public long Commit(RequestLogEntry entry)
        {
            Action<RequestLogEntry>[] notify = null;

            lock (_lock)
            {
                entry.Index = _nextIndex++;
                _ring[_head] = entry;
                _head = (_head + 1) % Capacity;
                if (_count < Capacity) _count++;
                _counts[(int)entry.Kind]++;
                if (entry.IsError) _errors++;
                BumpToolStat(entry);

                if (_subscribers.Count > 0)
                {
                    notify = _subscribers.ToArray();
                    _subscribers.RemoveAll(IsDeadSubscriber);
                }
            }

            if (notify != null)
            {
                foreach (var subscriber in notify)
                {
                    try { subscriber(entry); }
                    catch { /* a failing subscriber must not break the request path */ }
                }
            }

            return entry.Index;
        }

        /// <summary>
        /// Returns up to <paramref name="limit"/> entries with Index &gt; <paramref name="after"/>,
        /// oldest first (ring order). Used for incremental polling and replay after SSE drops.
        /// </summary>
        public List<RequestLogEntry> Query(long after, int limit)
        {
            lock (_lock)
            {
                var result = new List<RequestLogEntry>();
                for (var i = 0; i < _count && result.Count < limit; i++)
                {
                    var idx = (_head - _count + i + Capacity) % Capacity;
                    var entry = _ring[idx];
                    if (entry.Index > after)
                        result.Add(entry);
                }
                return result;
            }
        }

        public void GetStats(out long[] counts, out long errors, out long nextIndex)
        {
            lock (_lock)
            {
                counts = (long[])_counts.Clone();
                errors = _errors;
                nextIndex = _nextIndex;
            }
        }

        /// <summary>
        /// Updates the cumulative per-tool aggregates for a committed entry.
        /// Called while holding <c>_lock</c> (from <see cref="Commit"/>), so it is not re-entrant-safe
        /// on its own — see <see cref="GetToolStats"/> for the locked public entry point.
        /// </summary>
        private void BumpToolStat(RequestLogEntry entry)
        {
            if (entry.Kind != RequestKind.Local) return;
            if (string.IsNullOrEmpty(entry.Tool)) return;

            if (!_toolStats.TryGetValue(entry.Tool, out var stat))
            {
                stat = new ToolStat { Name = entry.Tool };
                _toolStats[entry.Tool] = stat;
            }
            stat.CallCount++;
            stat.TotalDurationMs += entry.DurationMs;
            if (entry.IsError) stat.ErrorCount++;
        }

        /// <summary>
        /// Returns a snapshot of cumulative per-tool aggregates, sorted by total duration descending.
        /// Values are copied so callers can read them safely without racing concurrent Commits.
        /// </summary>
        public List<ToolStat> GetToolStats()
        {
            lock (_lock)
            {
                var result = new List<ToolStat>(_toolStats.Values.Count);
                foreach (var stat in _toolStats.Values)
                {
                    result.Add(new ToolStat
                    {
                        Name = stat.Name,
                        CallCount = stat.CallCount,
                        TotalDurationMs = stat.TotalDurationMs,
                        ErrorCount = stat.ErrorCount
                    });
                }
                result.Sort((a, b) => b.TotalDurationMs.CompareTo(a.TotalDurationMs));
                return result;
            }
        }

        /// <summary>
        /// Subscribes to committed entries. The returned token unsubscribes; entries are also
        /// pruned lazily when a subscriber reports dead (see <see cref="SseSink"/>).
        /// </summary>
        public IDisposable Subscribe(Action<RequestLogEntry> onEvent)
        {
            lock (_lock)
            {
                _subscribers.Add(onEvent);
            }
            return new Unsubscriber(this, onEvent);
        }

        private static bool IsDeadSubscriber(Action<RequestLogEntry> subscriber)
        {
            return subscriber.Target is IDisposable sink && sink is SseSink s && s.IsDead;
        }

        public static string Truncate(string text, int maxLength)
        {
            if (text == null) return null;
            if (text.Length <= maxLength) return text;
            return text.Substring(0, maxLength);
        }

        private sealed class Unsubscriber : IDisposable
        {
            private readonly RequestLogBuffer _buffer;
            private readonly Action<RequestLogEntry> _subscriber;
            private bool _disposed;

            public Unsubscriber(RequestLogBuffer buffer, Action<RequestLogEntry> subscriber)
            {
                _buffer = buffer;
                _subscriber = subscriber;
            }

            public void Dispose()
            {
                if (_disposed) return;
                lock (_buffer._lock)
                {
                    _buffer._subscribers.Remove(_subscriber);
                }
                _disposed = true;
            }
        }
    }
}
