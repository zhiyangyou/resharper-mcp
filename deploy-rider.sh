#!/bin/bash
# Deploy the ReSharper MCP plugin to Rider.
#
# Deployment = stop all running Rider processes (so the plugin files are not
# locked / picked up mid-copy), then build + install via install-rider.sh.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Kill every process that belongs to the Rider app bundle — the launcher, the IDE java
# process, ReSharper.Backend, Roslyn worker, cef helpers, etc. Their command lines all
# contain "Rider.app". A Gradle daemon may also match (it runs off Rider's bundled JBR when
# JAVA_HOME points there) — it is harmless and self-restarts, but we still exclude it to keep
# the shutdown targeted.
echo "Stopping all Rider processes..."
for pid in $(pgrep -f "Rider.app"); do
    if [ -n "$pid" ] && ! ps -p "$pid" -o command= | grep -q "org.gradle.launcher.daemon"; then
        kill "$pid" 2>/dev/null || true
    fi
done
# Give the app a moment to shut down gracefully, then force-kill anything still alive
# (e.g. backend workers that ignore SIGTERM) so no file locks survive the copy step.
sleep 3
for pid in $(pgrep -f "Rider.app"); do
    if [ -n "$pid" ] && ! ps -p "$pid" -o command= | grep -q "org.gradle.launcher.daemon"; then
        kill -9 "$pid" 2>/dev/null || true
    fi
done

echo "Running install-rider.sh..."
"$SCRIPT_DIR/install-rider.sh" "$@"

echo ""
echo "Deploy complete. Start Rider — the MCP server will come up on"
echo "http://127.0.0.1:23741/ when you open a solution."
