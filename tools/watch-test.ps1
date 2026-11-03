# Watch one test run and return the moment it settles, instead of sleeping blindly.
#
#   powershell -File tools\watch-test.ps1 -Log <agent-launch.log> [-TimeoutSec 900] [-Offset <bytes>]
#
# Exit codes: 0 = success marker, 2 = failure marker, 3 = timeout.
param(
    [Parameter(Mandatory = $true)][string]$Log,
    [int]$TimeoutSec = 900,
    [long]$Offset = -1
)

$ErrorActionPreference = 'Stop'

$waited = 0
while (-not (Test-Path $Log) -and $waited -lt 120) {
    Start-Sleep -Seconds 2
    $waited += 2
}
if (-not (Test-Path $Log)) {
    Write-Output '[watch] log never appeared'
    exit 3
}
if ($Offset -lt 0) { $Offset = 0 }

$success = 'acquire finished|弄完了：'
$failure = 'acquire stage failed|弄不到 '
$interesting = 'acquire|掉落表|变种表|重新测算|还差|baritone 挖|同族里|材料不够|界面没打开|走不到|合成格'

$deadline = (Get-Date).AddSeconds($TimeoutSec)
$result = 3
$stream = [System.IO.File]::Open($Log, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
try {
    $stream.Seek($Offset, [System.IO.SeekOrigin]::Begin) | Out-Null
    $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 700
        while (-not $reader.EndOfStream) {
            $line = $reader.ReadLine()
            if ($null -eq $line) { break }
            if ($line -notmatch $interesting) { continue }
            if ($line.Length -gt 160) { $line = $line.Substring(0, 160) }
            Write-Output $line
            if ($line -match $success) { $result = 0 }
            if ($line -match $failure) { $result = 2 }
            if ($result -ne 3) { break }
        }
        if ($result -ne 3) { break }
        $alive = Get-Process java -ErrorAction SilentlyContinue
        if (-not $alive) {
            Write-Output '[watch] game exited'
            $result = 2
            break
        }
    }
    $reader.Dispose()
} finally {
    $stream.Dispose()
}
Write-Output "[watch] result=$result"
exit $result
