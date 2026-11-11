param(
    [ValidateSet('record', 'scan', 'replay')][string]$Mode = 'record',
    [string]$Address = '',
    [string]$Recording = '',
    [ValidateSet('all', 'gauge', 'zc6', 'zd8')][string]$Profile = 'all',
    [double]$Duration = 0,
    [double]$Speed = 1,
    [switch]$LoopReplay
)
$ErrorActionPreference = 'Stop'
$pythonCommand = Get-Command python -ErrorAction SilentlyContinue
if (-not $pythonCommand) { $pythonCommand = Get-Command py -ErrorAction SilentlyContinue }
if (-not $pythonCommand) { throw 'Python 3.11+ is required.' }
$pythonExecutable = $pythonCommand.Source
$venv = Join-Path $PSScriptRoot '.cache\obd-recorder-venv'
$runtime = Join-Path $venv 'Scripts\python.exe'
if (-not (Test-Path -LiteralPath $runtime)) {
    & $pythonExecutable -m venv $venv
    if ($LASTEXITCODE -ne 0) { throw 'Cannot create recorder environment.' }
}
& $runtime -m pip --version
if ($LASTEXITCODE -ne 0) {
    & $runtime -m ensurepip --upgrade --default-pip
    if ($LASTEXITCODE -ne 0) { throw 'Cannot initialize pip.' }
}
& $runtime -c 'import bleak; import winrt.windows.devices.bluetooth.genericattributeprofile; import winrt.windows.devices.radios'
if ($LASTEXITCODE -ne 0) {
    & $runtime -m pip install -r (Join-Path $PSScriptRoot 'obd_recorder_requirements.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Dependency installation failed.' }
}
$arguments = @('-X', 'utf8', '-u', (Join-Path $PSScriptRoot 'obd_recorder.py'), $Mode)
if ($Mode -eq 'record') {
    $arguments += @('--profile', $Profile, '--duration', $Duration.ToString([Globalization.CultureInfo]::InvariantCulture))
    if ($Address) { $arguments += @('--address', $Address) }
} elseif ($Mode -eq 'replay') {
    if (-not $Recording) {
        $Recording = Read-Host 'Recording JSONL file path'
        $Recording = $Recording.Trim('"')
    }
    $arguments += @($Recording, '--speed', $Speed.ToString([Globalization.CultureInfo]::InvariantCulture))
    if ($LoopReplay) { $arguments += '--loop' }
}
& $runtime @arguments
exit $LASTEXITCODE
