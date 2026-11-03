# Derive a library jar path from its maven name when the version json has no
# downloads.artifact.path (PCL2 writes maven-style entries for Fabric libraries).
function Get-LibPath {
    param([string]$Root, $Lib)
    if ($Lib.downloads -and $Lib.downloads.artifact -and $Lib.downloads.artifact.path) {
        $p = Join-Path (Join-Path $Root 'libraries') ($Lib.downloads.artifact.path -replace '/', '\')
        if (Test-Path $p) { return $p }
    }
    $name = $Lib.name
    if (-not $name) { return $null }
    $parts = $name.Split(':')
    if ($parts.Count -lt 3) { return $null }
    $group = $parts[0].Replace('.', '\')
    $artifact = $parts[1]
    $version = $parts[2]
    $classifier = if ($parts.Count -ge 4) { $parts[3] } else { $null }
    $file = if ($classifier) { "$artifact-$version-$classifier.jar" } else { "$artifact-$version.jar" }
    $p = Join-Path (Join-Path (Join-Path (Join-Path $Root 'libraries') $group) $artifact) $version
    $full = Join-Path $p $file
    if (Test-Path $full) { return $full }
    return $null
}
