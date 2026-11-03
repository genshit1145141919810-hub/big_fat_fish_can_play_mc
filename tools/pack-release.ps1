# pack-release.ps1 - assemble a dist/<version> folder ready for manual upload.
#
#   powershell -File tools\pack-release.ps1 -Version 0.4.0-alpha.2
#
# Copies the built jar + sources, README, LICENSE, CHANGELOG and the store page text,
# and writes SHA1/SHA512 checksums (Modrinth wants them, and people like verifying).
param(
    [Parameter(Mandatory = $true)][string]$Version
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$libs = Join-Path $root 'build\libs'
$jar = Join-Path $libs "big-fat-fish-can-play-mc-$Version.jar"
$sources = Join-Path $libs "big-fat-fish-can-play-mc-$Version-sources.jar"
if (-not (Test-Path $jar)) { throw "jar not found: $jar (run gradlew build first)" }

$out = Join-Path $root "dist\$Version"
New-Item -ItemType Directory -Path $out -Force | Out-Null
Copy-Item $jar $out -Force
if (Test-Path $sources) { Copy-Item $sources $out -Force }
foreach ($file in @('README.md', 'LICENSE', 'CHANGELOG.md')) {
    $path = Join-Path $root $file
    if (Test-Path $path) { Copy-Item $path $out -Force }
}
$desc = Join-Path $root 'docs\modrinth-description.md'
if (Test-Path $desc) { Copy-Item $desc (Join-Path $out 'store-description.md') -Force }

$lines = @()
Get-ChildItem $out -Filter '*.jar' | ForEach-Object {
    $sha1 = (Get-FileHash $_.FullName -Algorithm SHA1).Hash.ToLower()
    $sha512 = (Get-FileHash $_.FullName -Algorithm SHA512).Hash.ToLower()
    $lines += "$sha1  $($_.Name)"
    $lines += "sha512:$sha512  $($_.Name)"
}
$lines | Set-Content (Join-Path $out 'checksums.txt') -Encoding UTF8

Write-Host "packed: $out"
Get-ChildItem $out | Select-Object Name, Length | Format-Table -AutoSize
