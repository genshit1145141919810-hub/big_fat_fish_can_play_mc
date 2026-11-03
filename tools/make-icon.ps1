# make-icon.ps1 - generate the project icon (512x512, PNG) used by Modrinth/CurseForge
# and as the in-game mod icon.
#
#   powershell -File tools\make-icon.ps1 [-Size 512]
#
# Flat shapes on purpose: it stays readable at 64px in a mod list. Big teal fish,
# a Minecraft-ish block grid, and a small pickaxe to hint at gameplay.
param(
    [int]$Size = 512
)

Add-Type -AssemblyName System.Drawing

$bmp = New-Object System.Drawing.Bitmap $Size, $Size
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = 'AntiAlias'
$g.InterpolationMode = 'HighQualityBicubic'
$g.PixelOffsetMode = 'HighQuality'

$s = [double]$Size
function P([double]$v) { return [single]($v * $s / 512.0) }

# ---- background: deep sea gradient ----
$rect = New-Object System.Drawing.Rectangle 0, 0, $Size, $Size
$bg = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    $rect,
    [System.Drawing.Color]::FromArgb(255, 9, 16, 26),
    [System.Drawing.Color]::FromArgb(255, 20, 44, 58),
    90.0)
$g.FillRectangle($bg, $rect)

# ---- subtle block grid (Minecraft flavour) ----
$gridPen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(28, 120, 200, 190)), (P 2)
$step = P 64
for ($x = 0; $x -le $Size; $x += $step) {
    $g.DrawLine($gridPen, $x, 0, $x, $Size)
}
for ($y = 0; $y -le $Size; $y += $step) {
    $g.DrawLine($gridPen, 0, $y, $Size, $y)
}

# ---- water glow behind the fish ----
$glow = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(45, 45, 212, 191))
$g.FillEllipse($glow, (P 44), (P 150), (P 424), (P 300))

# ---- fish body ----
$bodyPath = New-Object System.Drawing.Drawing2D.GraphicsPath
$bodyPath.AddEllipse((P 108), (P 178), (P 268), (P 196))
$bodyBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    (New-Object System.Drawing.Rectangle (P 108), (P 178), (P 268), (P 196)),
    [System.Drawing.Color]::FromArgb(255, 94, 234, 212),
    [System.Drawing.Color]::FromArgb(255, 13, 148, 136),
    30.0)
$g.FillPath($bodyBrush, $bodyPath)

# ---- tail ----
$tailPath = New-Object System.Drawing.Drawing2D.GraphicsPath
$tailPath.AddPolygon(@(
    (New-Object System.Drawing.PointF (P 366), (P 276)),
    (New-Object System.Drawing.PointF (P 466), (P 196)),
    (New-Object System.Drawing.PointF (P 446), (P 276)),
    (New-Object System.Drawing.PointF (P 466), (P 356))
))
$tailBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 20, 184, 166))
$g.FillPath($tailBrush, $tailPath)

# ---- top fin ----
$finPath = New-Object System.Drawing.Drawing2D.GraphicsPath
$finPath.AddPolygon(@(
    (New-Object System.Drawing.PointF (P 196), (P 182)),
    (New-Object System.Drawing.PointF (P 244), (P 118)),
    (New-Object System.Drawing.PointF (P 286), (P 186))
))
$g.FillPath($tailBrush, $finPath)

# ---- belly highlight ----
$belly = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(70, 240, 253, 250))
$g.FillEllipse($belly, (P 150), (P 290), (P 190), (P 62))

# ---- eye ----
$white = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 248, 250, 252))
$dark = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 12, 20, 28))
$g.FillEllipse($white, (P 150), (P 226), (P 76), (P 76))
$g.FillEllipse($dark, (P 176), (P 248), (P 34), (P 34))
$g.FillEllipse($white, (P 186), (P 256), (P 12), (P 12))

# ---- pickaxe: handle + head, upper right ----
$handlePen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(255, 146, 104, 66)), (P 26)
$handlePen.StartCap = 'Round'
$handlePen.EndCap = 'Round'
$g.DrawLine($handlePen, (P 408), (P 140), (P 468), (P 66))

$headPath = New-Object System.Drawing.Drawing2D.GraphicsPath
$headPath.AddPolygon(@(
    (New-Object System.Drawing.PointF (P 372), (P 96)),
    (New-Object System.Drawing.PointF (P 402), (P 62)),
    (New-Object System.Drawing.PointF (P 468), (P 40)),
    (New-Object System.Drawing.PointF (P 486), (P 60)),
    (New-Object System.Drawing.PointF (P 446), (P 104)),
    (New-Object System.Drawing.PointF (P 414), (P 118))
))
$headBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 203, 213, 225))
$g.FillPath($headBrush, $headPath)
$headOutline = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(255, 100, 116, 139)), (P 6)
$g.DrawPath($headOutline, $headPath)

$root = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $root 'src\main\resources\assets\big_fat_fish_can_play_mc'
New-Item -ItemType Directory -Force $outDir | Out-Null
$out = Join-Path $outDir 'icon.png'
$bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)

$docsDir = Join-Path $root 'docs'
New-Item -ItemType Directory -Force $docsDir | Out-Null
$bmp.Save((Join-Path $docsDir 'icon-512.png'), [System.Drawing.Imaging.ImageFormat]::Png)

$g.Dispose()
$bmp.Dispose()
Write-Host "icon: $out ($((Get-Item $out).Length) bytes, $Size x $Size)"
