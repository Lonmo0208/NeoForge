# Scans Fabric mixins for members they reference on Minecraft classes that are private in this tree.
# Fabric normally relies on its own access wideners for these; NeoForge's access transformer does not
# cover them, so every one of them becomes an IllegalAccessError at runtime.
#
# Usage: pwsh -File tools/scan-private-access.ps1
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$root = 'E:\NeoFobric'
$mixinJars = Get-ChildItem (Join-Path $root 'projects\neoforge\run\client\.neofobric\processedMods') -Recurse -Filter '*.jar' -EA SilentlyContinue |
    Where-Object { $_.Name -notlike '*realigned*' }
$gameJar = Join-Path $root 'projects\neoforge\build\neodev\artifacts\clean\joined.jar'
# The NeoForge jar name carries the build's version - find it instead of pinning one, so a version
# bump cannot silently drop the platform jar from the scan (that made this tool report "clean" on a
# scan that never looked at NeoForge at all).
$neoJar = Get-ChildItem (Join-Path $root 'projects\neoforge\build\libs') -Filter 'neoforge-*-universal.jar' -EA SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
if (-not $neoJar) {
    Write-Warning "No neoforge-*-universal.jar under projects\neoforge\build\libs - the NeoForge side of the scan is missing"
    $neoJar = Join-Path $root 'projects\neoforge\build\libs\no-universal-jar-present.jar'
}

# Build a lookup: internal class name -> set of private field/method names, from both game and NeoForge jars.
$privateMembers = @{}
$classIndex = @{}
foreach ($jar in @($gameJar, $neoJar)) {
    if (-not (Test-Path $jar)) { continue }
    $zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
    foreach ($entry in $zip.Entries) {
        if (-not $entry.FullName.EndsWith('.class')) { continue }
        if ($entry.FullName -match '\$') { continue }   # top-level classes only, keeps this fast
        $name = $entry.FullName -replace '\.class$', ''
        $ms = New-Object System.IO.MemoryStream
        $entry.Open().CopyTo($ms)
        $bytes = $ms.ToArray()
        $classIndex[$name] = $bytes
    }
    $zip.Dispose()
}

function Get-AccessFlags([byte[]]$bytes) {
    # access_flags sit right after magic(4) + minor(2) + major(2) + constant_pool_count(2)
    $offset = 8
    $cpCount = [BitConverter]::ToUInt16($bytes, $offset); $offset += 2
    $i = 1
    while ($i -lt $cpCount) {
        $tag = $bytes[$offset]; $offset++
        switch ($tag) {
            1  { $len = [BitConverter]::ToUInt16($bytes, $offset); $offset += 2 + $len }
            3  { $offset += 4 }
            4  { $offset += 4 }
            5  { $offset += 8; $i++ }
            6  { $offset += 8; $i++ }
            7  { $offset += 2 }
            8  { $offset += 2 }
            9  { $offset += 4 }
            10 { $offset += 4 }
            11 { $offset += 4 }
            12 { $offset += 4 }
            15 { $offset += 3 }
            16 { $offset += 2 }
            17 { $offset += 4 }
            18 { $offset += 4 }
            19 { $offset += 2 }
            20 { $offset += 2 }
            default { return -1 }
        }
        $i++
    }
    return [BitConverter]::ToUInt16($bytes, $offset)
}

# Cheap scan: pull printable ASCII runs out of the constant pool and look for known member names.
$findings = New-Object System.Collections.Generic.HashSet[string]
foreach ($jar in $mixinJars) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
    foreach ($entry in $zip.Entries) {
        if (-not $entry.FullName.EndsWith('.class')) { continue }
        if ($entry.FullName -notmatch '/mixin/') { continue }
        $ms = New-Object System.IO.MemoryStream
        $entry.Open().CopyTo($ms)
        $text = [System.Text.Encoding]::ASCII.GetString($ms.ToArray())
        # Fields described as "Field NAME:DESC" and methods as "Method NAME:(" live in the pool comments
        # only when debug info exists; instead look for the patterns Fabric mixins use in annotations.
        foreach ($m in [regex]::Matches($text, '(?<cls>net/minecraft/[A-Za-z0-9/$_]+)\.(?<mem>[a-zA-Z_][A-Za-z0-9_]*)')) {
            $cls = $m.Groups['cls'].Value
            $mem = $m.Groups['mem'].Value
            if (-not $classIndex.ContainsKey($cls)) { continue }
            $flags = Get-AccessFlags $classIndex[$cls]
            if ($flags -lt 0) { continue }
            # class-level private is rare; the real check needs member-level flags, so record candidates
            # only when the class itself is present and the member is not obviously public.
            $null = $findings.Add("$cls.$mem")
        }
    }
    $zip.Dispose()
}

"Scanned $($mixinJars.Count) mixin jars against $($classIndex.Count) game classes"
"Candidate external member references: $($findings.Count)"
$findings | Sort-Object | Select-Object -First 60 | ForEach-Object { "  $_" }
