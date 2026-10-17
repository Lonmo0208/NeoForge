#!/usr/bin/env pwsh
# NeoFobric 世界加载测试驱动 v2
#   ./nf2.ps1 -Args @('-Pneofobric.noFabricMixins=true') -Label "noFabricMixins"
# 检测：Time elapsed = 成功；16%+保存 或 集成服务器启动后 20s 无进展 = 失败
# 无论成败都会关闭游戏，避免挂住。
param(
    [string[]]$ExtraArgs = @(),
    [string]$Label = 'test',
    [int]$TimeoutMin = 4
)

$root = 'E:\NeoFobric'
$clog = Join-Path $root 'projects\neoforge\run\client\logs\latest.log'
$saves = Join-Path $root 'projects\neoforge\run\client\saves'

function Kill-Game {
    # Only the dev game: its command line carries the FML mod-folders marker. The old heuristic
    # ("any java over 300 MB that is not the Gradle daemon") also killed a running production
    # Minecraft or any other large JVM on this machine.
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" -EA SilentlyContinue | Where-Object {
        $_.CommandLine -like '*fml.modFolders*'
    } | ForEach-Object {
        Stop-Process -Id $_.ProcessId -Force -EA SilentlyContinue
    }
    Start-Sleep -Seconds 3
}

Kill-Game
Get-ChildItem $saves -Recurse -Filter 'session.lock' -EA SilentlyContinue |
    Where-Object { $_.FullName -notlike '*_broken_backup*' } |
    ForEach-Object { Remove-Item $_.FullName -Force -EA SilentlyContinue }
Remove-Item $clog -Force -EA SilentlyContinue

$allArgs = @(':neoforge:runClient', '-Pneofobric.quickPlayWorld=新的世界', '--console=plain', '--no-daemon') + $ExtraArgs
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$p = Start-Process -FilePath (Join-Path $root 'gradlew.bat') -ArgumentList $allArgs `
    -WorkingDirectory $root -WindowStyle Hidden -PassThru

$deadline = (Get-Date).AddMinutes($TimeoutMin)
$state = 'TIMEOUT'
$integratedAt = $null
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
    if ($p.HasExited) { $state = 'CLIENT_EXITED'; break }
    if (-not (Test-Path $clog)) { continue }
    $t = Get-Content $clog -Raw -EA SilentlyContinue
    if ($t -match 'Time elapsed') { $state = 'WORLD_OK'; break }
    if ($t -match 'VerifyError|Failed to load datapacks') { $state = 'CRASH'; break }
    if (-not $integratedAt -and $t -match 'Starting integrated minecraft server') { $integratedAt = Get-Date }
    # 集成服务器起来后 20 秒仍无 Time elapsed = 世界加载被放弃
    if ($integratedAt -and ((Get-Date) - $integratedAt).TotalSeconds -gt 20) { $state = 'FAILED_16'; break }
}

Kill-Game
$sw.Stop()
$color = switch ($state) { 'WORLD_OK' { 'Green' } 'FAILED_16' { 'Red' } 'CRASH' { 'Magenta' } default { 'Yellow' } }
Write-Host ("  [{0}] -> {1}  ({2:N0}s)" -f $Label, $state, $sw.Elapsed.TotalSeconds) -ForegroundColor $color
return $state
