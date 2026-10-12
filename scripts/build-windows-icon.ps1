$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$outputPath = Join-Path (Split-Path $PSScriptRoot -Parent) 'agent/Pult.Agent/Assets/pult.ico'
$frames = foreach ($size in @(16, 24, 32, 48, 64, 128, 256)) {
    $bitmap = New-Object Drawing.Bitmap($size, $size)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.ScaleTransform($size / 256.0, $size / 256.0)
    $background = New-Object Drawing.Drawing2D.GraphicsPath
    $background.AddArc(4, 4, 104, 104, 180, 90)
    $background.AddArc(148, 4, 104, 104, 270, 90)
    $background.AddArc(148, 148, 104, 104, 0, 90)
    $background.AddArc(4, 148, 104, 104, 90, 90)
    $background.CloseFigure()
    $brush = New-Object Drawing.SolidBrush([Drawing.ColorTranslator]::FromHtml('#101011'))
    $graphics.FillPath($brush, $background)
    $ring = New-Object Drawing.Pen([Drawing.ColorTranslator]::FromHtml('#F04E48'), 20)
    $stem = New-Object Drawing.Pen([Drawing.ColorTranslator]::FromHtml('#EEEBDF'), 20)
    $ring.StartCap = $ring.EndCap = $stem.StartCap = $stem.EndCap = [Drawing.Drawing2D.LineCap]::Round
    $graphics.DrawArc($ring, 60, 66, 136, 136, 306, 288)
    $graphics.DrawLine($stem, 128, 58, 128, 130)
    $png = New-Object IO.MemoryStream
    $bitmap.Save($png, [Drawing.Imaging.ImageFormat]::Png)
    [pscustomobject]@{ Size = $size; Bytes = $png.ToArray() }
    $png.Dispose(); $graphics.Dispose(); $bitmap.Dispose(); $background.Dispose(); $brush.Dispose(); $ring.Dispose(); $stem.Dispose()
}
$file = [IO.File]::Create($outputPath)
$writer = New-Object IO.BinaryWriter($file)
try {
    $writer.Write([uint16]0); $writer.Write([uint16]1); $writer.Write([uint16]$frames.Count)
    $offset = 6 + 16 * $frames.Count
    foreach ($frame in $frames) {
        $dimension = if ($frame.Size -eq 256) { 0 } else { $frame.Size }
        $writer.Write([byte]$dimension); $writer.Write([byte]$dimension); $writer.Write([byte]0); $writer.Write([byte]0)
        $writer.Write([uint16]1); $writer.Write([uint16]32)
        $writer.Write([uint32]$frame.Bytes.Length); $writer.Write([uint32]$offset)
        $offset += $frame.Bytes.Length
    }
    foreach ($frame in $frames) { $writer.Write([byte[]]$frame.Bytes) }
} finally { $writer.Dispose(); $file.Dispose() }
Write-Output "Generated $outputPath"
