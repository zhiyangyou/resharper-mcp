using System;
using System.Collections.Generic;
using System.Linq;
using Newtonsoft.Json.Linq;

namespace ReSharperMcp
{
    /// <summary>
    /// A single MCP client session, identified by the MCP <c>initialize</c> <c>clientInfo</c>
    /// (name + version) plus the connecting remote address.
    /// </summary>
    public sealed class ClientSession
    {
        public string ClientName;
        public string ClientVersion;
        public string RemoteAddress;
        public long FirstSeenMs;      // UTC ms of the first initialize
        public long LastActiveMs;     // UTC ms of most recent activity
        public long RequestCount;     // number of requests attributed to this client
        public bool Online;           // false once LastActiveMs is older than the inactivity timeout
        public long OfflineSinceMs;   // UTC ms when marked offline, 0 while online

        public JObject ToJObject()
        {
            return new JObject
            {
                ["clientName"] = ClientName,
                ["clientVersion"] = ClientVersion,
                ["remoteAddress"] = RemoteAddress,
                ["firstSeen"] = FirstSeenMs,
                ["lastActive"] = LastActiveMs,
                ["requestCount"] = RequestCount,
                ["online"] = Online,
                ["offlineSince"] = OfflineSinceMs
            };
        }
    }

    /// <summary>
    /// Thread-safe registry of connected MCP clients, owned at the shell-component level so it
    /// survives promotion. Clients are upserted by (name, version, remote address); activity is
    /// lazily evaluated against a timeout so no explicit disconnect detection is required.
    /// </summary>
    public sealed class ClientSessionTracker
    {
        public const long InactivityTimeoutMs = 30_000;   // 30s without activity → offline

        private readonly object _lock = new object();
        private readonly Dictionary<string, ClientSession> _sessions = new Dictionary<string, ClientSession>();

        /// <summary>
        /// Records activity for the given client. Upserts on first sight; bumps request count and
        /// last-active stamp on subsequent requests. Called for every JSON-RPC request.
        /// </summary>
        public void RecordActivity(string clientName, string clientVersion, string remoteAddress)
        {
            if (string.IsNullOrEmpty(clientName)) return;
            var key = BuildKey(clientName, clientVersion, remoteAddress);
            var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

            lock (_lock)
            {
                if (_sessions.TryGetValue(key, out var session))
                {
                    session.LastActiveMs = now;
                    session.RequestCount++;
                    session.Online = true;
                    session.OfflineSinceMs = 0;
                }
                else
                {
                    _sessions[key] = new ClientSession
                    {
                        ClientName = clientName,
                        ClientVersion = clientVersion ?? "",
                        RemoteAddress = remoteAddress ?? "",
                        FirstSeenMs = now,
                        LastActiveMs = now,
                        RequestCount = 1,
                        Online = true
                    };
                }
            }
        }

        /// <summary>
        /// Bumps activity for any tracked session from the given remote address. Used for
        /// non-initialize requests (which carry no clientInfo) so they refresh the client's
        /// last-active stamp without creating spurious "unknown" sessions.
        /// When multiple sessions share an address (common on localhost), bumps the most
        /// recently active one to keep online state correct.
        /// </summary>
        public void RecordActivityByAddress(string remoteAddress)
        {
            if (string.IsNullOrEmpty(remoteAddress)) return;
            var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

            lock (_lock)
            {
                ClientSession mostRecent = null;
                foreach (var session in _sessions.Values)
                {
                    if (!string.Equals(session.RemoteAddress, remoteAddress, StringComparison.Ordinal)) continue;
                    if (mostRecent == null || session.LastActiveMs > mostRecent.LastActiveMs)
                        mostRecent = session;
                }
                if (mostRecent != null)
                {
                    mostRecent.LastActiveMs = now;
                    mostRecent.RequestCount++;
                    mostRecent.Online = true;
                    mostRecent.OfflineSinceMs = 0;
                }
            }
        }

        /// <summary>
        /// Returns a snapshot of all tracked sessions with online/offline resolved against the
        /// inactivity timeout. Values are copied under the lock so callers can read them safely
        /// without racing concurrent RecordActivity writes.
        /// </summary>
        public List<ClientSession> Snapshot()
        {
            var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            lock (_lock)
            {
                var result = new List<ClientSession>(_sessions.Count);
                foreach (var session in _sessions.Values)
                {
                    if (session.Online && now - session.LastActiveMs > InactivityTimeoutMs)
                    {
                        session.Online = false;
                        session.OfflineSinceMs = now;
                    }
                    result.Add(new ClientSession
                    {
                        ClientName = session.ClientName,
                        ClientVersion = session.ClientVersion,
                        RemoteAddress = session.RemoteAddress,
                        FirstSeenMs = session.FirstSeenMs,
                        LastActiveMs = session.LastActiveMs,
                        RequestCount = session.RequestCount,
                        Online = session.Online,
                        OfflineSinceMs = session.OfflineSinceMs
                    });
                }
                return result;
            }
        }

        public int OnlineCount()
        {
            var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            lock (_lock)
            {
                return _sessions.Values.Count(s => s.Online || now - s.LastActiveMs <= InactivityTimeoutMs);
            }
        }

        private static string BuildKey(string name, string version, string address)
        {
            return $"{name}|{version ?? ""}|{address ?? ""}";
        }
    }
}
