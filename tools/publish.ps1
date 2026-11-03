# publish.ps1 - build artifacts and push them to GitHub / Modrinth / CurseForge.
#
# Credentials come from environment variables (never hardcode them):
#   GITHUB_TOKEN, GITHUB_REPO (owner/name)
#   MODRINTH_TOKEN, MODRINTH_PROJECT (project id or slug)
#   CURSEFORGE_TOKEN, CURSEFORGE_PROJECT (numeric project id)
#
# Without a given platform's variables the script just reports what is missing and
# continues with the others, so a partial setup still works.
param(
    [string]$Version = '0.1.0-alpha.1',
    [string]$Jar = '',
    [string]$Changelog = 'D:\Chat\deepseek-companion\CHANGELOG.md',
    [string]$Proxy = 'http://127.0.0.1:7890',
    [switch]$SkipGitHub,
    [switch]$SkipModrinth,
    [switch]$SkipCurseForge
)

$ErrorActionPreference = 'Stop'
$root = 'D:\Chat\deepseek-companion'
if (-not $Jar) { $Jar = Join-Path $root "build\libs\deepseek-companion-$Version.jar" }
if (-not (Test-Path $Jar)) { throw "jar not found: $Jar (build first)" }

$body = ''
if (Test-Path $Changelog) {
    $body = Get-Content $Changelog -Raw
    # keep the section for this version if present
    $marker = "## $Version"
    $idx = $body.IndexOf($marker)
    if ($idx -ge 0) { $body = $body.Substring($idx) }
}

function Write-Step($text) { Write-Host "== $text" -ForegroundColor Cyan }

# ---------------------------------------------------------------- GitHub release
if (-not $SkipGitHub) {
    Write-Step 'GitHub release'
    $token = $env:GITHUB_TOKEN
    $repo = $env:GITHUB_REPO
    if (-not $token -or -not $repo) {
        Write-Host '  skipped: set GITHUB_TOKEN and GITHUB_REPO (e.g. yourname/deepseek-companion)'
    } else {
        $api = "https://api.github.com/repos/$repo/releases"
        $payload = @{
            tag_name   = "v$Version"
            name       = "v$Version"
            body       = $body
            prerelease = $true
            draft      = $false
        } | ConvertTo-Json -Depth 4
        $headers = @{ Authorization = "Bearer $token"; 'User-Agent' = 'deepseek-companion-release' }
        $release = Invoke-RestMethod -Method Post -Uri $api -Headers $headers -Body $payload -ContentType 'application/json'
        Write-Host "  release created: $($release.html_url)"
        $upload = "https://uploads.github.com/repos/$repo/releases/$($release.id)/assets?name=$([System.IO.Path]::GetFileName($Jar))"
        Invoke-RestMethod -Method Post -Uri $upload -Headers $headers -InFile $Jar -ContentType 'application/java-archive' | Out-Null
        Write-Host "  asset uploaded: $([System.IO.Path]::GetFileName($Jar))"
    }
}

# ------------------------------------------------------------------- Modrinth
function New-Multipart($DataJson, $FilePath) {
    $boundary = [guid]::NewGuid().ToString()
    $ms = New-Object System.IO.MemoryStream
    $enc = [System.Text.Encoding]::UTF8
    $nl = "`r`n"
    $part = "--$boundary$nl" +
        "Content-Disposition: form-data; name=`"data`"$nl" +
        "Content-Type: application/json$nl$nl" +
        $DataJson + $nl
    $bytes = $enc.GetBytes($part)
    $ms.Write($bytes, 0, $bytes.Length)
    $fileBytes = [System.IO.File]::ReadAllBytes($FilePath)
    $fileHeader = "--$boundary$nl" +
        "Content-Disposition: form-data; name=`"file`"; filename=`"$([System.IO.Path]::GetFileName($FilePath))`"$nl" +
        "Content-Type: application/java-archive$nl$nl"
    $bytes = $enc.GetBytes($fileHeader)
    $ms.Write($bytes, 0, $bytes.Length)
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
        Write-Host '  skipped: set MODRINTH_TOKEN and MODRINTH_PROJECT'
    } else {
        $data = @{
            project_id     = $project
            name           = "DeepSeek Companion $Version"
            version_number = $Version
            changelog      = $body
            dependencies   = @()
            game_versions  = @('1.21.11')
            version_type   = 'alpha'
            loaders        = @('fabric')
            featured       = $false
        } | ConvertTo-Json -Depth 5
        $mp = New-Multipart $data $Jar
        $headers = @{ Authorization = $token; 'User-Agent' = 'deepseek-companion-release' }
        $res = Invoke-RestMethod -Method Post -Uri 'https://api.modrinth.com/v2/version' -Headers $headers `
            -Body $mp.Bytes -ContentType $mp.ContentType
        Write-Host "  uploaded version id $($res.id)"
    }
}

# ----------------------------------------------------------------- CurseForge
if (-not $SkipCurseForge) {
    Write-Step 'CurseForge'
    $token = $env:CURSEFORGE_TOKEN
    $project = $env:CURSEFORGE_PROJECT
    if (-not $token -or -not $project) {
        Write-Host '  skipped: set CURSEFORGE_TOKEN and CURSEFORGE_PROJECT'
    } else {
        $meta = @{
            changelog     = $body
            changelogType = 'markdown'
            displayName   = "DeepSeek Companion $Version"
            releaseType   = 'alpha'
            gameVersions  = @('1.21.11', 'Fabric')
        } | ConvertTo-Json -Depth 4
        $mp = New-Multipart $meta $Jar
        $headers = @{ 'X-Api-Token' = $token }
        $res = Invoke-RestMethod -Method Post `
            -Uri "https://minecraft.curseforge.com/api/projects/$project/upload" `
            -Headers $headers -Body $mp.Bytes -ContentType $mp.ContentType
        Write-Host "  upload result: $($res | ConvertTo-Json -Compress)"
    }
}

Write-Step 'done'
Write-Host "artifact: $Jar"
