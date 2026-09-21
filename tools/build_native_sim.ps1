param(
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$make = Join-Path $projectRoot 'tools\.cache\w64devkit\bin\make.exe'

if (-not (Test-Path -LiteralPath $make)) {
    throw 'Portable GCC is missing. Download/extract w64devkit into tools\.cache\w64devkit first.'
}

Push-Location $projectRoot
try {
    if ($Clean) {
        & $make -f simulator/Makefile clean
        if ($LASTEXITCODE -ne 0) { throw 'Native simulator clean failed.' }
    }
    & $make -f simulator/Makefile -j ([Environment]::ProcessorCount)
    if ($LASTEXITCODE -ne 0) { throw 'Native simulator build failed.' }
}
finally {
    Pop-Location
}
