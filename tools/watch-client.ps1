# Watches a running Minecraft client: reports crashes and key milestones as they appear.
# Replaces waiting for someone to paste a log.
#
# Usage: pwsh -File tools/watch-client.ps1 [-Minutes 6] [-World 新的世界]
param(
    [int]$Minutes = 6,
    [string]$World = '新的世界',
    [switch]$NoLaunch
)

$ErrorActionPreference = 'Continue'
$root = 'E:\NeoFobric'
$clog = Join-Path $root 'projects\neoforge\run\client\logs\latest.log'
$crashDir = Join-Path $root 'projects\neoforge\run\client\crash-reports'

function Get-GameProcess {
    # The dev game is identified by the FML mod-folders marker in its command line; the old
    # "largest java over 300 MB" heuristic could pick (or kill) an unrelated production game.
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue | Where-Object {
        $_.CommandLine -like '*fml.modFolders*'
    } | ForEach-Object {
        $mb = 0
        try { $mb = [int]((Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue).WorkingSet64 / 1MB) } catch {}
        [pscustomobject]@{ Id = $_.ProcessId; MB = $mb }
    } | Sort-Object MB -Descending | Select-Object -First 1
}

function Kill-Game {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue | Where-Object {
        $_.CommandLine -like '*fml.modFolders*'
    } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

# Record which crash reports already existed, so only new ones are reported.
$knownCrashes = @{}
if (Test-Path $crashDir) {
    Get-ChildItem $crashDir -Filter '*.txt' -ErrorAction SilentlyContinue | ForEach-Object { $knownCrashes[$_.Name] = $true }
}

if (-not $NoLaunch) {
    Kill-Game
    Start-Sleep -Seconds 4
    $saves = Join-Path $root 'projects\neoforge\run\client\saves'
    Get-ChildItem $saves -Recurse -Filter 'session.lock' -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notlike '*_broken_backup*' } |
        ForEach-Object { Remove-Item $_.FullName -Force -ErrorAction SilentlyContinue }
    Remove-Item $clog -Force -ErrorAction SilentlyContinue
    Write-Host "== launching client (world: $World) ==" -ForegroundColor Cyan
    Start-Process -FilePath (Join-Path $root 'gradlew.bat') `
        -ArgumentList ':neoforge:runClient', "-Pneofobric.quickPlayWorld=$World", '--console=plain', '--no-daemon' `
        -WorkingDirectory $root -WindowStyle Hidden | Out-Null
}

$deadline = (Get-Date).AddMinutes($Minutes)
$seen = @{}
$verdict = 'TIMEOUT'
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 3

    # New crash report?
    if (Test-Path $crashDir) {
        $new = Get-ChildItem $crashDir -Filter '*.txt' -ErrorAction SilentlyContinue |
            Where-Object { -not $knownCrashes.ContainsKey($_.Name) } |
            Sort-Object LastWriteTime
        foreach ($cr in $new) {
            $knownCrashes[$cr.Name] = $true
            $head = Get-Content $cr.FullName -ErrorAction SilentlyContinue | Select-Object -Skip 6 -First 3
            Write-Host ""
            Write-Host "!! CRASH: $($cr.Name)" -ForegroundColor Red
            $head | ForEach-Object { Write-Host "   $_" -ForegroundColor Red }
            $verdict = 'CRASH'
        }
        if ($verdict -eq 'CRASH') { break }
    }

    if (-not (Test-Path $clog)) { continue }
    $text = Get-Content $clog -Raw -ErrorAction SilentlyContinue
    if (-not $text) { continue }

    # Milestones, each reported once.
    foreach ($marker in @(
            @{ Key = 'scan';   Pattern = 'Access scan of sodium';            Label = 'scan reached sodium' },
            @{ Key = 'menu';   Pattern = 'Sound engine started';             Label = 'main menu ready' },
            @{ Key = 'login';  Pattern = 'logged in with entity id';          Label = 'PLAYER LOGGED IN' },
            @{ Key = 'world';  Pattern = 'Time elapsed';                     Label = 'world load finished' })) {
        if (-not $seen[$marker.Key] -and $text -match [regex]::Escape($marker.Pattern)) {
            $seen[$marker.Key] = $true
            Write-Host "   [ok] $($marker.Label)" -ForegroundColor Green
        }
    }

    # Fatal conditions, reported immediately.
    foreach ($bad in @(
            @{ Pattern = 'IllegalAccessError';        Label = 'IllegalAccessError (missing access widening)' },
            @{ Pattern = 'IncompatibleClassChangeError'; Label = 'IncompatibleClassChangeError (final class / interface change)' },
            @{ Pattern = 'NoSuchMethodError';         Label = 'NoSuchMethodError' },
            @{ Pattern = 'VerifyError';               Label = 'VerifyError (bad bytecode from an injection)' },
            @{ Pattern = 'Failed to encode packet';   Label = 'packet encode failure (payload conflict)' },
            @{ Pattern = 'Client disconnected with reason'; Label = 'client disconnected' })) {
        if (-not $seen["bad:$($bad.Label)"] -and $text -match [regex]::Escape($bad.Pattern)) {
            $seen["bad:$($bad.Label)"] = $true
            Write-Host "   [!!] $($bad.Label)" -ForegroundColor Red
            if ($bad.Pattern -eq 'Client disconnected with reason') {
                $line = (Get-Content $clog -ErrorAction SilentlyContinue |
                    Select-String -SimpleMatch 'Client disconnected with reason' | Select-Object -Last 1)
                if ($line) { Write-Host "        $($line.Line.Substring([Math]::Max(0, $line.Line.IndexOf('reason'))))" -ForegroundColor Red }
                $verdict = 'DISCONNECTED'
            }
        }
    }
    if ($verdict -eq 'DISCONNECTED') { break }

    # Success is the player actually being in the world, then surviving a few seconds.
    if ($seen['login']) {
        Start-Sleep -Seconds 12
        $after = Get-Content $clog -Raw -ErrorAction SilentlyContinue
        if ($after -notmatch 'Client disconnected with reason') { $verdict = 'IN_WORLD'; break }
    }

    # Process gone without a crash report: report the tail so the reason is visible.
    if (-not (Get-GameProcess)) {
        Start-Sleep -Seconds 3
        $tail = Get-Content $clog -Tail 4 -ErrorAction SilentlyContinue
        Write-Host "   process exited; last lines:" -ForegroundColor Yellow
        $tail | ForEach-Object { Write-Host "     $_" -ForegroundColor Yellow }
        if ($verdict -eq 'TIMEOUT') { $verdict = 'EXITED' }
        break
    }
}

Kill-Game
Write-Host ""
Write-Host "== verdict: $verdict ==" -ForegroundColor $(if ($verdict -eq 'IN_WORLD') { 'Green' } else { 'Yellow' })
