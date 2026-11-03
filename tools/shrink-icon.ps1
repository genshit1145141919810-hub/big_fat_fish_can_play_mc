Add-Type -AssemblyName System.Drawing

# Downscale the square promo art to a 512 icon for Modrinth/CurseForge.
$src = 'D:\Chat\deepseek-companion\docs\promo-maid.png'
$out = 'D:\Chat\deepseek-companion\docs\icon-512-maid.png'

$image = [System.Drawing.Bitmap]::FromFile($src)
Write-Host ("source: {0}x{1}" -f $image.Width, $image.Height)

$dst = New-Object System.Drawing.Bitmap 512, 512, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($dst)
$g.InterpolationMode = 'HighQualityBicubic'
$g.PixelOffsetMode = 'HighQuality'
$g.SmoothingMode = 'AntiAlias'
$g.DrawImage($image, (New-Object System.Drawing.Rectangle 0, 0, 512, 512))
$g.Dispose()
$dst.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$dst.Dispose()
$image.Dispose()
Write-Host "wrote $out"
