# ChangeLog

## [0.11.2] - 2026-08-18

- Fixed Peer monitor log latency by keeping the cursor at the last consumed log entry instead of the next-index watermark.
- Peer SSE connections now select the backend that locally owns the current Solution and deduplicate replayed log entries.
- Added regression coverage for cursor handling and Primary/Peer monitor endpoint selection.

## [0.11.1] - 2026-08-18

- Fixed deterministic routing for same-name solutions by using normalized full-path IDs across local and peer registries.
- Preserved solution IDs through primary-to-peer forwarding, peer re-resolution, request logs, monitoring, and UI labels.
- Added owner-safe peer deregistration, complete stale-port cleanup, C#/Kotlin regression tests, and Windows-native build/deploy scripts.

## [0.11.0] - 2026-08-17

- Stable solution id: `list_solutions` now returns an `id` (the full .sln path) for every open solution — pass it as `solutionName` to target solutions with duplicate names precisely and unambiguously.
- Robust same-name routing: forwarding a tool call to a peer now carries the resolved solution id, so peers with multiple local solutions resolve correctly instead of failing.
- Primary/peer self-healing: peers re-register their solutions with the primary on a watchdog cadence, so routing recovers automatically after a primary restart or takeover.

## [0.10.0] - 2026-08-17

- Added the `apply_suggestions` tool for applying inspection quick-fixes across a whole file.
- Added inspection filtering, preview mode, batching, and support for targeting multiple file paths.

## [0.9.0]

- Added navigation tools for symbol source, call hierarchy, and type hierarchy.
- Added diagnostics, quick-fix discovery, completion, semantic rename, member generation, and quick-fix application tools.

## [0.8.0]

- Added the `flow` tool for control-flow summaries with branches, loops, calls, and inlined targets.

## [0.7.0]

- Added batch mode for symbol, file, namespace, and search operations.
- Added result limits for widely-used symbols.

## [0.6.1]

- Replaced deprecated Java URL construction with Java 20+ compatible URI-based construction.

## [0.6.0]

- Added the `format_file` tool and improved multi-instance primary/peer recovery.
- Added watchdog health checks, smarter file resolution, and more robust error handling.

## [0.5.1]

- Added Streamable HTTP transport support and MCP protocol version negotiation.
- Added session handling, SSE GET support, DELETE termination, and correct notification responses.

## [0.5.0]

- Added the `fix_usings` write tool with ambiguity reporting and interactive resolution.

## [0.4.1]

- Improved symbol-by-name resolution for partially qualified names.

## [0.4.0]

- Added compact text output and standardized symbol signature formatting.

## [0.3.1]

- Improved solution targeting with unique path-segment hints and richer snippets.

## [0.3.0]

- Added multi-solution support through the primary/peer architecture.

## [0.2.1]

- Improved performance, timeout handling, path resolution, and symbol resolution behavior.

## [0.2.0]

- Improved usages, implementations, symbol information, search, namespace browsing, and generated-source handling.

## [0.1.0]

- Initial release with nine MCP tools and language-agnostic ReSharper PSI support.
