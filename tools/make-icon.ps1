# Generate a 128x128 project icon for the mod (Modrinth/CurseForge want one).
Add-Type -AssemblyName System.Drawing

$size = 128
$bmp = New-Object System.Drawing.Bitmap $size, $size
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = 'AntiAlias'
$g.TextRenderingHint = 'AntiAlias'

# background: dark slate with a soft gradient
$rect = New-Object System.Drawing.Rectangle 0, 0, $size, $size
$bg = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    $rect,
    [System.Drawing.Color]::FromArgb(255, 22, 27, 34),
    [System.Drawing.Color]::FromArgb(255, 34, 48, 60),
    45.0)
$g.FillRectangle($bg, $rect)

# glowing circle
$circleBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    (New-Object System.Drawing.Rectangle 14, 14, 100, 100),
    [System.Drawing.Color]::FromArgb(255, 74, 222, 128),
    [System.Drawing.Color]::FromArgb(255, 16, 185, 129),
    90.0)
$g.FillEllipse($circleBrush, 14, 14, 100, 100)

# letter D
$font = New-Object System.Drawing.Font('Segoe UI', 62, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
$textBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 12, 18, 24))
$format = New-Object System.Drawing.StringFormat
$format.Alignment = 'Center'
$format.LineAlignment = 'Center'
$g.DrawString('D', $font, $textBrush, (New-Object System.Drawing.RectangleF 0, 0, $size, $size), $format)

# small pickaxe dot to hint at gameplay
$g.FillEllipse((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 250, 204, 21))), 92, 92, 18, 18)

$outDir = 'D:\Chat\deepseek-companion\src\main\resources\assets\deepseekcompanion'
New-Item -ItemType Directory -Force $outDir | Out-Null
$out = Join-Path $outDir 'icon.png'
$bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose()
$bmp.Dispose()
$font.Dispose()
Write-Host "icon: $out ($((Get-Item $out).Length) bytes)"
