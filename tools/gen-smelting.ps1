# Regenerate the cooking recipe tables (smelting / blasting / smoking / campfire)
# straight from the installed Minecraft jar, so the data is never hand-written.
#
#   powershell -File tools\gen-smelting.ps1 [-Jar <path to minecraft jar>] [-Out <asset dir>]
#
# Defaults: pick any 1.21.11 client jar under D:\Game\.minecraft\versions,
# write into src\main\resources\assets\big_fat_fish_can_play_mc.
param(
    [string]$Jar,
    [string]$Out = (Join-Path $PSScriptRoot '..\src\main\resources\assets\big_fat_fish_can_play_mc')
)

$ErrorActionPreference = 'Stop'

if (-not $Jar) {
    $candidates = Get-ChildItem 'D:\Game\.minecraft\versions' -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '1\.21\.11' } |
        ForEach-Object { Get-ChildItem $_.FullName -Filter '*.jar' -ErrorAction SilentlyContinue } |
        Where-Object { $_.Length -gt 20MB }
    if (-not $candidates) { throw 'cannot find a 1.21.11 jar, pass -Jar' }
    $Jar = ($candidates | Select-Object -First 1).FullName
}
if (-not (Test-Path $Jar)) { throw "jar not found: $Jar" }
Write-Host "jar = $Jar"

$tmp = Join-Path $env:TEMP ('mcrecipes-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $tmp | Out-Null
try {
    Copy-Item $Jar (Join-Path $tmp 'mc.zip')
    Expand-Archive -Path (Join-Path $tmp 'mc.zip') -DestinationPath (Join-Path $tmp 'mc') -Force
    $recipeDir = Join-Path $tmp 'mc\data\minecraft\recipe'
    if (-not (Test-Path $recipeDir)) { throw "no recipe data in this jar: $recipeDir" }

    $groups = [ordered]@{
        'minecraft:smelting'         = 'smelting.json'
        'minecraft:blasting'         = 'blasting.json'
        'minecraft:smoking'          = 'smoking.json'
        'minecraft:campfire_cooking' = 'campfire_cooking.json'
    }
    $buckets = @{}
    foreach ($key in $groups.Keys) { $buckets[$key] = New-Object System.Collections.ArrayList }

    $files = Get-ChildItem $recipeDir -Filter '*.json' -Recurse
    $parsed = 0
    foreach ($file in $files) {
        try {
            $json = Get-Content $file.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
        } catch {
            continue
        }
        $type = $json.type
        if (-not $type -or -not $buckets.ContainsKey($type)) { continue }

        # 1.21 shape: { "ingredient": "minecraft:raw_iron", "result": { "id": "minecraft:iron_ingot" } }
        $input = $null
        $ing = $json.ingredient
        if ($ing) {
            if ($ing -is [System.Array]) { $ing = $ing[0] }
            if ($ing -is [string]) { $input = $ing }
            elseif ($ing.item) { $input = $ing.item }
            elseif ($ing.tag) { $input = '#' + $ing.tag }
        }
        $result = $json.result
        $outId = $null
        $count = 1
        if ($result -is [string]) {
            $outId = $result
        } elseif ($result) {
            if ($result.id) { $outId = $result.id }
            elseif ($result.item) { $outId = $result.item }
            if ($result.count) { $count = [int]$result.count }
        }
        if (-not $input -or -not $outId) { continue }

        $entry = [pscustomobject]@{ i = $input; o = $outId; c = $count }
        [void]$buckets[$type].Add($entry)
        $parsed++
    }

    foreach ($key in $groups.Keys) {
        $list = @($buckets[$key] | Sort-Object { $_.o }, { $_.i })
        $path = Join-Path $Out $groups[$key]
        $json = ConvertTo-Json -InputObject $list -Compress -Depth 5
        [System.IO.File]::WriteAllText($path, $json, (New-Object System.Text.UTF8Encoding($false)))
        Write-Host ("wrote {0} ({1} entries)" -f $groups[$key], $list.Count)
    }
    Write-Host "parsed $parsed cooking recipes from $($files.Count) recipe files"
} finally {
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}
