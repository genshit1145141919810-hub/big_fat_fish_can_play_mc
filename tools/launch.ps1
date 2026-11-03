# Launch the Fabric 1.21.11 instance from the CLI (offline account) so the agent can
# smoke-test the mod without the launcher. ASCII only.
param(
    [string]$Instance = '1.21.11-Fabric 0.20.1',
    [string]$Memory = '4G',
    [string]$Username = 'Minecoinft',
    [string]$QuickPlay = ''
)

$ErrorActionPreference = 'Stop'
$root = 'D:\Game\.minecraft'
$gameDir = Join-Path $root "versions\$Instance"
$jsonPath = Join-Path $gameDir "$Instance.json"
$jarPath = Join-Path $gameDir "$Instance.jar"
$version = Get-Content $jsonPath -Raw | ConvertFrom-Json

$natives = Join-Path $env:TEMP 'xd-natives'
New-Item -ItemType Directory -Force -Path $natives | Out-Null

# classpath: every library that actually exists + the merged game jar
. (Join-Path $PSScriptRoot 'libpath.ps1')
$cp = New-Object System.Collections.Generic.List[string]
$skipped = 0
foreach ($lib in $version.libraries) {
    $resolved = Get-LibPath -Root $root -Lib $lib
    if ($resolved) { $cp.Add($resolved) } else { $skipped++ }
}
if (Test-Path $jarPath) { $cp.Add($jarPath) }
$classpath = $cp -join ';'
Write-Host "[launch] libs=$($cp.Count) skipped=$skipped"

$replace = @{
    '${natives_directory}'    = $natives
    '${launcher_name}'        = 'agent'
    '${launcher_version}'     = '1'
    '${classpath}'            = $classpath
    '${auth_player_name}'     = $Username
    '${version_name}'         = $Instance
    '${game_directory}'       = $gameDir
    '${assets_root}'          = (Join-Path $root 'assets')
    '${assets_index_name}'    = $version.assetIndex.id
    '${auth_uuid}'            = '00000000000000000000000000000001'
    '${auth_access_token}'    = '0'
    '${user_type}'            = 'legacy'
    '${version_type}'         = 'release'
    '${clientid}'             = '0'
    '${auth_xuid}'            = '0'
}

function Expand-Args($list) {
    $out = New-Object System.Collections.Generic.List[string]
    foreach ($item in $list) {
        if ($item -is [string]) {
            $v = $item
            foreach ($k in $replace.Keys) { $v = $v.Replace($k, $replace[$k]) }
            $out.Add($v)
        }
    }
    return $out
}

$jvmArgs = Expand-Args $version.arguments.jvm
$gameArgs = Expand-Args $version.arguments.game

$java = 'C:\Users\guane\AppData\Roaming\.minecraft\runtime\java-runtime-delta\bin\java.exe'
if (-not (Test-Path $java)) { $java = 'D:\Chat\.build-tools\jdk25\jdk-25.0.5+8\bin\java.exe' }

$logDir = Join-Path $gameDir 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$log = Join-Path $logDir 'agent-launch.log'
Remove-Item $log -ErrorAction SilentlyContinue

$all = @("-Xmx$Memory") + $jvmArgs + @($version.mainClass) + $gameArgs
if ($QuickPlay) { $all += "--quickPlaySingleplayer"; $all += $QuickPlay }
$argString = ($all | ForEach-Object { if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ } }) -join ' '
Write-Host "[launch] $java"
Write-Host "[launch] cwd=$gameDir"
Write-Host "[launch] log=$log"

Start-Process -FilePath $java -ArgumentList $argString -WorkingDirectory $gameDir -RedirectStandardOutput $log -RedirectStandardError "$log.err" -PassThru | Select-Object Id,ProcessName | Format-Table -AutoSize
