#!/bin/bash
set -euo pipefail

PLUGIN_NAME="ReSharperMcp"
RIDER_VERSION="${1:-}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

source "$SCRIPT_DIR/scripts/resolve-java-home.sh"

# Auto-detect Rider version if not specified
if [ -z "$RIDER_VERSION" ]; then
    # Try macOS path first, then Linux
    RIDER_DIR=$(ls -d "$HOME/Library/Application Support/JetBrains/Rider"* 2>/dev/null | sort -V | tail -1)
    if [ -z "$RIDER_DIR" ]; then
        RIDER_DIR=$(ls -d ~/.local/share/JetBrains/Rider* 2>/dev/null | sort -V | tail -1)
    fi
    if [ -z "$RIDER_DIR" ]; then
        echo "Error: Could not find Rider plugin directory."
        echo "Usage: $0 [Rider2025.3]"
        exit 1
    fi
else
    # Check macOS path first, then Linux
    if [ -d "$HOME/Library/Application Support/JetBrains/$RIDER_VERSION" ]; then
        RIDER_DIR="$HOME/Library/Application Support/JetBrains/$RIDER_VERSION"
    else
        RIDER_DIR="$HOME/.local/share/JetBrains/$RIDER_VERSION"
    fi
fi

PLUGIN_DIR="$RIDER_DIR/plugins/$PLUGIN_NAME"

if ! JAVA_HOME="$(resolve_java_home)"; then
    echo "Error: JDK 21 not found in JAVA_HOME or Android Studio."
    exit 1
fi
export JAVA_HOME

if command -v dotnet >/dev/null 2>&1; then
    DOTNET_COMMAND="dotnet"
elif command -v dotnet.exe >/dev/null 2>&1; then
    DOTNET_COMMAND="dotnet.exe"
else
    echo "Error: dotnet or dotnet.exe was not found."
    exit 1
fi

DOTNET_PROJECT="$SCRIPT_DIR/src/ReSharperMcp/ReSharperMcp.csproj"
if [ "$DOTNET_COMMAND" = "dotnet.exe" ]; then
    if command -v wslpath >/dev/null 2>&1; then
        DOTNET_PROJECT="$(wslpath -w "$DOTNET_PROJECT")"
    elif command -v cygpath >/dev/null 2>&1; then
        DOTNET_PROJECT="$(cygpath -w "$DOTNET_PROJECT")"
    fi
fi

echo "Building backend..."
"$DOTNET_COMMAND" build "$DOTNET_PROJECT" -c Release -v quiet

echo "Building frontend..."
run_gradle_jar "$SCRIPT_DIR/rider-plugin"

echo "Installing to: $PLUGIN_DIR"
mkdir -p "$PLUGIN_DIR/dotnet"
mkdir -p "$PLUGIN_DIR/lib"
cp "$SCRIPT_DIR/src/ReSharperMcp/bin/Release/net472/$PLUGIN_NAME.dll" "$PLUGIN_DIR/dotnet/"
cp "$SCRIPT_DIR/src/ReSharperMcp/bin/Release/net472/$PLUGIN_NAME.pdb" "$PLUGIN_DIR/dotnet/" 2>/dev/null || true
cp "$SCRIPT_DIR/rider-plugin/build/libs/$PLUGIN_NAME.jar" "$PLUGIN_DIR/lib/"

echo ""
echo "Done! Plugin installed to $PLUGIN_DIR"
echo "  lib/  -> $PLUGIN_NAME.jar (frontend: Kotlin classes + plugin descriptor)"
echo "  dotnet/ -> $PLUGIN_NAME.dll (backend component)"
echo ""
echo "Restart Rider for the plugin to take effect."
echo "The MCP server will start on http://127.0.0.1:23741/ when you open a solution."
echo "Set RESHARPER_MCP_PORT env var to use a different port."
