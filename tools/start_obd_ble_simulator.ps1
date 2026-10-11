param(
    [switch]$Check,
    [switch]$VerboseLog,
    [double]$Duration = 0
)

$ErrorActionPreference = 'Stop'
$pythonCommand = Get-Command python -ErrorAction SilentlyContinue
if (-not $pythonCommand) { $pythonCommand = Get-Command py -ErrorAction SilentlyContinue }
if (-not $pythonCommand) { throw 'Python 3.10+ is required.' }
$pythonExecutable = $pythonCommand.Source
$cache = Join-Path $PSScriptRoot '.cache\obd-ble-python'
$requirements = Join-Path $PSScriptRoot 'obd_ble_requirements.txt'

# Verify with this interpreter: cached native wheels can belong to another Python.
$importCheck = @'
import sys
sys.path.insert(0, sys.argv[1])
try:
    import winrt.windows.devices.bluetooth.genericattributeprofile
    import winrt.windows.devices.radios
    import winrt.windows.storage.streams
except ImportError:
    sys.exit(1)
'@
& $pythonExecutable -c $importCheck $cache
if ($LASTEXITCODE -ne 0) {
    Write-Host 'Installing Windows BLE dependencies into the project cache...'
    & $pythonExecutable -m pip install --upgrade --target $cache -r $requirements
    if ($LASTEXITCODE -ne 0) { throw 'BLE dependency installation failed.' }
}

$arguments = @('-X', 'utf8', '-u', (Join-Path $PSScriptRoot 'obd_ble_simulator.py'), '--duration', $Duration.ToString([Globalization.CultureInfo]::InvariantCulture))
if ($Check) { $arguments += '--check' }
if ($VerboseLog) { $arguments += '--verbose' }
& $pythonExecutable @arguments
# Python already prints the actionable error. Preserve its exit code without
# adding an unrelated PowerShell exception and script stack trace.
exit $LASTEXITCODE
