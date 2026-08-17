# ChangeLog

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
