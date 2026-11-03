# github-release.ps1 - create a GitHub release and attach the built artifacts.
#
#   powershell -File tools\github-release.ps1 -Version 0.4.0-alpha.2
#
# Token: uses $env:GITHUB_TOKEN when set, otherwise asks git's credential helper
# (the same one `git push` already uses). Never prints the token.
param(
    [string]$Version = '0.4.0-alpha.2',
    [string]$Repo = 'genshit1145141919810-hub/big_fat_fish_can_play_mc',
    [switch]$Draft,
    [switch]$NotPrerelease
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$dist = Join-Path $root "dist\$Version"
$jar = Join-Path $dist "big-fat-fish-can-play-mc-$Version.jar"
if (-not (Test-Path $jar)) {
    throw "jar not found: $jar (run tools\pack-release.ps1 -Version $Version first)"
}

function Get-Token {
    if ($env:GITHUB_TOKEN) { return $env:GITHUB_TOKEN }
    $payload = "protocol=https`nhost=github.com`n`n"
    $filled = $payload | git credential fill 2>$null
    $line = $filled | Where-Object { $_ -match '^password=' } | Select-Object -First 1
    if (-not $line) { throw 'no token: set GITHUB_TOKEN or store a credential for github.com' }
    return ($line -replace '^password=', '')
}

# Release notes: the section for this version from CHANGELOG.md.
$changelog = Get-Content (Join-Path $root 'CHANGELOG.md') -Raw
$marker = "## $Version"
$idx = $changelog.IndexOf($marker)
if ($idx -lt 0) { throw "CHANGELOG.md has no section for $Version" }
$notes = $changelog.Substring($idx)
$next = $notes.IndexOf("`n## ", 3)
if ($next -gt 0) { $notes = $notes.Substring(0, $next) }

$token = Get-Token
$headers = @{
    Authorization          = "Bearer $token"
    'User-Agent'           = 'big-fat-fish-release'
    Accept                 = 'application/vnd.github+json'
}

$tag = "v$Version"
$body = @{
    tag_name         = $tag
    name             = "Big Fat Fish Can Play MC $Version"
    body             = $notes
    draft            = [bool]$Draft
    prerelease       = -not $NotPrerelease
    generate_release_notes = $false
} | ConvertTo-Json -Depth 5

Write-Host "== creating release $tag on $Repo"
try {
    $release = Invoke-RestMethod -Method Post -Uri "https://api.github.com/repos/$Repo/releases" `
        -Headers $headers -Body $body -ContentType 'application/json'
} catch {
    $detail = $_.ErrorDetails.Message
    if ($detail -match 'already_exists') {
        Write-Host '  release exists, fetching it'
        $release = Invoke-RestMethod -Headers $headers -Uri "https://api.github.com/repos/$Repo/releases/tags/$tag"
    } else {
        throw
    }
}
Write-Host "  $($release.html_url)"

$assets = Get-ChildItem $dist -File
foreach ($file in $assets) {
    $mime = switch ($file.Extension) {
        '.jar' { 'application/java-archive' }
        '.md' { 'text/markdown' }
        '.txt' { 'text/plain' }
        default { 'application/octet-stream' }
    }
    $uri = "https://uploads.github.com/repos/$Repo/releases/$($release.id)/assets?name=$([uri]::EscapeDataString($file.Name))"
    $existing = (Invoke-RestMethod -Headers $headers -Uri "https://api.github.com/repos/$Repo/releases/$($release.id)/assets").name
    if ($existing -contains $file.Name) {
        Write-Host "  skip (already attached): $($file.Name)"
        continue
    }
    Invoke-RestMethod -Method Post -Uri $uri -Headers $headers -InFile $file.FullName -ContentType $mime | Out-Null
    Write-Host "  attached: $($file.Name) ($($file.Length) bytes)"
}

Write-Host "done: $($release.html_url)"
