param(
    [ValidateSet('auto', 'elm327', 'demo')]
    [string]$Source = 'auto',
    [string]$ElmHost = '127.0.0.1',
    [int]$ElmPort = 35000,
    [int]$Port = 8080
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$server = Join-Path $PSScriptRoot 'preview_server.py'
$nativeSimulator = Join-Path $projectRoot 'simulator\build\brz_lvgl_sim.exe'
$nativeProcess = $null

$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) {
    $python = Get-Command py -ErrorAction SilentlyContinue
}
if (-not $python) {
    $bundledPython = Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
    if (Test-Path -LiteralPath $bundledPython) {
        $python = Get-Item -LiteralPath $bundledPython
    }
}
if (-not $python) {
    throw '未找到 Python。请安装 Python 3.9+（ELM327-emulator 本身也需要 Python）。'
}
$pythonExecutable = if ($python.Source) { $python.Source } else { $python.FullName }

Set-Location -LiteralPath $projectRoot
if (-not (Test-Path -LiteralPath $nativeSimulator)) {
    & (Join-Path $PSScriptRoot 'build_native_sim.ps1')
}

try {
    $nativeArgs = @('--headless', '--elm-host', $ElmHost, '--elm-port', [string]$ElmPort)
    if ($Source -eq 'demo') { $nativeArgs += '--demo' }
    $nativeProcess = Start-Process -FilePath $nativeSimulator -ArgumentList $nativeArgs -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru
    & $pythonExecutable $server --source demo --elm-host $ElmHost --elm-port $ElmPort --port $Port
}
finally {
    if ($nativeProcess -and -not $nativeProcess.HasExited) {
        Stop-Process -Id $nativeProcess.Id
    }
}
