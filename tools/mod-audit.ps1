# mod-audit.ps1 - check that every fabric.mod.json dependency of every jar in a mods
# folder is provided by another jar in that folder (including jar-in-jar modules),
# or by the loader/minecraft/java itself. ASCII only on purpose.
param(
    [Parameter(Mandatory = $true)][string]$ModsDir
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$system = @('minecraft', 'java', 'fabricloader', 'fabric-loader')

function Read-ModJson([System.IO.Compression.ZipArchive]$zip) {
    $entry = $zip.Entries | Where-Object { $_.FullName -eq 'fabric.mod.json' } | Select-Object -First 1
    if (-not $entry) { return $null }
    $reader = New-Object System.IO.StreamReader($entry.Open())
    $text = $reader.ReadToEnd()
    $reader.Close()
    return ($text | ConvertFrom-Json)
}

function Read-NestedModJson([System.IO.Compression.ZipArchiveEntry]$entry) {
    $ms = New-Object System.IO.MemoryStream
    $stream = $entry.Open()
    $stream.CopyTo($ms)
    $stream.Close()
    $ms.Position = 0
    $zip = New-Object System.IO.Compression.ZipArchive($ms)
    $json = Read-ModJson $zip
    $zip.Dispose()
    $ms.Dispose()
    return $json
}

$mods = @()      # id -> metadata
$provided = New-Object System.Collections.Generic.HashSet[string]

foreach ($file in Get-ChildItem $ModsDir -File -Filter *.jar) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($file.FullName)
    try {
        $json = Read-ModJson $zip
        if ($json) {
            $mods += [pscustomobject]@{ File = $file.Name; Id = $json.id; Version = $json.version; Depends = $json.depends; Nested = @() }
            [void]$provided.Add($json.id)
        }
        foreach ($entry in ($zip.Entries | Where-Object { $_.FullName -like 'META-INF/jars/*.jar' })) {
            $nested = Read-NestedModJson $entry
            if ($nested) {
                [void]$provided.Add($nested.id)
                if ($json) { $mods[-1].Nested += ($nested.id + '@' + $nested.version) }
            }
        }
    } finally {
        $zip.Dispose()
    }
}

Write-Host "== jar $($mods.Count) mods, $($provided.Count) ids (incl. nested) =="
foreach ($m in $mods) {
    Write-Host ("- {0} {1}  [{2}]" -f $m.Id, $m.Version, $m.File)
    if ($m.Nested.Count -gt 0) {
        Write-Host ("    nested: " + ($m.Nested -join ', '))
    }
}

$problems = @()
foreach ($m in $mods) {
    if (-not $m.Depends) { continue }
    foreach ($prop in $m.Depends.PSObject.Properties) {
        $id = $prop.Name
        if ($system -contains $id) { continue }
        if ($provided.Contains($id)) { continue }
        $problems += ("MISSING: {0} needs '{1}' ({2})" -f $m.Id, $id, $prop.Value)
    }
}

Write-Host ''
if ($problems.Count -eq 0) {
    Write-Host 'OK - all dependencies satisfied'
} else {
    $problems | ForEach-Object { Write-Host $_ }
    Write-Host ("$($problems.Count) problem(s)")
}
