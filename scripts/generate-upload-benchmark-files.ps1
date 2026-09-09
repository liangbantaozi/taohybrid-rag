param(
    [string]$SourcePath = "$PSScriptRoot\..\testdata\upload_benchmark\source.txt",
    [string]$OutputDirectory = "$PSScriptRoot\..\testdata\upload_benchmark\generated",
    [long[]]$SizeMb = @(100),
    [int]$RunsPerMode = 5,
    [ValidateSet('baseline', 'parallel')]
    [string[]]$BenchmarkMode = @('baseline', 'parallel')
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $SourcePath -PathType Leaf)) {
    throw "Source text file not found: $SourcePath"
}

if ($RunsPerMode -lt 1) {
    throw 'RunsPerMode must be at least 1.'
}

$sourceBytes = [System.IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $SourcePath))
if ($sourceBytes.Length -eq 0) {
    throw 'Source text file is empty.'
}

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

foreach ($size in $SizeMb) {
    if ($size -le 0) {
        throw 'Each SizeMb value must be positive.'
    }

    $targetBytes = $size * 1MB
    foreach ($modeName in $BenchmarkMode) {
        for ($run = 1; $run -le $RunsPerMode; $run += 1) {
            $fileName = '{0}mb-{1}-{2:d2}.txt' -f $size, $modeName, $run
            $outputPath = Join-Path $OutputDirectory $fileName
            if (Test-Path -LiteralPath $outputPath) {
                throw "Refusing to overwrite an existing benchmark file: $outputPath"
            }
            $header = [System.Text.Encoding]::UTF8.GetBytes("# TaoHybridRAG upload benchmark`r`n# run=$modeName-$run generated=$(Get-Date -Format o)`r`n`r`n")

            $stream = [System.IO.File]::Open($outputPath, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write)
            try {
                $stream.Write($header, 0, [Math]::Min($header.Length, $targetBytes))
                while ($stream.Position + $sourceBytes.Length -le $targetBytes) {
                    $stream.Write($sourceBytes, 0, $sourceBytes.Length)
                }
                while ($stream.Position -lt $targetBytes) {
                    $stream.WriteByte(10)
                }
            } finally {
                $stream.Dispose()
            }

            Write-Output "$fileName $targetBytes bytes"
        }
    }
}
