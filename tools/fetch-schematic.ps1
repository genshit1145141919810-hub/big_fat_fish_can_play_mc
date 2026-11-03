# fetch-schematic.ps1 - install a Litematica schematic (.litematic, or a zip containing them)
# into a Minecraft instance's schematics folder.
#
# Examples:
#   .\tools\fetch-schematic.ps1 -Url "https://raw.githubusercontent.com/Arcadi4/LitematicaQL/main/Fixtures/Demos/Cottage.litematic"
#   .\tools\fetch-schematic.ps1 -Url "https://example.com/house.zip" -Instance "1.21.11-Fabric 0.19.2"
#
# In game: `.xd schematic` lists them, `.xd schematic <name>` reports the material list.
# (ASCII only on purpose: Windows PowerShell mangles non-BOM UTF-8 script text.)

param(
    [Parameter(Mandatory = $true)][string]$Url,
    [string]$Instance = '1.21.11-Fabric 0.20.1',
    [string]$Dest = '',
    [string]$Proxy = 'http://127.0.0.1:7890'
)

$ErrorActionPreference = 'Stop'
$root = 'D:\Game\.minecraft\versions'
if ([string]::IsNullOrWhiteSpace($Dest)) {
    $Dest = Join-Path (Join-Path $root $Instance) 'schematics'
}
New-Item -ItemType Directory -Force -Path $Dest | Out-Null

$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ('xd-fetch-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$raw = Join-Path $tmp 'download.bin'

Write-Host "[fetch] $Url"
curl.exe -sS -L -x $Proxy -H "User-Agent: deepseek-companion-fetch" -o $raw $Url
if (-not (Test-Path $raw)) { throw 'download failed' }
$size = (Get-Item $raw).Length
if ($size -lt 64) { throw "downloaded only $size bytes - probably an error page" }

$magic = [System.IO.File]::ReadAllBytes($raw)[0..1]
$installed = @()

if ($magic[0] -eq 0x1F -and $magic[1] -eq 0x8B) {
    # gzip header: the payload itself is a .litematic
    $name = [System.IO.Path]::GetFileName(([uri]$Url).AbsolutePath)
    if ([string]::IsNullOrWhiteSpace($name) -or -not $name.EndsWith('.litematic')) {
        $name = 'download.litematic'
    }
    $target = Join-Path $Dest $name
    Copy-Item $raw $target -Force
    $installed += $target
}
elseif ($magic[0] -eq 0x50 -and $magic[1] -eq 0x4B) {
    # zip: pull every .litematic out of it
    $zipPath = Join-Path $tmp 'download.zip'
    Move-Item $raw $zipPath -Force
    $unzip = Join-Path $tmp 'unzipped'
    Expand-Archive -Path $zipPath -DestinationPath $unzip -Force
    $found = Get-ChildItem -Recurse -File -Filter '*.litematic' $unzip
    if (-not $found) { throw 'zip contains no .litematic' }
    foreach ($file in $found) {
        $target = Join-Path $Dest $file.Name
        Copy-Item $file.FullName $target -Force
        $installed += $target
    }
}
else {
    $hex = ('{0:X2} {1:X2}' -f $magic[0], $magic[1])
    throw "unknown file type (header $hex) - not a .litematic and not a zip"
}

Write-Host "[ok] installed into $Dest"
foreach ($file in $installed) {
    Write-Host ('  - {0}  {1} bytes' -f (Split-Path $file -Leaf), (Get-Item $file).Length)
}
Write-Host '[tip] in game: .xd schematic  |  .xd schematic <name>'
Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
