$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot '../tools/flasher/serial_ports.ps1')
function Assert-That([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Device([string]$Port, [string]$Id, [string]$Path = '') {
    [pscustomobject]@{Port=$Port; PnpId=$Id; DevicePath=$Path; Name="Device $Port"}
}
$bluetooth = @(ConvertTo-SerialPortCatalog @(
    (Device 'COM3' '' '\Device\BthModem2'),
    (Device 'COM4' 'BTHENUM\virtual'),
    (Device 'COM5' '' '\Device\BthModem3'),
    (Device 'COM6' 'BTHMODEM\virtual')))
Assert-That ($bluetooth.Count -eq 4) 'Expected four input ports'
Assert-That (@($bluetooth | Where-Object IsBluetooth).Count -eq 4) 'All four must be classified as Bluetooth'
Assert-That (-not (Select-RecommendedPort $bluetooth)) 'Never recommend Bluetooth'
Write-Output 'PASS: Four Bluetooth-only ports are excluded from recommendation'

$usb = @(ConvertTo-SerialPortCatalog @((Device 'COM7' 'USB\VID_303A&PID_1001&MI_00\device')))
Assert-That ($usb[0].IsUsb -and $usb[0].IsEspressif) 'ESP USB classification'
Assert-That ((Select-RecommendedPort @($bluetooth + $usb)) -eq 'COM7') 'Recommend ESP over Bluetooth'
Assert-That ($usb[0].Display -match 'COM7.*Espressif USB') 'Display device details'
Write-Output 'PASS: One USB gauge is automatically recommended with its device name'

$uart = @(ConvertTo-SerialPortCatalog @((Device 'COM9' 'USB\VID_10C4&PID_EA60\uart')))
Assert-That ((Select-RecommendedPort $uart) -eq 'COM9') 'Support USB UART'
Assert-That ((Select-RecommendedPort @($uart + $usb)) -eq 'COM7') 'Prefer ESP native USB'
Write-Output 'PASS: USB UART supported and native ESP USB preferred'

$other = @(ConvertTo-SerialPortCatalog @((Device 'COM8' 'USB\VID_303A&PID_1001\second')))
Assert-That (-not (Select-RecommendedPort @($usb + $other))) 'Do not guess between two ESP devices'
$ftdi = @(ConvertTo-SerialPortCatalog @((Device 'COM10' 'FTDIBUS\VID_0403+PID_6001\device')))
Assert-That ($ftdi[0].IsUsb) 'FTDI USB classification'
Assert-That (-not (Select-RecommendedPort @($uart + $ftdi))) 'Do not guess between two USB UART devices'
Write-Output 'PASS: Multiple USB candidates require a user choice'

$unknown = @(ConvertTo-SerialPortCatalog @((Device 'COM1' 'ACPI\PNP0501')))
Assert-That (-not (Select-RecommendedPort $unknown)) 'Never automatically choose an unknown built-in COM'
Assert-That (-not (Select-RecommendedPort @())) 'Empty inventory must be supported'
Write-Output 'PASS: Empty or unknown-only inventories do not enable automatic selection'

$duplicates = @(ConvertTo-SerialPortCatalog @((Device 'COM7' 'USB\VID_303A'), (Device 'COM7' 'USB\VID_303A'), (Device 'bad;port' 'USB\VID_303A')))
Assert-That ($duplicates.Count -eq 1) 'Deduplicate and reject invalid port names'
# A present Bluetooth modem must remain Bluetooth even with stale USB metadata.
$stale = @(ConvertTo-SerialPortCatalog @((Device 'COM3' 'USB\VID_303A' '\Device\BthModem2')))
Assert-That ($stale[0].IsBluetooth -and -not $stale[0].IsUsb) 'Do not misclassify stale registry entries'
Write-Output 'PASS: Duplicate, invalid and stale registry entries are handled safely'
