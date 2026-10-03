param([Parameter(Mandatory=$true)][string]$PackageRoot,
      [string]$Port, [string]$RunDirectory,
      [switch]$FirstInstall, [switch]$Confirmed, [switch]$ValidateOnly)
try {
    . (Join-Path $PSScriptRoot 'flash_core.ps1')
    if ($ValidateOnly) {
        $package = Read-FlashPackage $PackageRoot
        Write-Output "PASS: Package $($package.version), all firmware/tool hashes verified. No device accessed."
    } else {
        if (-not $Confirmed) { throw 'Explicit confirmation is required before accessing a device.' }
        . (Join-Path $PSScriptRoot 'serial_ports.ps1')
        $live = @(Get-SerialPortCatalog | Where-Object { $_.Port -eq $Port })
        if ($live.Count -ne 1) { throw 'Selected serial port is no longer present. Reconnect USB and wait for detection.' }
        if ($live[0].IsBluetooth) { throw 'Bluetooth virtual COM ports cannot flash a USB gauge. Connect its USB data cable.' }
        Invoke-GaugeFlash -Root $PackageRoot -Port $Port -RunDirectory $RunDirectory -FirstInstall:$FirstInstall
    }
    exit 0
} catch {
    Write-Error $_ -ErrorAction Continue
    exit 1
}
