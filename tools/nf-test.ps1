#!/usr/bin/env pwsh
# NeoFobric 世界加载 A/B 测试驱动
# 用法: ./tools/nf-test.ps1 -Args @('-Pneofobric.disabled=true') -Label "桥接全关"
# 行为: 启动客户端 -> 自动进世界 -> 判断结果 -> 无论成败都关闭进程（避免挂住）
param(
    [string[]]$Args = @(),
    [string]$Label = 'test',
    [int]$TimeoutMin = 5,
    [string]$World = '新的世界'
)

$root = 'E:\NeoFobric'
$saves = Join-Path $root 'projects\neoforge\run\client\saves'
$clog = Join-Path $root 'projects\neoforge\run\client\logs\latest.log'

function Stop-AllClients {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" -EA SilentlyContinue | ForEach-Object {
        $c = $_.CommandLine
        if ($c -and ($c -like '*runClient*' -or $c -like '*devlaunch*')) {
            Stop-Process -Id $_.ProcessId -Force -EA SilentlyContinue
        }
    }
    Start-Sleep -Seconds 3
}

Stop-AllClients
Get-ChildItem $saves -Recurse -Filter 'session.lock' -EA SilentlyContinue |
    Where-Object { $_.FullName -notlike '*_broken_backup*' } |
    ForEach-Object { Remove-Item $_.FullName -Force -EA SilentlyContinue }
Remove-Item $clog -Force -EA SilentlyContinue

$allArgs = @(':neoforge:runClient', "-Pneofobric.quickPlayWorld=$World", '--console=plain', '--no-daemon') + $Args
Write-Host "=== [$Label] 测试开始 ===" -ForegroundColor Cyan
Write-Host "  参数: $($Args -join ' ')"
$p = Start-Process -FilePath (Join-Path $root 'gradlew.bat') -ArgumentList $allArgs `
    -WorkingDirectory $root -WindowStyle Hidden -PassThru

$deadline = (Get-Date).AddMinutes($TimeoutMin)
$state = 'TIMEOUT'
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 4
    if ($p.HasExited) { if ($state -eq 'TIMEOUT') { $state = 'CLIENT_EXITED' }; break }
    if (Test-Path $clog) {
        $t = Get-Content $clog -Raw -EA SilentlyContinue
        if ($t -match 'Time elapsed') { $state = 'WORLD_OK'; break }
        if ($t -match 'VerifyError|Failed to load datapacks') { $state = 'CRASH'; break }
        if ($t -match 'Stopping server') { $state = 'STOPPED_16'; break }
    }
}

# 无论成败：立刻关闭，避免游戏挂住
Stop-AllClients

$icon = switch ($state) {
    'WORLD_OK' { '[OK]   ' }
    'CRASH' { '[CRASH]' }
    'STOPPED_16' { '[FAIL] ' }
    default { '[?]    ' }
}
Write-Host "$icon [$Label] 阶段=$state" -ForegroundColor $(if ($state -eq 'WORLD_OK') { 'Green' } elseif ($state -eq 'CRASH') { 'Red' } else { 'Yellow' })

# 关键判据
if (Test-Path $clog) {
    $spawn = if ((Get-Content $clog | Select-String -SimpleMatch 'Selecting global world spawn').Count -gt 0) { 'yes' } else { 'no' }
    $elapsed = (Get-Content $clog | Select-String -SimpleMatch 'Time elapsed').Count
    Write-Host "  Selecting global world spawn=$spawn  Time elapsed=$elapsed"
}
return $state
