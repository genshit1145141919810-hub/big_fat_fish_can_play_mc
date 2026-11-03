Add-Type -AssemblyName System.IO.Compression.FileSystem
$jar = 'D:\Chat\deepseek-companion\build\libs\big-fat-fish-can-play-mc-0.4.0-alpha.2.jar'
$zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
$entries = $zip.Entries | Where-Object { $_.FullName -like '*lang/*' -or $_.FullName -like '*icon.png' }
foreach ($entry in $entries) {
    Write-Host ("{0}  ({1} bytes)" -f $entry.FullName, $entry.Length)
}
$zip.Dispose()
