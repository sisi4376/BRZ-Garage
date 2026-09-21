param(
    [switch]$Rebuild
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$simulator = Join-Path $projectRoot 'simulator\build\brz_lvgl_sim.exe'

if ($Rebuild -or -not (Test-Path -LiteralPath $simulator)) {
    & (Join-Path $PSScriptRoot 'build_native_sim.ps1')
}

Start-Process -FilePath $simulator -WorkingDirectory $projectRoot
