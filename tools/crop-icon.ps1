Add-Type -AssemblyName System.Drawing

# Square 512 icon from the portrait render: crop around head + raised block, then upscale.
$srcPath = 'D:\Chat\deepseek-companion\docs\char-with-block.png'
$outPath = 'D:\Chat\deepseek-companion\docs\icon-512-char.png'

$src = [System.Drawing.Bitmap]::FromFile($srcPath)
$size = 480
$x = [int](($src.Width - $size) / 2)
$y = 40
if ($x -lt 0) { $x = 0 }
if ($x + $size -gt $src.Width) { $x = $src.Width - $size }

$dst = New-Object System.Drawing.Bitmap 512, 512, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($dst)
$g.InterpolationMode = 'HighQualityBicubic'
$g.PixelOffsetMode = 'HighQuality'
$g.SmoothingMode = 'AntiAlias'
$g.DrawImage($src, (New-Object System.Drawing.Rectangle 0, 0, 512, 512),
    (New-Object System.Drawing.Rectangle $x, $y, $size, $size),
    [System.Drawing.GraphicsUnit]::Pixel)
$g.Dispose()
$dst.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)
$dst.Dispose()
$src.Dispose()
Write-Host "wrote $outPath"
