[CmdletBinding()]
param(
    [string]$Port,
    [ValidateSet("AMOLED_175", "LCD_185")]
    [string]$Board = "AMOLED_175",
    [ValidateSet("Source", "Release")]
    [string]$Mode = "Source",
    [switch]$SkipBackup,
    [switch]$Yes,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$backupRoot = Join-Path $projectRoot "backups\manual"

function Resolve-RequiredTool {
    param([Parameter(Mandatory = $true)][string]$Name)
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        throw "找不到 $Name。请先打开 ESP-IDF 5.5+ PowerShell 环境，再运行本脚本。"
    }
    # ESP-IDF 5.5 exposes esptool.py as a PowerShell function (`python -m
    # esptool`) rather than an application, so Source is empty in that case.
    # Returning its command name keeps invocation in the current IDF session.
    if ($command.Source) { return $command.Source }
    return $command.Name
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)][string]$File,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )
    $display = ($Arguments | ForEach-Object {
        if ($_ -match '\s') { '"' + $_ + '"' } else { $_ }
    }) -join ' '
    Write-Host "> $File $display" -ForegroundColor DarkGray
    if ($DryRun) { return }
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "命令执行失败，退出码 $LASTEXITCODE：$File"
    }
}

function Select-SerialPort {
    param([string]$RequestedPort)
    $ports = @([System.IO.Ports.SerialPort]::GetPortNames() | Sort-Object)
    if ($RequestedPort) {
        if ($ports.Count -gt 0 -and $RequestedPort -notin $ports) {
            throw "未检测到串口 $RequestedPort。当前串口：$($ports -join ', ')"
        }
        return $RequestedPort
    }
    if ($ports.Count -eq 0) {
        throw "没有检测到串口。请检查 USB 数据线、驱动和开发板供电。"
    }
    if ($ports.Count -eq 1) { return $ports[0] }

    Write-Host "检测到多个串口："
    for ($index = 0; $index -lt $ports.Count; $index++) {
        Write-Host "  [$($index + 1)] $($ports[$index])"
    }
    $selection = Read-Host "输入序号"
    $number = 0
    if (-not [int]::TryParse($selection, [ref]$number) -or
        $number -lt 1 -or $number -gt $ports.Count) {
        throw "串口选择无效。"
    }
    return $ports[$number - 1]
}

function Get-ConfiguredBoard {
    $sdkconfig = Join-Path $projectRoot "sdkconfig"
    if (-not (Test-Path -LiteralPath $sdkconfig)) { return $null }
    $text = Get-Content -LiteralPath $sdkconfig -Raw
    if ($text -match '(?m)^CONFIG_OBD_BOARD_AMOLED_175=y\r?$') { return "AMOLED_175" }
    if ($text -match '(?m)^CONFIG_OBD_BOARD_LCD_185=y\r?$') { return "LCD_185" }
    return $null
}

function Assert-ReleaseBoard {
    param([string]$ExpectedBoard)
    $manifestPath = Join-Path $projectRoot "firmware\release\latest.json"
    if (-not (Test-Path -LiteralPath $manifestPath)) {
        throw "缺少发布清单：$manifestPath"
    }
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    $manifestBoard = [string]$manifest.device.board
    $matches = if ($ExpectedBoard -eq "AMOLED_175") {
        $manifestBoard -match 'AMOLED-1\.75'
    } else {
        $manifestBoard -match 'LCD-1\.85'
    }
    if (-not $matches) {
        throw "Release 板型不匹配：清单是 '$manifestBoard'，你选择的是 '$ExpectedBoard'。已拒绝烧录。"
    }
    return $manifestBoard
}

Set-Location -LiteralPath $projectRoot
$Port = Select-SerialPort $Port
$idf = $null
$esptool = Resolve-RequiredTool "esptool.py"
$targetDescription = $Board

if ($Mode -eq "Source") {
    $idf = Resolve-RequiredTool "idf.py"
    if (-not (Test-Path -LiteralPath (Join-Path $projectRoot "sdkconfig"))) {
        Invoke-Checked $idf @("set-target", "esp32s3")
    }
    $configuredBoard = Get-ConfiguredBoard
    if ($configuredBoard -ne $Board) {
        throw "当前 sdkconfig 板型为 '$configuredBoard'，参数要求 '$Board'。请先运行 idf.py menuconfig，在 OBD DSP Configuration → Gauge board 中选择正确板型。"
    }
    $targetDescription = "源码构建 / $configuredBoard"
} else {
    $targetDescription = "预编译 Release / $(Assert-ReleaseBoard $Board)"
}

Write-Host ""
Write-Host "即将部署 BRZ OBD Gauge" -ForegroundColor Cyan
Write-Host "  串口：$Port"
Write-Host "  模式：$Mode"
Write-Host "  目标：$targetDescription"
Write-Host "  备份：$(if ($SkipBackup) { '跳过（不推荐）' } else { '烧录前自动备份 0x8000..0x11FFF' })"
Write-Host "  擦除：不会执行 erase_flash"

if (-not $Yes -and -not $DryRun) {
    $confirmation = Read-Host "确认开发板和串口无误后输入 FLASH"
    if ($confirmation -cne "FLASH") {
        Write-Host "已取消，没有写入设备。"
        exit 0
    }
}

if (-not $SkipBackup) {
    if (-not $DryRun) {
        New-Item -ItemType Directory -Force -Path $backupRoot | Out-Null
    }
    $stamp = Get-Date -Format "yyyyMMdd_HHmmss"
    $safePort = $Port -replace '[^A-Za-z0-9_-]', '_'
    $backupPath = Join-Path $backupRoot "preflash_${stamp}_${safePort}.bin"
    Invoke-Checked $esptool @(
        "--chip", "esp32s3", "-p", $Port, "-b", "460800",
        "read_flash", "0x8000", "0xA000", $backupPath
    )
    Write-Host "备份：$backupPath" -ForegroundColor Green
}

if ($Mode -eq "Source") {
    Invoke-Checked $idf @("build")
    Invoke-Checked $idf @("-p", $Port, "flash")
} else {
    $releaseRoot = Join-Path $projectRoot "firmware\release"
    $flashItems = @(
        @("0x000000", (Join-Path $releaseRoot "bootloader\bootloader.bin")),
        @("0x008000", (Join-Path $releaseRoot "partition_table\partition-table.bin")),
        @("0x00F000", (Join-Path $releaseRoot "ota_data_initial.bin")),
        @("0x020000", (Join-Path $releaseRoot "obd_brz_gauge.bin")),
        @("0x620000", (Join-Path $releaseRoot "bootmedia.bin"))
    )
    foreach ($item in $flashItems) {
        if (-not (Test-Path -LiteralPath $item[1])) { throw "缺少固件文件：$($item[1])" }
    }
    $arguments = @(
        "--chip", "esp32s3", "-p", $Port, "-b", "460800",
        "write_flash", "--flash_mode", "dio", "--flash_freq", "80m", "--flash_size", "16MB"
    )
    foreach ($item in $flashItems) { $arguments += $item[0]; $arguments += $item[1] }
    Invoke-Checked $esptool $arguments
}

Write-Host ""
if ($DryRun) {
    Write-Host "DryRun 完成：未连接或写入设备。" -ForegroundColor Yellow
} else {
    Write-Host "烧录完成。请确认设备启动画面和串口日志正常。" -ForegroundColor Green
    Write-Host "查看两分钟日志：.\monitor_serial.ps1 -Port $Port -DurationSeconds 120"
}
