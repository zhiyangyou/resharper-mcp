using System;
using System.IO;
using System.Text;
using Newtonsoft.Json;

namespace ReSharperMcp
{
    /// <summary>
    /// Writes SSE <c>event: log</c> frames to a single HTTP connection. One instance per active
    /// GET stream, pushed to from <see cref="RequestLogBuffer.Commit"/> on a request thread.
    /// Own lock guards against concurrent writes from the keepalive loop and the buffer notifier.
    /// </summary>
    internal sealed class SseSink : IDisposable
    {
        private readonly object _lock = new object();
        private readonly StreamWriter _writer;
        private volatile bool _dead;

        public bool IsDead => _dead;

        public SseSink(StreamWriter writer)
        {
            _writer = writer;
        }

        /// <summary>
        /// Writes one log event. Best effort: a failed write marks the sink dead so the buffer
        /// prunes it on the next commit and the SSE loop can exit.
        /// </summary>
        public void Push(RequestLogEntry entry)
        {
            if (_dead) return;
            lock (_lock)
            {
                if (_dead) return;
                try
                {
                    _writer.Write("event: log\n");
                    _writer.Write("data: " + entry.ToJObject().ToString(Formatting.None) + "\n\n");
                    _writer.Flush();
                }
                catch
                {
                    _dead = true;
                }
            }
        }

        /// <summary>
        /// Writes a keepalive comment; shares the lock with <see cref="Push"/> so comment frames
        /// never interleave with data frames.
        /// </summary>
        public void WriteKeepalive()
        {
            if (_dead) return;
            lock (_lock)
            {
                if (_dead) return;
                try
                {
                    _writer.Write(": keepalive\n\n");
                    _writer.Flush();
                }
                catch
                {
                    _dead = true;
                }
            }
        }

        public void Dispose()
        {
            _dead = true;
            lock (_lock)
            {
                try { _writer.Flush(); } catch { }
            }
        }
    }
}
