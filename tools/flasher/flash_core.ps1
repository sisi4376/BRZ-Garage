Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'process_output.ps1')

function Get-PackageHash {
    param([string]$Path)
    $stream = [IO.File]::OpenRead($Path)
    $hasher = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($hasher.ComputeHash($stream)).Replace('-', '').ToLowerInvariant() }
    finally { $stream.Dispose(); $hasher.Dispose() }
}

function Read-FlashPackage {
    param([string]$Root)
    $manifest = Get-Content -LiteralPath (Join-Path $Root 'package.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($manifest.format -ne 1 -or $manifest.chip -ne 'esp32s3' -or
        $manifest.variant -ne 'obd_brz_gauge_amoled175' -or $manifest.flash_bytes -ne 16777216) {
        throw 'Unsupported firmware package or hardware.'
    }
    $expected = [ordered]@{
        'bootloader.bin' = @(0, 32768)
        'partition-table.bin' = @(32768, 4096)
        'ota_data_initial.bin' = @(61440, 8192)
        'obd_brz_gauge.bin' = @(131072, 3145728)
        'bootmedia.bin' = @(6422528, 10354688)
    }
    if (@($manifest.files).Count -ne $expected.Count) { throw 'Incomplete firmware package.' }
    $seen = @{}
    foreach ($entry in $manifest.files) {
        if (-not $expected.Contains($entry.path) -or $seen.ContainsKey($entry.path)) { throw 'Unexpected or duplicate firmware file.' }
        $seen[$entry.path] = $true
        $limits = $expected[$entry.path]
        if ($entry.offset -ne $limits[0] -or $entry.size -le 0 -or $entry.size -gt $limits[1]) { throw 'Unsafe flash address or file size.' }
        $file = Join-Path (Join-Path $Root 'firmware') $entry.path
        if ((Get-Item -LiteralPath $file).Length -ne $entry.size -or
            (Get-PackageHash $file) -ne $entry.sha256) { throw "Firmware checksum failed: $($entry.path)" }
    }
    $tool = Join-Path $Root 'tools/esptool.exe'
    if ((Get-PackageHash $tool) -ne $manifest.esptool_sha256) { throw 'Flashing tool checksum failed.' }
    $app = [IO.File]::ReadAllBytes((Join-Path $Root 'firmware/obd_brz_gauge.bin'))
    if ($app[0] -ne 0xE9 -or [BitConverter]::ToUInt16($app, 12) -ne 9 -or
        [BitConverter]::ToUInt32($app, 32) -ne 2882360370) { throw 'Invalid ESP32-S3 application image.' }
    $version = [Text.Encoding]::ASCII.GetString($app, 48, 32).TrimEnd([char]0)
    $project = [Text.Encoding]::ASCII.GetString($app, 80, 32).TrimEnd([char]0)
    if ($version -ne $manifest.version -or $project -ne 'obd_brz_gauge') { throw 'Application version or project does not match package.' }
    return $manifest
}

function Get-FlashPlan {
    param($Manifest, [byte[]]$InstalledTable, [byte[]]$PackageTable, [switch]$FirstInstall)
    if ($InstalledTable.Length -ne $PackageTable.Length) { throw 'Partition table read is incomplete.' }
    $matching = $true
    $blank = $true
    for ($i = 0; $i -lt $PackageTable.Length; $i++) {
        if ($InstalledTable[$i] -ne $PackageTable[$i]) { $matching = $false }
        if ($InstalledTable[$i] -ne 255) { $blank = $false }
    }
    if (-not $matching -and -not $blank -and -not $FirstInstall) {
        throw 'Existing partition layout differs. Backup is saved. Select First installation only if replacing the factory/other firmware intentionally.'
    }
    foreach ($entry in $Manifest.files) {
        # Keep the user's boot animation on a normal reinstall of this layout.
        if ($entry.path -eq 'bootmedia.bin' -and $matching -and -not $FirstInstall) { continue }
        $entry
    }
}

function Invoke-GaugeFlash {
    param([string]$Root, [string]$Port, [string]$RunDirectory, [switch]$FirstInstall)
    if ($Port -notmatch '^COM[1-9][0-9]{0,4}$') { throw 'Invalid COM port.' }
    $manifest = Read-FlashPackage $Root
    New-Item -ItemType Directory -Path $RunDirectory -ErrorAction Stop | Out-Null
    $log = Join-Path $RunDirectory 'flash.log'
    $tool = Join-Path $Root 'tools/esptool.exe'
    $common = @('--chip', 'esp32s3', '--port', $Port, '--baud', '460800', '--before', 'default_reset', '--after', 'no_reset')
    function Write-FlashLog([string]$Message) {
        Add-Content -LiteralPath $log -Value $Message -Encoding UTF8
    }
    function Run-Tool([string[]]$ToolArguments) {
        Write-FlashLog ('> esptool ' + ($ToolArguments -join ' '))
        return (Invoke-FlasherTool -File $tool -ToolArguments $ToolArguments -Log $log)
    }
    try {
        Write-FlashLog 'PHASE: Detecting ESP32-S3 and Flash capacity'
        $identity = Run-Tool ($common + @('flash_id'))
        if ($identity -notmatch '(?im)Detected flash size:\s*16MB\s*$') { throw 'This package requires exactly 16 MB Flash.' }
        Write-FlashLog 'PHASE: Backing up all 16 MB of Flash - do not unplug USB'
        $backup = Join-Path $RunDirectory 'flash-before.bin'
        $null = Run-Tool ($common + @('read_flash', '0x0', '0x1000000', $backup))
        if ((Get-Item -LiteralPath $backup).Length -ne 16777216) { throw 'Full backup is incomplete; refusing to write.' }
        $digest = Get-PackageHash $backup
        "$digest  flash-before.bin" | Set-Content -LiteralPath (Join-Path $RunDirectory 'SHA256SUMS.txt') -Encoding ASCII
        $installed = [IO.File]::ReadAllBytes($backup)
        $table = [IO.File]::ReadAllBytes((Join-Path $Root 'firmware/partition-table.bin'))
        [byte[]]$installedTable = $installed[32768..(32768 + $table.Length - 1)]
        $plan = @(Get-FlashPlan $manifest $installedTable $table -FirstInstall:$FirstInstall)
        $pairs = @()
        foreach ($entry in $plan) {
            $pairs += ('0x{0:x}' -f [int]$entry.offset)
            $pairs += (Join-Path (Join-Path $Root 'firmware') $entry.path)
        }
        # Recheck all package hashes immediately before the first write.
        $null = Read-FlashPackage $Root
        Write-FlashLog 'PHASE: Writing verified firmware - do not unplug USB'
        $settings = @('--flash_mode', 'dio', '--flash_freq', '80m', '--flash_size', '16MB')
        $null = Run-Tool ($common + @('write_flash') + $settings + $pairs)
        Write-FlashLog 'PHASE: Verifying written Flash'
        $null = Run-Tool ($common + @('verify_flash') + $settings + $pairs)
        Write-FlashLog 'PHASE: Restarting gauge'
        $null = Run-Tool @('--chip', 'esp32s3', '--port', $Port, '--before', 'default_reset', '--after', 'hard_reset', 'run')
        Write-FlashLog "SUCCESS: Firmware $($manifest.version) written and verified. Backup: $backup"
    } catch {
        Write-FlashLog ('ERROR: ' + $_.Exception.Message)
        throw
    }
}
