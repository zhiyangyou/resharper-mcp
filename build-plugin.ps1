[CmdletBinding()]
param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'Build Output')
)

$ErrorActionPreference = 'Stop'
$pluginName = 'ReSharperMcp'
$pluginXmlPath = Join-Path $PSScriptRoot 'rider-plugin/src/main/resources/META-INF/plugin.xml'
$changelogPath = Join-Path $PSScriptRoot 'CHANGELOG.md'
$stagingPath = Join-Path $PSScriptRoot '.build-staging'

function Resolve-JavaHome {
    $candidates = [System.Collections.Generic.List[string]]::new()
    if ($env:JAVA_HOME) {
        $candidates.Add($env:JAVA_HOME)
    }

    $candidateRoots = @(
        (Join-Path ${env:ProgramFiles} 'JetBrains'),
        (Join-Path ${env:LOCALAPPDATA} 'JetBrains/Installations'),
        (Join-Path ${env:USERPROFILE} '.gradle/caches/8.13/transforms')
    )
    foreach ($root in $candidateRoots) {
        if (Test-Path -LiteralPath $root) {
            Get-ChildItem -LiteralPath $root -Directory -Recurse -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -eq 'jbr' } |
                ForEach-Object { $candidates.Add($_.FullName) }
        }
    }

    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if (Test-Path -LiteralPath (Join-Path $candidate 'bin/java.exe')) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    throw '未找到 JDK 21。请设置 JAVA_HOME，或安装 Rider/JDK 21 后重试。'
}

function Invoke-Checked {
    param([string]$FilePath, [string[]]$Arguments)
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "命令失败 ($LASTEXITCODE): $FilePath $($Arguments -join ' ')"
    }
}

$javaHome = Resolve-JavaHome
$env:JAVA_HOME = $javaHome
$pluginXml = Get-Content -LiteralPath $pluginXmlPath -Raw
$versionMatch = [regex]::Match($pluginXml, '<version>([^<]+)</version>')
if (-not $versionMatch.Success) {
    throw "无法从 $pluginXmlPath 读取插件版本。"
}
$pluginVersion = $versionMatch.Groups[1].Value.Trim()
if (-not (Test-Path -LiteralPath $changelogPath) -or -not (Select-String -LiteralPath $changelogPath -Pattern "^## \[$([regex]::Escape($pluginVersion))\]" -Quiet)) {
    throw "CHANGELOG.md 必须包含版本 $pluginVersion 的条目。"
}

$dotnet = (Get-Command dotnet -ErrorAction SilentlyContinue).Source
if (-not $dotnet) {
    throw '未找到 dotnet。'
}
$backendProject = Join-Path $PSScriptRoot 'src/ReSharperMcp/ReSharperMcp.csproj'
$gradle = Join-Path $PSScriptRoot 'rider-plugin/gradlew.bat'

Write-Host '构建 .NET 后端...'
Invoke-Checked $dotnet @('build', $backendProject, '-c', 'Release', '-v', 'minimal')
Write-Host '构建 Kotlin 插件...'
Push-Location (Split-Path -Parent $gradle)
try {
    Invoke-Checked $gradle @('jar', '--no-daemon', '--console=plain')
}
finally {
    Pop-Location
}

$pluginJar = Join-Path $PSScriptRoot 'rider-plugin/build/libs/ReSharperMcp.jar'
$pluginDescriptor = Join-Path $PSScriptRoot 'rider-plugin/build/resources/main/META-INF/plugin.xml'
$resourceRoot = Join-Path $PSScriptRoot 'rider-plugin/build/resources/main'
if (-not (Test-Path -LiteralPath $pluginJar)) {
    throw "未生成插件 JAR: $pluginJar"
}
if (-not (Test-Path -LiteralPath $pluginDescriptor)) {
    throw "未生成插件描述文件: $pluginDescriptor"
}

$jar = Join-Path $javaHome 'bin/jar.exe'
if (-not (Test-Path -LiteralPath $jar)) {
    $jarCandidates = @()
    $jarCommand = Get-Command jar.exe -ErrorAction SilentlyContinue
    if ($jarCommand) {
        $jarCandidates += $jarCommand.Source
    }
    if ($env:ProgramFiles) {
        $jarCandidates += @(Get-ChildItem -LiteralPath (Join-Path $env:ProgramFiles 'Java') -Filter 'jar.exe' -Recurse -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty FullName)
    }
    $jar = $jarCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
}
if (-not (Test-Path -LiteralPath $jar)) {
    throw '未找到 jar 工具，无法把 plugin.xml 写入插件 JAR。'
}
Invoke-Checked $jar @('uf', $pluginJar, '-C', $resourceRoot, 'META-INF/plugin.xml')

if (Test-Path -LiteralPath $stagingPath) {
    Remove-Item -LiteralPath $stagingPath -Recurse -Force
}
$stagingPlugin = Join-Path $stagingPath $pluginName
New-Item -ItemType Directory -Path (Join-Path $stagingPlugin 'lib') -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $stagingPlugin 'dotnet') -Force | Out-Null
Copy-Item -LiteralPath $pluginJar -Destination (Join-Path $stagingPlugin 'lib')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot "src/ReSharperMcp/bin/Release/net472/$pluginName.dll") -Destination (Join-Path $stagingPlugin 'dotnet')

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$readmeOutput = Join-Path $OutputDirectory 'README.md'
@("# ReSharper MCP Server", '', "**Version:** ``$pluginVersion``", '') + (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'README.md') | Select-Object -Skip 1) |
    Set-Content -LiteralPath $readmeOutput -Encoding UTF8
Copy-Item -LiteralPath $changelogPath -Destination (Join-Path $OutputDirectory 'CHANGELOG.md') -Force

$zipPath = Join-Path $OutputDirectory "$pluginName.zip"
if (Test-Path -LiteralPath $zipPath) {
    Remove-Item -LiteralPath $zipPath -Force
}
Compress-Archive -Path $stagingPlugin -DestinationPath $zipPath -CompressionLevel Optimal
Remove-Item -LiteralPath $stagingPath -Recurse -Force

Write-Host "已生成 $zipPath (版本 $pluginVersion)"
