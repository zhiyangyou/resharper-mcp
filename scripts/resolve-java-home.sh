#!/bin/bash

is_jdk21() {
    local java_home="$1"
    local java_command="$java_home/bin/java"

    if [ ! -x "$java_command" ] && [ -x "$java_home/bin/java.exe" ]; then
        java_command="$java_home/bin/java.exe"
    fi

    if [ ! -x "$java_command" ]; then
        return 1
    fi

    "$java_command" -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | grep -q '^21$'
}

resolve_java_home() {
    local candidate

    if [ -n "${JAVA_HOME:-}" ] && is_jdk21 "$JAVA_HOME"; then
        printf '%s\n' "$JAVA_HOME"
        return 0
    fi

    local candidates=(
        "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
        "/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
        "$HOME/Applications/Android Studio.app/Contents/jbr/Contents/Home"
        "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
        "$HOME/Library/Application Support/Google/AndroidStudio/jbr"
        "$HOME/AppData/Local/Programs/Android Studio/jbr"
    )

    for candidate in /mnt/c/Users/*/AppData/Local/Programs/Android\ Studio/jbr; do
        candidates+=("$candidate")
    done

    for candidate in "${candidates[@]}"; do
        if is_jdk21 "$candidate"; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done

    return 1
}

to_host_path() {
    local path="$1"

    if command -v wslpath >/dev/null 2>&1; then
        wslpath -w "$path"
    elif command -v cygpath >/dev/null 2>&1; then
        cygpath -w "$path"
    else
        printf '%s\n' "$path"
    fi
}

run_gradle_jar() {
    local project_dir="$1"

    if [ -x "$JAVA_HOME/bin/java.exe" ] && command -v cmd.exe >/dev/null 2>&1 && command -v wslpath >/dev/null 2>&1; then
        local windows_java_home
        local windows_project_dir
        windows_java_home="$(to_host_path "$JAVA_HOME")"
        windows_project_dir="$(to_host_path "$project_dir")"
        cmd.exe /c "set JAVA_HOME=$windows_java_home&& cd /d $windows_project_dir&& gradlew.bat jar --quiet"
        return $?
    fi

    (cd "$project_dir" && ./gradlew jar --quiet)
}

create_plugin_zip() {
    local staging_dir="$1"
    local plugin_name="$2"
    local zip_file="$3"

    if command -v zip >/dev/null 2>&1; then
        (cd "$staging_dir" && zip -r "$zip_file" "$plugin_name/" -q)
        return $?
    fi

    if command -v powershell.exe >/dev/null 2>&1 && command -v wslpath >/dev/null 2>&1; then
        local windows_staging_dir
        local windows_zip_file
        windows_staging_dir="$(to_host_path "$staging_dir")"
        windows_zip_file="$(to_host_path "$zip_file")"
        powershell.exe -NoProfile -Command "Compress-Archive -LiteralPath '$windows_staging_dir\\$plugin_name' -DestinationPath '$windows_zip_file' -Force"
        return $?
    fi

    echo "Error: zip or PowerShell Compress-Archive is required."
    return 1
}
