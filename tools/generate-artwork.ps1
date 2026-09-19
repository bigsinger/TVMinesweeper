# Draw small, original launcher artwork without network assets or native app dependencies.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$outputDirectory = Join-Path (Split-Path -Parent $PSScriptRoot) 'app\src\main\res\drawable-nodpi'
New-Item -ItemType Directory -Force $outputDirectory | Out-Null
function Draw-BoardIcon([Drawing.Graphics]$graphics, [single]$x, [single]$y, [single]$size) {
    $cellSize = $size / 3
    $tileBrush = New-Object Drawing.SolidBrush([Drawing.ColorTranslator]::FromHtml('#21364C'))
    $accentBrush = New-Object Drawing.SolidBrush([Drawing.ColorTranslator]::FromHtml('#49DAC5'))
    $focusPen = New-Object Drawing.Pen([Drawing.ColorTranslator]::FromHtml('#74F4DA'), ($size / 36))
    $flagBrush = New-Object Drawing.SolidBrush([Drawing.ColorTranslator]::FromHtml('#FFD271'))
    for ($row = 0; $row -lt 3; $row++) {
        for ($column = 0; $column -lt 3; $column++) {
            $graphics.FillRectangle($tileBrush, ($x + $column * $cellSize + 3), ($y + $row * $cellSize + 3), ($cellSize - 6), ($cellSize - 6))
        }
    }
    $graphics.DrawRectangle($focusPen, ($x + $cellSize + 2), ($y + $cellSize + 2), ($cellSize - 4), ($cellSize - 4))
    $poleX = $x + 1.38 * $cellSize
    $topY = $y + 1.20 * $cellSize
    $graphics.FillRectangle($accentBrush, $poleX, $topY, ($size / 38), ($cellSize * 0.62))
    $points = [Drawing.PointF[]]@([Drawing.PointF]::new($poleX, $topY), [Drawing.PointF]::new(($poleX + 0.40 * $cellSize), ($topY + 0.15 * $cellSize)), [Drawing.PointF]::new($poleX, ($topY + 0.32 * $cellSize)))
    $graphics.FillPolygon($flagBrush, $points)
    $graphics.FillRectangle($accentBrush, ($poleX - 0.10 * $cellSize), ($topY + 0.62 * $cellSize), ($cellSize * 0.30), ($size / 38))
    $numberFont = [Drawing.Font]::new('Segoe UI', ($cellSize * 0.44), [Drawing.FontStyle]::Bold, [Drawing.GraphicsUnit]::Pixel)
    $graphics.DrawString('1', $numberFont, $accentBrush, ($x + $cellSize * 0.32), ($y + $cellSize * 0.20))
    $graphics.DrawString('2', $numberFont, $accentBrush, ($x + $cellSize * 2.32), ($y + $cellSize * 2.20))
    $numberFont.Dispose(); $tileBrush.Dispose(); $accentBrush.Dispose(); $focusPen.Dispose(); $flagBrush.Dispose()
}
$icon = [Drawing.Bitmap]::new(192, 192)
$iconGraphics = [Drawing.Graphics]::FromImage($icon)
$iconGraphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
$iconGraphics.Clear([Drawing.ColorTranslator]::FromHtml('#0B1525'))
Draw-BoardIcon $iconGraphics 17 17 158
$icon.Save((Join-Path $outputDirectory 'ic_launcher.png'), [Drawing.Imaging.ImageFormat]::Png)
$iconGraphics.Dispose(); $icon.Dispose()
$banner = [Drawing.Bitmap]::new(320, 180)
$bannerGraphics = [Drawing.Graphics]::FromImage($banner)
$bannerGraphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
$bannerGraphics.TextRenderingHint = [Drawing.Text.TextRenderingHint]::AntiAliasGridFit
$bannerGraphics.Clear([Drawing.ColorTranslator]::FromHtml('#0B1525'))
Draw-BoardIcon $bannerGraphics 23 46 92
$titleFont = [Drawing.Font]::new('Microsoft YaHei', 29, [Drawing.FontStyle]::Bold, [Drawing.GraphicsUnit]::Pixel)
$captionFont = [Drawing.Font]::new('Segoe UI', 12, [Drawing.FontStyle]::Regular, [Drawing.GraphicsUnit]::Pixel)
$titleBrush = [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml('#EDF8FC'))
$captionBrush = [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml('#62DDC9'))
$bannerGraphics.DrawString(([string][char]0x7535 + [char]0x89C6 + [char]0x626B + [char]0x96F7), $titleFont, $titleBrush, 131, 59)
$bannerGraphics.DrawString('TV MINESWEEPER', $captionFont, $captionBrush, 135, 104)
$banner.Save((Join-Path $outputDirectory 'tv_banner.png'), [Drawing.Imaging.ImageFormat]::Png)
$titleFont.Dispose(); $captionFont.Dispose(); $titleBrush.Dispose(); $captionBrush.Dispose(); $bannerGraphics.Dispose(); $banner.Dispose()
