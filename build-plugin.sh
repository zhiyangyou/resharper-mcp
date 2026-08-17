#!/bin/bash
set -euo pipefail

PLUGIN_NAME="ReSharperMcp"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
STAGING_DIR="$SCRIPT_DIR/.build-staging"
OUTPUT_DIR="$SCRIPT_DIR/Build Output"
ZIP_FILE="$OUTPUT_DIR/$PLUGIN_NAME.zip"
PLUGIN_XML="$SCRIPT_DIR/rider-plugin/src/main/resources/META-INF/plugin.xml"
CHANGELOG_SOURCE="$SCRIPT_DIR/CHANGELOG.md"

source "$SCRIPT_DIR/scripts/resolve-java-home.sh"

if ! JAVA_HOME="$(resolve_java_home)"; then
    echo "Error: JDK 21 not found in JAVA_HOME or Android Studio."
    exit 1
fi
export JAVA_HOME

PLUGIN_VERSION="$(sed -nE 's/.*<version>([^<]+)<\/version>.*/\1/p' "$PLUGIN_XML" | head -n 1)"
if [ -z "$PLUGIN_VERSION" ]; then
    echo "Error: Could not determine plugin version from $PLUGIN_XML."
    exit 1
fi

if [ ! -f "$CHANGELOG_SOURCE" ] || ! grep -Fq "## [$PLUGIN_VERSION]" "$CHANGELOG_SOURCE"; then
    echo "Error: $CHANGELOG_SOURCE must contain a section for version $PLUGIN_VERSION."
    exit 1
fi

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

PLUGIN_JAR="$SCRIPT_DIR/rider-plugin/build/libs/$PLUGIN_NAME.jar"
PLUGIN_DESCRIPTOR="$SCRIPT_DIR/rider-plugin/build/resources/main/META-INF/plugin.xml"
if [ ! -f "$PLUGIN_DESCRIPTOR" ]; then
    echo "Error: Plugin descriptor was not generated: $PLUGIN_DESCRIPTOR"
    exit 1
fi

echo "Adding plugin descriptor..."
JAR_COMMAND="$JAVA_HOME/bin/jar"
if [ ! -x "$JAR_COMMAND" ] && [ -x "$JAVA_HOME/bin/jar.exe" ]; then
    JAR_COMMAND="$JAVA_HOME/bin/jar.exe"
fi
JAR_FILE="$PLUGIN_JAR"
RESOURCE_ROOT="$SCRIPT_DIR/rider-plugin/build/resources/main"
if [[ "$JAR_COMMAND" == *.exe ]]; then
    JAR_FILE="$(to_host_path "$JAR_FILE")"
    RESOURCE_ROOT="$(to_host_path "$RESOURCE_ROOT")"
fi
"$JAR_COMMAND" uf "$JAR_FILE" -C "$RESOURCE_ROOT" META-INF/plugin.xml

echo "Assembling plugin ZIP..."
rm -rf "$STAGING_DIR"
mkdir -p "$STAGING_DIR/$PLUGIN_NAME/lib"
mkdir -p "$STAGING_DIR/$PLUGIN_NAME/dotnet"
mkdir -p "$OUTPUT_DIR"

{
    printf '# ReSharper MCP Server\n\n'
    printf '**Version:** `%s`\n\n' "$PLUGIN_VERSION"
    sed '1{/^# ReSharper MCP Server$/d;}' "$SCRIPT_DIR/README.md"
} > "$OUTPUT_DIR/README.md"
cp "$CHANGELOG_SOURCE" "$OUTPUT_DIR/CHANGELOG.md"

cp "$PLUGIN_JAR" "$STAGING_DIR/$PLUGIN_NAME/lib/"
cp "$SCRIPT_DIR/src/ReSharperMcp/bin/Release/net472/$PLUGIN_NAME.dll" "$STAGING_DIR/$PLUGIN_NAME/dotnet/"

cd "$STAGING_DIR"
rm -f "$ZIP_FILE"
create_plugin_zip "$STAGING_DIR" "$PLUGIN_NAME" "$ZIP_FILE"

# Cleanup
rm -rf "$STAGING_DIR"

echo ""
echo "Done! Plugin ZIP created: $ZIP_FILE"
echo "Upload it at: https://plugins.jetbrains.com/plugin/add"
