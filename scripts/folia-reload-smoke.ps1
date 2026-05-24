param(
    [string]$LiveServerRoot = "C:\Users\rajbe\OneDrive\Surface Pro Desktop\Replica Server",
    [string]$FoliaJarPath = "C:\Users\rajbe\Downloads\folia-26.1.2-8.jar",
    [string]$PluginJarRoot = "C:\Users\rajbe\Downloads\Plugins\Plugins",
    [string]$StageRoot = ".codex-staging\folia-reload-smoke",
    [string[]]$PluginJars = @("ViaVersion-5.9.1.jar", "ViaBackwards-5.9.1.jar", "ViaRewind-4.1.1.jar"),
    [string[]]$Commands = @("viaversion reload", "version ViaVersion"),
    [string]$PluginJarList = "",
    [string]$CommandList = "",
    [string]$PlugManIgnoredPlugins = "",
    [switch]$CopyRuntimeDirs,
    [switch]$UseRcon,
    [int]$StartupTimeoutSeconds = 180,
    [int]$AfterCommandSeconds = 12,
    [int]$RconPort = 25576,
    [string]$RconPassword = "election-smoke",
    [string]$DebugLogName = "staging-debug.log"
)

$ErrorActionPreference = "Stop"

function Write-SmokeLog {
    param(
        [string]$Message,
        [string]$Level = "INFO"
    )

    $line = "[{0}] [{1}] {2}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss.fff zzz"), $Level, $Message
    if ($script:DebugLogPath) {
        Add-Content -LiteralPath $script:DebugLogPath -Value $line -Encoding UTF8
    }
}

function Get-InterestingLines {
    param([string[]]$Lines)

    $Lines | Where-Object {
        $_ -match "(?i)rcon|viaversion|viarewind|viabackwards|plugman|exception|error|warn|failed|reload|Done \("
    }
}

function Copy-RequiredFile {
    param(
        [string]$Source,
        [string]$Destination,
        [string]$Label
    )

    if (!(Test-Path -LiteralPath $Source)) {
        Write-SmokeLog "$Label missing at $Source" "ERROR"
        throw "$Label missing at $Source"
    }

    Copy-Item -LiteralPath $Source -Destination $Destination -Force
    $size = (Get-Item -LiteralPath $Destination).Length
    Write-SmokeLog "Copied $Label from $Source to $Destination ($size bytes)"
}

$repoRoot = (Resolve-Path ".").Path
$stagePath = Join-Path $repoRoot $StageRoot
$pluginsPath = Join-Path $stagePath "plugins"
$script:DebugLogPath = Join-Path $stagePath $DebugLogName

if ($PluginJarList.Trim().Length -gt 0) {
    $PluginJars = $PluginJarList -split ";" | ForEach-Object { $_.Trim() } | Where-Object { $_.Length -gt 0 }
}
if ($CommandList.Trim().Length -gt 0) {
    $Commands = $CommandList -split ";" | ForEach-Object { $_.Trim() } | Where-Object { $_.Length -gt 0 }
}

if (Test-Path -LiteralPath $stagePath) {
    Remove-Item -LiteralPath $stagePath -Recurse -Force
}
New-Item -ItemType Directory -Path $pluginsPath -Force | Out-Null
New-Item -ItemType File -Path $script:DebugLogPath -Force | Out-Null

Write-SmokeLog "Starting Folia reload smoke run"
Write-SmokeLog "Repository root: $repoRoot"
Write-SmokeLog "Stage path: $stagePath"
Write-SmokeLog "Mode: $(if ($UseRcon) { 'RCON' } else { 'STDIN' })"
Write-SmokeLog "Commands: $($Commands -join ' | ')"
Write-SmokeLog "Plugin jars: $($PluginJars -join ', ')"

$sourceFoliaJar = $FoliaJarPath
if (!(Test-Path -LiteralPath $sourceFoliaJar)) {
    $sourceFoliaJar = Join-Path $LiveServerRoot "folia.jar"
    Write-SmokeLog "Explicit Folia jar was not found; falling back to live server jar at $sourceFoliaJar" "WARN"
}
Copy-RequiredFile -Source $sourceFoliaJar -Destination (Join-Path $stagePath "folia.jar") -Label "Folia jar"

if ($CopyRuntimeDirs) {
    foreach ($dir in @("libraries", "versions")) {
        $source = Join-Path $LiveServerRoot $dir
        $destination = Join-Path $stagePath $dir
        if (Test-Path -LiteralPath $source) {
            Write-SmokeLog "Copying runtime directory $source to $destination"
            robocopy $source $destination /E /R:1 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null
            $robocopyExit = $LASTEXITCODE
            Write-SmokeLog "Robocopy for $dir exited with $robocopyExit"
            if ($robocopyExit -gt 8) {
                throw "Robocopy failed while copying $dir with exit code $robocopyExit"
            }
        } else {
            Write-SmokeLog "Runtime directory not found: $source" "WARN"
        }
    }

    $cacheSource = Join-Path $LiveServerRoot "cache\mojang_26.1.2.jar"
    if (Test-Path -LiteralPath $cacheSource) {
        New-Item -ItemType Directory -Path (Join-Path $stagePath "cache") -Force | Out-Null
        Copy-RequiredFile -Source $cacheSource -Destination (Join-Path $stagePath "cache\mojang_26.1.2.jar") -Label "Mojang cache jar"
    } else {
        Write-SmokeLog "Mojang cache jar was not found at $cacheSource" "WARN"
    }
}

foreach ($jar in $PluginJars) {
    Copy-RequiredFile -Source (Join-Path $PluginJarRoot $jar) -Destination (Join-Path $pluginsPath $jar) -Label "Plugin jar $jar"
}

if ($PlugManIgnoredPlugins.Length -gt 0) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $plugManJar = Get-ChildItem -LiteralPath $pluginsPath -Filter "PlugManX*.jar" -File -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($plugManJar) {
        $plugManDir = Join-Path $pluginsPath "PlugManX"
        New-Item -ItemType Directory -Path $plugManDir -Force | Out-Null
        $plugManConfig = Join-Path $plugManDir "config.yml"
        $zip = [IO.Compression.ZipFile]::OpenRead($plugManJar.FullName)
        try {
            $entry = $zip.Entries | Where-Object { $_.FullName -eq "config.yml" } | Select-Object -First 1
            if ($entry) {
                $reader = [IO.StreamReader]::new($entry.Open())
                try {
                    $content = $reader.ReadToEnd()
                } finally {
                    $reader.Dispose()
                }
                $content = $content -replace "(?m)^ignored-plugins:\s*\[.*\]\s*$", "ignored-plugins: [$PlugManIgnoredPlugins]"
                Set-Content -LiteralPath $plugManConfig -Value $content -Encoding UTF8
                Write-SmokeLog "Patched staged PlugManX ignored-plugins to [$PlugManIgnoredPlugins]"
            } else {
                Write-SmokeLog "PlugManX config.yml was not found inside $($plugManJar.Name)" "WARN"
            }
        } finally {
            $zip.Dispose()
        }
    } else {
        Write-SmokeLog "PlugManIgnoredPlugins was set, but no PlugManX jar was copied." "WARN"
    }
}

@"
eula=true
"@ | Set-Content -LiteralPath (Join-Path $stagePath "eula.txt") -Encoding ASCII

@"
server-port=25566
query.port=25566
online-mode=false
enable-query=false
enable-rcon=$($UseRcon.ToString().ToLowerInvariant())
rcon.port=$RconPort
rcon.password=$RconPassword
level-name=staging-world
spawn-protection=0
motd=ElectionPlugin reload smoke staging
view-distance=2
simulation-distance=2
max-players=1
"@ | Set-Content -LiteralPath (Join-Path $stagePath "server.properties") -Encoding ASCII
Write-SmokeLog "Wrote eula.txt and server.properties"

if ($UseRcon) {
    $stdoutPath = Join-Path $stagePath "terminal.log"
    $stderrPath = Join-Path $stagePath "terminal.err.log"
    $latestLog = Join-Path $stagePath "logs\latest.log"

    Write-SmokeLog "Starting staged Folia process"
    $process = Start-Process -FilePath "java" `
        -ArgumentList "-Xmx1536M -Xms512M -jar folia.jar nogui" `
        -WorkingDirectory $stagePath `
        -RedirectStandardOutput $stdoutPath `
        -RedirectStandardError $stderrPath `
        -WindowStyle Hidden `
        -PassThru
    Write-SmokeLog "Started staged Folia process id $($process.Id)"

    $started = $false
    $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($process.HasExited) {
            Write-SmokeLog "Staged process exited before startup completed with exit code $($process.ExitCode)" "ERROR"
            break
        }
        if (Test-Path -LiteralPath $latestLog) {
            try {
                $text = Get-Content -LiteralPath $latestLog -Raw
                if ($text -match "Done \(" -or $text -match "For help, type") {
                    $started = $true
                    Write-SmokeLog "Staged Folia startup completed"
                    break
                }
            } catch {
                Write-SmokeLog "Could not read latest.log yet: $($_.Exception.Message)" "WARN"
            }
        }
        Start-Sleep -Milliseconds 500
    }

    $rconOutputs = @()
    if ($started) {
        foreach ($command in $Commands) {
            Write-SmokeLog "Sending RCON command: $command"
            $output = (& java (Join-Path $repoRoot "scripts\RconSend.java") 127.0.0.1 $RconPort $RconPassword $command 2>&1 | Out-String).Trim()
            $rconOutputs += ">>> $command`n$output"
            Write-SmokeLog "RCON output for '$command': $output"
            Start-Sleep -Seconds $AfterCommandSeconds
        }

        Write-SmokeLog "Sending RCON command: stop"
        $stopOutput = (& java (Join-Path $repoRoot "scripts\RconSend.java") 127.0.0.1 $RconPort $RconPassword stop 2>&1 | Out-String).Trim()
        $rconOutputs += ">>> stop`n$stopOutput"
        Write-SmokeLog "RCON output for 'stop': $stopOutput"
    } else {
        Write-SmokeLog "Skipping RCON commands because staged Folia did not finish startup" "ERROR"
    }

    if (-not $process.WaitForExit(45000)) {
        Write-SmokeLog "Staged process did not exit after stop; killing process $($process.Id)" "ERROR"
        $process.Kill()
        $process.WaitForExit()
    }
    $process.Refresh()
    Write-SmokeLog "Staged process ended with exit code $($process.ExitCode)"

    $all = @()
    foreach ($path in @($latestLog, $stdoutPath, $stderrPath, $script:DebugLogPath)) {
        if (Test-Path -LiteralPath $path) {
            $all += Get-Content -LiteralPath $path
        }
    }
    $interesting = Get-InterestingLines -Lines $all

    [pscustomobject]@{
        Mode = "RCON"
        Started = $started
        ExitCode = $process.ExitCode
        StagePath = $stagePath
        ServerLogPath = $latestLog
        DebugLogPath = $script:DebugLogPath
        RconOutput = ($rconOutputs -join "`n`n").Trim()
        InterestingLines = ($interesting -join "`n")
    } | ConvertTo-Json -Depth 4
    exit
}

Write-SmokeLog "STDIN mode selected; Folia may report console command source errors in this mode."
Push-Location $stagePath
try {
    $logPath = Join-Path $stagePath "terminal.log"
    & {
        Start-Sleep -Seconds $StartupTimeoutSeconds
        foreach ($command in $Commands) {
            Write-SmokeLog "Writing stdin command: $command"
            $command
            Start-Sleep -Seconds $AfterCommandSeconds
        }
        Write-SmokeLog "Writing stdin command: stop"
        "stop"
    } | & java -Xmx1536M -Xms512M -jar folia.jar nogui *>&1 | Tee-Object -FilePath $logPath
    $exitCode = $LASTEXITCODE
    Write-SmokeLog "STDIN run ended with exit code $exitCode"
} finally {
    Pop-Location
}

$all = @()
foreach ($path in @($logPath, $script:DebugLogPath)) {
    if (Test-Path -LiteralPath $path) {
        $all += Get-Content -LiteralPath $path
    }
}
$started = (($all -join "`n") -match "Done \(" -or ($all -join "`n") -match "For help, type")
$interesting = Get-InterestingLines -Lines $all

[pscustomobject]@{
    Mode = "STDIN"
    Started = $started
    ExitCode = $exitCode
    StagePath = $stagePath
    ServerLogPath = $logPath
    DebugLogPath = $script:DebugLogPath
    InterestingLines = ($interesting -join "`n")
} | ConvertTo-Json -Depth 4
