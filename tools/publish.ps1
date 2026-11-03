# publish.ps1 - build artifacts and upload them to GitHub / Modrinth / CurseForge.
#
# Credentials come from environment variables (never hardcode them):
#   GITHUB_TOKEN, GITHUB_REPO            owner/name
#   MODRINTH_TOKEN, MODRINTH_PROJECT     project id or slug
#   CURSEFORGE_TOKEN, CURSEFORGE_PROJECT numeric project id
#
# Examples:
#   $env:MODRINTH_TOKEN='mrp_xxx'; $env:MODRINTH_PROJECT='big-fat-fish-can-play-mc'
#   powershell -File tools\publish.ps1 -Version 0.4.0-alpha.2 -SkipGitHub -Proxy ''
#
# Add -DryRun to print exactly what would be sent without touching the network.
param(
    [string]$Version = '0.4.0-alpha.2',
    [string]$Jar = '',
    [string]$Changelog = '',
    [string]$Description = '',
    [string]$Proxy = '',
    [string]$GameVersion = '1.21.11',
    [switch]$SkipGitHub,
    [switch]$SkipModrinth,
    [switch]$SkipCurseForge,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $Jar) { $Jar = Join-Path $root "build\libs\big-fat-fish-can-play-mc-$Version.jar" }
if (-not $Changelog) { $Changelog = Join-Path $root 'CHANGELOG.md' }
if (-not $Description) { $Description = Join-Path $root 'docs\modrinth-description.md' }
if (-not (Test-Path $Jar)) { throw "jar not found: $Jar (build first: gradlew build)" }

# Release notes: the section for this version, falling back to the whole file.
$body = ''
if (Test-Path $Changelog) {
    $body = Get-Content $Changelog -Raw
    $marker = "## $Version"
    $idx = $body.IndexOf($marker)
    if ($idx -ge 0) {
        $body = $body.Substring($idx)
        $next = $body.IndexOf("`n## ", 3)
        if ($next -gt 0) { $body = $body.Substring(0, $next) }
    }
}
$displayName = "Big Fat Fish Can Play MC $Version"
$sha1 = (Get-FileHash $Jar -Algorithm SHA1).Hash.ToLower()
$sha512 = (Get-FileHash $Jar -Algorithm SHA512).Hash.ToLower()
$fileName = [System.IO.Path]::GetFileName($Jar)

function Write-Step($text) { Write-Host "== $text" -ForegroundColor Cyan }
function Invoke-Api {
    param([string]$Uri, [hashtable]$Headers, $Body, [string]$ContentType, [string]$Method = 'Post')
    if ($Proxy) {
        return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $Headers -Body $Body -ContentType $ContentType -Proxy $Proxy
    }
    return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $Headers -Body $Body -ContentType $ContentType
}

Write-Step 'artifact'
Write-Host "  jar    : $Jar"
Write-Host "  size   : $((Get-Item $Jar).Length) bytes"
Write-Host "  sha1   : $sha1"
Write-Host "  version: $Version ($GameVersion / fabric / alpha)"

# ---------------------------------------------------------------- GitHub release
if (-not $SkipGitHub) {
    Write-Step 'GitHub release'
    $token = $env:GITHUB_TOKEN
    $repo = $env:GITHUB_REPO
    if (-not $token -or -not $repo) {
        Write-Host '  skipped: set GITHUB_TOKEN and GITHUB_REPO (e.g. yourname/big_fat_fish_can_play_mc)'
    } elseif ($DryRun) {
        Write-Host "  [dry-run] POST https://api.github.com/repos/$repo/releases tag=v$Version prerelease"
        Write-Host "  [dry-run] upload $fileName"
    } else {
        $payload = @{
            tag_name   = "v$Version"
            name       = $displayName
            body       = $body
            prerelease = $true
            draft      = $false
        } | ConvertTo-Json -Depth 4
        $headers = @{ Authorization = "Bearer $token"; 'User-Agent' = 'big-fat-fish-release' }
        $release = Invoke-Api -Uri "https://api.github.com/repos/$repo/releases" -Headers $headers `
            -Body $payload -ContentType 'application/json'
        Write-Host "  release created: $($release.html_url)"
        $upload = "https://uploads.github.com/repos/$repo/releases/$($release.id)/assets?name=$fileName"
        Invoke-RestMethod -Method Post -Uri $upload -Headers $headers -InFile $Jar `
            -ContentType 'application/java-archive' | Out-Null
        Write-Host "  asset uploaded: $fileName"
    }
}

# ------------------------------------------------------------------- Modrinth
# Modrinth expects multipart/form-data with a JSON "data" part plus one file part.
function New-Multipart {
    param([string]$FieldName, [string]$DataJson, [string]$FilePath, [string]$MimeType = 'application/java-archive')
    $boundary = [guid]::NewGuid().ToString()
    $ms = New-Object System.IO.MemoryStream
    $enc = [System.Text.Encoding]::UTF8
    $nl = "`r`n"
    $part = "--$boundary$nl" +
        "Content-Disposition: form-data; name=`"$FieldName`"$nl" +
        "Content-Type: application/json$nl$nl" +
        $DataJson + $nl
    $bytes = $enc.GetBytes($part)
    $ms.Write($bytes, 0, $bytes.Length)
    $fileHeader = "--$boundary$nl" +
        "Content-Disposition: form-data; name=`"file`"; filename=`"$([System.IO.Path]::GetFileName($FilePath))`"$nl" +
        "Content-Type: $MimeType$nl$nl"
    $bytes = $enc.GetBytes($fileHeader)
    $ms.Write($bytes, 0, $bytes.Length)
    $fileBytes = [System.IO.File]::ReadAllBytes($FilePath)
    $ms.Write($fileBytes, 0, $fileBytes.Length)
    $tail = $enc.GetBytes("$nl--$boundary--$nl")
    $ms.Write($tail, 0, $tail.Length)
    return @{ Bytes = $ms.ToArray(); ContentType = "multipart/form-data; boundary=$boundary" }
}

if (-not $SkipModrinth) {
    Write-Step 'Modrinth'
    $token = $env:MODRINTH_TOKEN
    $project = $env:MODRINTH_PROJECT
    if (-not $token -or -not $project) {
        Write-Host '  skipped: set MODRINTH_TOKEN and MODRINTH_PROJECT (slug or id)'
    } else {
        # Version files need the sha hashes, Modrinth re-hashes and rejects on mismatch.
        $data = @{
            project_id     = $project
            name           = $displayName
            version_number = $Version
            changelog      = $body
            dependencies   = @()
            game_versions  = @($GameVersion)
            version_type   = 'alpha'
            loaders        = @('fabric')
            featured       = $false
            file_parts     = @('file')
            primary_file   = 'file'
        } | ConvertTo-Json -Depth 6
        if ($DryRun) {
            Write-Host "  [dry-run] POST https://api.modrinth.com/v2/version"
            Write-Host "  [dry-run] data: $data"
        } else {
            $mp = New-Multipart -FieldName 'data' -DataJson $data -FilePath $Jar
            $headers = @{ Authorization = $token; 'User-Agent' = 'big-fat-fish-release/1.0' }
            $res = Invoke-Api -Uri 'https://api.modrinth.com/v2/version' -Headers $headers `
                -Body $mp.Bytes -ContentType $mp.ContentType
            Write-Host "  uploaded version id $($res.id)"
            Write-Host "  page: https://modrinth.com/mod/$project/version/$($res.version_number)"
        }
    }
}

# ----------------------------------------------------------------- CurseForge
if (-not $SkipCurseForge) {
    Write-Step 'CurseForge'
    $token = $env:CURSEFORGE_TOKEN
    $project = $env:CURSEFORGE_PROJECT
    if (-not $token -or -not $project) {
        Write-Host '  skipped: set CURSEFORGE_TOKEN (X-Api-Token) and CURSEFORGE_PROJECT (numeric id)'
    } else {
        # CurseForge uses a JSON "metadata" part; gameVersions mix game version and loader names.
        $metadata = @{
            changelog     = $body
            changelogType = 'markdown'
            displayName   = $displayName
            releaseType   = 'alpha'
            gameVersions  = @($GameVersion, 'Fabric')
        } | ConvertTo-Json -Depth 4
        if ($DryRun) {
            Write-Host "  [dry-run] POST https://minecraft.curseforge.com/api/projects/$project/upload"
            Write-Host "  [dry-run] metadata: $metadata"
        } else {
            $mp = New-Multipart -FieldName 'metadata' -DataJson $metadata -FilePath $Jar
            $headers = @{ 'X-Api-Token' = $token }
            $res = Invoke-Api -Uri "https://minecraft.curseforge.com/api/projects/$project/upload" `
                -Headers $headers -Body $mp.Bytes -ContentType $mp.ContentType
            Write-Host "  upload result: $($res | ConvertTo-Json -Compress)"
        }
    }
}

Write-Step 'done'
Write-Host "artifact: $Jar"
if (Test-Path $Description) {
    Write-Host "project page text: $Description (paste into the Modrinth/CurseForge editor once)"
}
