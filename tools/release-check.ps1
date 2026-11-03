# release-check.ps1 - validate the built jar before publishing.
# Checks: version string, required resources, icon, mixin configs, no Baritone bundled,
# and that the jar actually carries the class files the mixins touch.
param(
    [string]$Jar = 'D:\Chat\deepseek-companion\build\libs\big-fat-fish-can-play-mc-0.3.0-alpha.1.jar'
)

Add-Type -AssemblyName System.IO.Compression.FileSystem
$problems = @()
$notes = @()

if (-not (Test-Path $Jar)) { Write-Host "jar not found: $Jar"; exit 1 }
Write-Host "jar: $Jar ($((Get-Item $Jar).Length) bytes)"

$zip = [System.IO.Compression.ZipFile]::OpenRead($Jar)
$names = $zip.Entries | ForEach-Object { $_.FullName }

function Read-Json($path) {
    $entry = $zip.Entries | Where-Object { $_.FullName -eq $path } | Select-Object -First 1
    if (-not $entry) { return $null }
    $reader = New-Object System.IO.StreamReader($entry.Open())
    $text = $reader.ReadToEnd()
    $reader.Close()
    return ($text | ConvertFrom-Json)
}

$mod = Read-Json 'fabric.mod.json'
if (-not $mod) {
    $problems += 'fabric.mod.json missing'
} else {
    $notes += "id=$($mod.id) name=$($mod.name)"
    if ($mod.version -match '\$\{') {
        $problems += 'version still contains a build placeholder'
    } else {
        $notes += "version=$($mod.version)"
    }
    if (-not $mod.icon) { $problems += 'no icon declared' }
    elseif (-not ($names -contains $mod.icon)) { $problems += "icon declared but missing in jar: $($mod.icon)" }
    if (-not $mod.license) { $problems += 'no license field' }
    if (-not $mod.contact) { $notes += 'contact missing (optional, but stores like it)' }
    elseif ("$($mod.contact.homepage)" -match 'REPLACE_ME') { $problems += 'contact.homepage still has REPLACE_ME' }
    foreach ($m in $mod.mixins) {
        if (-not ($names -contains $m)) { $problems += "mixin config missing: $m" }
    }
}

foreach ($required in @(
    'assets/big_fat_fish_can_play_mc/crafting.json',
    'assets/big_fat_fish_can_play_mc/smelting.json',
    'assets/big_fat_fish_can_play_mc/blasting.json',
    'assets/big_fat_fish_can_play_mc/smoking.json',
    'assets/big_fat_fish_can_play_mc/campfire_cooking.json',
    'io/github/xiaod/deepseekcompanion/DeepseekCompanionClient.class',
    'io/github/xiaod/deepseekcompanion/bot/PlanRunner.class',
    'io/github/xiaod/deepseekcompanion/bot/TestDriver.class',
    'io/github/xiaod/deepseekcompanion/integrations/CraftTree.class',
    'io/github/xiaod/deepseekcompanion/integrations/LitematicaSchematics.class',
    'io/github/xiaod/deepseekcompanion/integrations/SeedcrackerX.class'
)) {
    if (-not ($names -contains $required)) { $problems += "missing in jar: $required" }
}

# we must NOT redistribute Baritone
$baritoneClasses = $names | Where-Object { $_ -like 'baritone/*' }
if ($baritoneClasses) { $problems += 'jar contains baritone classes - do not redistribute' }

# test-only driver is fine to ship (it is inert without the command file) but say so
$notes += 'TestDriver ships: inert unless <gameDir>/xd-test-commands.txt exists'

$zip.Dispose()

$notes | ForEach-Object { Write-Host "  note: $_" }
if ($problems.Count -eq 0) {
    Write-Host 'CHECK OK - ready to publish'
    exit 0
}
$problems | ForEach-Object { Write-Host "  PROBLEM: $_" }
Write-Host "$($problems.Count) problem(s)"
exit 1
