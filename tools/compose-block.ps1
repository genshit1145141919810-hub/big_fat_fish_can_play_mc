# Put a Minecraft grass block in the character's raised hand.
#
#   powershell -File tools/compose-block.ps1 -In <src.png> -Out <dst.png> [-CX 405] [-CY 185] [-BlockSize 120] [-Angle -8]
#
# Pure System.Drawing: an isometric grass block is drawn pixel-style with solid rects,
# so it looks like the real thing at small size instead of a blurry upscaled texture.
param(
    [Parameter(Mandatory = $true)][string]$In,
    [Parameter(Mandatory = $true)][string]$Out,
    [int]$CX = 405,
    [int]$CY = 185,
    [int]$BlockSize = 120,
    [double]$Angle = -8
)

Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Drawing.Drawing2D

$src = [System.Drawing.Bitmap]::FromFile($In)
$bmp = New-Object System.Drawing.Bitmap $src.Width, $src.Height, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = 'AntiAlias'
$g.InterpolationMode = 'HighQualityBicubic'
$g.DrawImage($src, 0, 0, $src.Width, $src.Height)

# ---------- build the block on its own canvas ----------
$b = New-Object System.Drawing.Bitmap 256, 256
$bg = [System.Drawing.Graphics]::FromImage($b)
$bg.SmoothingMode = 'AntiAlias'

# isometric cube metrics (256 canvas)
$originX = 128.0
$originY = 132.0
$w = 112.0   # half width of the top diamond
$h = 56.0   # half height of the top diamond
$depth = 120.0

$top = @(
    (New-Object System.Drawing.PointF ($originX), ($originY - $h)),
    (New-Object System.Drawing.PointF ($originX + $w), ($originY)),
    (New-Object System.Drawing.PointF ($originX), ($originY + $h)),
    (New-Object System.Drawing.PointF ($originX - $w), ($originY))
)
$left = @(
    (New-Object System.Drawing.PointF ($originX - $w), ($originY)),
    (New-Object System.Drawing.PointF ($originX), ($originY + $h)),
    (New-Object System.Drawing.PointF ($originX), ($originY + $h + $depth)),
    (New-Object System.Drawing.PointF ($originX - $w), ($originY + $depth))
)
$right = @(
    (New-Object System.Drawing.PointF ($originX + $w), ($originY)),
    (New-Object System.Drawing.PointF ($originX), ($originY + $h)),
    (New-Object System.Drawing.PointF ($originX), ($originY + $h + $depth)),
    (New-Object System.Drawing.PointF ($originX + $w), ($originY + $depth))
)

# dirt colours (left face darker, right face brighter)
$leftBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 116, 78, 44))
$rightBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 150, 104, 60))
$bg.FillPolygon($leftBrush, [System.Drawing.PointF[]]$left)
$bg.FillPolygon($rightBrush, [System.Drawing.PointF[]]$right)

# pixel noise on the sides so it reads as a texture, not a flat shape
$rnd = New-Object System.Random 1337
foreach ($face in @(@{ pts = $left; dark = $true }, @{ pts = $right; dark = $false })) {
    for ($i = 0; $i -lt 420; $i++) {
        $fx = $rnd.NextDouble()
        $fy = $rnd.NextDouble()
        # sample inside the parallelogram
        $x = $face.pts[0].X + ($face.pts[3].X - $face.pts[0].X) * $fy + ($face.pts[1].X - $face.pts[0].X) * $fx
        $y = $face.pts[0].Y + ($face.pts[3].Y - $face.pts[0].Y) * $fy + ($face.pts[1].Y - $face.pts[0].Y) * $fx
        $shade = $rnd.Next(-18, 19)
        $base = if ($face.dark) { @(116, 78, 44) } else { @(150, 104, 60) }
        $col = [System.Drawing.Color]::FromArgb(
            255,
            [Math]::Max(0, [Math]::Min(255, $base[0] + $shade)),
            [Math]::Max(0, [Math]::Min(255, $base[1] + $shade)),
            [Math]::Max(0, [Math]::Min(255, $base[2] + $shade)))
        $bg.FillRectangle((New-Object System.Drawing.SolidBrush $col), [single]$x, [single]$y, 3, 3)
    }
}

# grass top: green with pixel speckles
$topBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 96, 170, 60))
$bg.FillPolygon($topBrush, [System.Drawing.PointF[]]$top)
for ($i = 0; $i -lt 520; $i++) {
    $fx = $rnd.NextDouble()
    $fy = $rnd.NextDouble()
    if (($fx + $fy) -gt 1.0) { continue }
    $x = $originX + ($w * ($fx - $fy))
    $y = ($originY - $h) + ($h * ($fx + $fy))
    $shade = $rnd.Next(-22, 23)
    $col = [System.Drawing.Color]::FromArgb(
        255,
        [Math]::Max(0, [Math]::Min(255, 96 + $shade)),
        [Math]::Max(0, [Math]::Min(255, 170 + $shade)),
        [Math]::Max(0, [Math]::Min(255, 60 + $shade)))
    $bg.FillRectangle((New-Object System.Drawing.SolidBrush $col), [single]$x, [single]$y, 3, 3)
}

# grass overhang on the sides
$lipLeft = @(
    (New-Object System.Drawing.PointF ($originX - $w), ($originY)),
    (New-Object System.Drawing.PointF ($originX), ($originY + $h)),
    (New-Object System.Drawing.PointF ($originX), ($originY + $h + 12)),
    (New-Object System.Drawing.PointF ($originX - $w), ($originY + 12))
)
$lipRight = @(
    (New-Object System.Drawing.PointF ($originX + $w), ($originY)),
    (New-Object System.Drawing.PointF ($originX), ($originY + $h)),
    (New-Object System.Drawing.PointF ($originX), ($originY + $h + 12)),
    (New-Object System.Drawing.PointF ($originX + $w), ($originY + 12))
)
$lipLeftBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 74, 138, 46))
$lipRightBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 92, 158, 54))
$bg.FillPolygon($lipLeftBrush, [System.Drawing.PointF[]]$lipLeft)
$bg.FillPolygon($lipRightBrush, [System.Drawing.PointF[]]$lipRight)

# crisp edges
$edgePen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(120, 20, 20, 20)), 2
$bg.DrawPolygon($edgePen, [System.Drawing.PointF[]]$top)
$bg.DrawPolygon($edgePen, [System.Drawing.PointF[]]$left)
$bg.DrawPolygon($edgePen, [System.Drawing.PointF[]]$right)
$bg.Dispose()

# ---------- shadow + rotate + paste ----------
$shadow = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(70, 0, 0, 0))
$g.FillEllipse($shadow, $CX - $BlockSize * 0.42, $CY + $BlockSize * 0.26, $BlockSize * 0.84, $BlockSize * 0.22)

$state = $g.Save()
$g.TranslateTransform([single]$CX, [single]$CY)
$g.RotateTransform([single]$Angle)
$half = $BlockSize / 2.0
$g.DrawImage($b, [single](-$half), [single](-$half), [single]$BlockSize, [single]$BlockSize)
$g.Restore($state)

$g.Dispose()
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
$src.Dispose()
$b.Dispose()
Write-Host "wrote $Out ($((Get-Item $Out).Length) bytes)"
