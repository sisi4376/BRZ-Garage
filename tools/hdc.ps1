$toolDirectory = Join-Path $PSScriptRoot '..\.toolchains\hdc\toolchains-3.2.3.6\toolchains'
$hdcExecutable = Join-Path $toolDirectory 'hdc.exe'

if (-not (Test-Path -LiteralPath $hdcExecutable -PathType Leaf)) {
    Write-Error "HDC is not installed at: $hdcExecutable"
    exit 1
}

Push-Location -LiteralPath $toolDirectory
try {
    & $hdcExecutable @args
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
