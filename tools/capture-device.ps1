[CmdletBinding()]
param([string]$Name = 'game', [int]$Width = 1200)
$ErrorActionPreference = 'Stop'
if ($Name -notmatch '^[a-zA-Z0-9_-]+$') { throw 'Use a simple capture name.' }
$workDir = 'E:\temp\TVMinesweeper'
New-Item -ItemType Directory -Force -Path $workDir | Out-Null
$original = Join-Path $workDir "$Name-original.png"
$compressed = Join-Path $workDir "$Name.jpg"
$adbCommand = (Get-Command adb.exe).Source
# Read the Android framebuffer directly; no desktop activation or obstructed window capture.
$process = New-Object Diagnostics.Process
$process.StartInfo.FileName = $adbCommand
$process.StartInfo.Arguments = 'exec-out screencap -p'
$process.StartInfo.UseShellExecute = $false
$process.StartInfo.CreateNoWindow = $true
$process.StartInfo.RedirectStandardOutput = $true
$stream = [IO.File]::Create($original)
try {
    [void]$process.Start()
    $process.StandardOutput.BaseStream.CopyTo($stream)
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw 'Device capture failed.' }
} finally { $stream.Dispose(); $process.Dispose() }
Add-Type -AssemblyName System.Drawing
$source = [Drawing.Image]::FromFile($original)
try {
    $targetWidth = [Math]::Min($Width, $source.Width)
    $targetHeight = [int][Math]::Round($source.Height * $targetWidth / $source.Width)
    $target = New-Object Drawing.Bitmap($targetWidth, $targetHeight)
    $graphics = [Drawing.Graphics]::FromImage($target)
    try {
        $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.DrawImage($source, 0, 0, $targetWidth, $targetHeight)
        $codec = [Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' }
        $parameters = New-Object Drawing.Imaging.EncoderParameters(1)
        $parameters.Param[0] = New-Object Drawing.Imaging.EncoderParameter([Drawing.Imaging.Encoder]::Quality, [long]72)
        try { $target.Save($compressed, $codec, $parameters) } finally { $parameters.Dispose() }
    } finally { $graphics.Dispose(); $target.Dispose() }
} finally { $source.Dispose() }
# Prefer local OCR output before opening the already compressed screenshot for layout review.
try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    $null = [Windows.Storage.StorageFile, Windows.Storage, ContentType=WindowsRuntime]
    $null = [Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics.Imaging, ContentType=WindowsRuntime]
    $null = [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType=WindowsRuntime]
    $null = [Windows.Globalization.Language, Windows.Globalization, ContentType=WindowsRuntime]
    $asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
        $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.IsGenericMethod
    } | Select-Object -First 1
    function Await-Result($operation, $type) {
        $task = $asTask.MakeGenericMethod($type).Invoke($null, @($operation))
        $task.Wait()
        return $task.Result
    }
    $file = Await-Result ([Windows.Storage.StorageFile]::GetFileFromPathAsync($original)) ([Windows.Storage.StorageFile])
    $readStream = Await-Result ($file.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
    try {
        $decoder = Await-Result ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($readStream)) ([Windows.Graphics.Imaging.BitmapDecoder])
        $bitmap = Await-Result ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
        try {
            $ocr = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
            if ($null -eq $ocr) { throw 'No local OCR language is installed.' }
            $result = Await-Result ($ocr.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
            [IO.File]::WriteAllText((Join-Path $workDir "$Name-ocr.txt"), $result.Text, [Text.UTF8Encoding]::new($false))
            Write-Output $result.Text
        } finally { $bitmap.Dispose() }
    } finally { $readStream.Dispose() }
} catch { Write-Warning "Local OCR unavailable: $($_.Exception.Message)" }
Get-Item -LiteralPath $compressed | Select-Object FullName, Length
