[CmdletBinding()]
param(
    [string]$RiderPluginsDir,
    [switch]$Force,
    [int]$ShutdownTimeoutSeconds = 30
)

$ErrorActionPreference = 'Stop'
$pluginName = 'ReSharperMcp'
$scriptRoot = $PSScriptRoot
$buildScript = Join-Path $scriptRoot 'build-plugin.ps1'

function Resolve-PluginsDirectory {
    param([string]$ExplicitPath)
    if ($ExplicitPath) {
        if (-not (Test-Path -LiteralPath $ExplicitPath)) {
            New-Item -ItemType Directory -Path $ExplicitPath -Force | Out-Null
        }
        return (Resolve-Path -LiteralPath $ExplicitPath).Path
    }

    $appData = $env:APPDATA
    if (-not $appData) {
        throw '无法确定 APPDATA，请显式传入 -RiderPluginsDir。'
    }
    $matches = @(Get-ChildItem -LiteralPath (Join-Path $appData 'JetBrains') -Directory -Filter 'Rider*' -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName 'plugins' } |
        Where-Object { Test-Path -LiteralPath $_ })
    if ($matches.Count -ne 1) {
        throw "自动发现到 $($matches.Count) 个 Rider 插件目录；请显式传入 -RiderPluginsDir。"
    }
    return (Resolve-Path -LiteralPath $matches[0]).Path
}

function Get-RiderProcesses {
    Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.ProcessName -match '^rider(64)?$' }
}

function Stop-RiderGracefully {
    param([int]$TimeoutSeconds, [switch]$AllowForce)
    $processes = @(Get-RiderProcesses)
    $executable = $processes | Where-Object { $_.Path } | Select-Object -First 1 -ExpandProperty Path
    foreach ($process in $processes) {
        if ($process.MainWindowHandle -ne 0) {
            [void]$process.CloseMainWindow()
        }
    }
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline -and @(Get-RiderProcesses).Count -gt 0) {
        Start-Sleep -Seconds 1
    }
    $remaining = @(Get-RiderProcesses)
    if ($remaining.Count -gt 0) {
        if (-not $AllowForce) {
            throw 'Rider 未能优雅退出；如确认可强制结束，请使用 -Force。'
        }
        $remaining | Stop-Process -Force
    }
    return $executable
}

function Resolve-RiderExecutable {
    param([string]$RunningPath)
    if ($RunningPath -and (Test-Path -LiteralPath $RunningPath)) {
        return $RunningPath
    }
    $installRoot = Join-Path $env:LOCALAPPDATA 'JetBrains/Installations'
    $candidates = if (Test-Path -LiteralPath $installRoot) {
        @(Get-ChildItem -LiteralPath $installRoot -Directory -Filter 'Rider*' -ErrorAction SilentlyContinue |
            ForEach-Object { Join-Path $_.FullName 'bin/rider64.exe' } |
            Where-Object { Test-Path -LiteralPath $_ })
    } else { @() }
    if ($candidates.Count -eq 1) {
        return $candidates[0]
    }
    return $null
}

Write-Host '先构建插件包...'
& $buildScript
if ($LASTEXITCODE -ne 0) {
    throw 'build-plugin.ps1 执行失败。'
}

$pluginsDirectory = Resolve-PluginsDirectory $RiderPluginsDir
$runningExecutable = Stop-RiderGracefully -TimeoutSeconds $ShutdownTimeoutSeconds -AllowForce:$Force
$riderExecutable = Resolve-RiderExecutable $runningExecutable
$zipPath = Join-Path $scriptRoot 'Build Output/ReSharperMcp.zip'
$tempPath = Join-Path ([System.IO.Path]::GetTempPath()) ("ReSharperMcp-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tempPath -Force | Out-Null
try {
    Expand-Archive -LiteralPath $zipPath -DestinationPath $tempPath -Force
    $sourcePlugin = Join-Path $tempPath $pluginName
    $targetPlugin = Join-Path $pluginsDirectory $pluginName
    if (Test-Path -LiteralPath $targetPlugin) {
        $backup = "$targetPlugin.bak.$([DateTime]::Now.ToString('yyyyMMddHHmmss'))"
        Move-Item -LiteralPath $targetPlugin -Destination $backup
    }
    New-Item -ItemType Directory -Path $targetPlugin -Force | Out-Null
    Copy-Item -Path (Join-Path $sourcePlugin '*') -Destination $targetPlugin -Recurse -Force
}
finally {
    if (Test-Path -LiteralPath $tempPath) {
        Remove-Item -LiteralPath $tempPath -Recurse -Force
    }
}

if (-not $riderExecutable) {
    throw '未找到唯一 Rider 可执行文件；插件已安装，但无法完成自动重启和 MCP 验证。'
}

Start-Process -FilePath $riderExecutable | Out-Null
$port = if ($env:RESHARPER_MCP_PORT) { [int]$env:RESHARPER_MCP_PORT } else { 23741 }
$body = '{"jsonrpc":"2.0","id":1,"method":"internal/status","params":{}}'
$deadline = (Get-Date).AddSeconds(60)
$verified = $false
while ((Get-Date) -lt $deadline) {
    try {
        $response = Invoke-RestMethod -Uri "http://127.0.0.1:$port/" -Method Post -Body $body -ContentType 'application/json' -TimeoutSec 3
        if ($response.result) {
            $verified = $true
            break
        }
    } catch {
        Start-Sleep -Seconds 2
    }
}
if (-not $verified) {
    throw "Rider 已重启，但 MCP internal/status 未在端口 $port 可用；请打开 Solution 后重试验证。"
}
Write-Host "插件已安装并完成 Rider/MCP 验证: $pluginsDirectory"
