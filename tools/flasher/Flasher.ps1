param([switch]$SelfTest)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[Windows.Forms.Application]::EnableVisualStyles()
try {
    . (Join-Path $PSScriptRoot 'flash_core.ps1')
    . (Join-Path $PSScriptRoot 'serial_ports.ps1')
    $manifest = Read-FlashPackage $PSScriptRoot
} catch {
    if (-not $SelfTest) { [Windows.Forms.MessageBox]::Show($_.Exception.Message, '固件包校验失败') | Out-Null }
    throw
}

$form = New-Object Windows.Forms.Form
$form.Text = "BRZ Garage 一键烧录 r$($manifest.flasher_revision) · $($manifest.version)"
$form.ClientSize = New-Object Drawing.Size(760, 655)
$form.StartPosition = 'CenterScreen'
$form.FormBorderStyle = 'FixedDialog'
$form.MaximizeBox = $false
$form.Font = New-Object Drawing.Font('Microsoft YaHei UI', 10)
$form.BackColor = [Drawing.Color]::FromArgb(245, 247, 250)

function Add-Label([string]$Text, [int]$Y, [int]$Height = 30) {
    $item = New-Object Windows.Forms.Label
    $item.Text = $Text
    $item.Location = New-Object Drawing.Point(24, $Y)
    $item.Size = New-Object Drawing.Size(712, $Height)
    $form.Controls.Add($item)
    return $item
}
$title = Add-Label '连接 USB，自动识别仪表串口' 20 38
$title.Font = New-Object Drawing.Font('Microsoft YaHei UI', 18, [Drawing.FontStyle]::Bold)
$null = Add-Label '适用：微雪 ESP32-S3-Touch-AMOLED-1.75 / 1.75-B / 1.75-G，16 MB Flash。' 67
$null = Add-Label '请停车操作，并关闭占用串口的程序。备份和烧录期间请勿拔线。' 97

$ports = New-Object Windows.Forms.ComboBox
$ports.Location = New-Object Drawing.Point(24, 139)
$ports.Size = New-Object Drawing.Size(550, 30)
$ports.DropDownStyle = 'DropDownList'
$ports.DisplayMember = 'Display'
$form.Controls.Add($ports)
$refresh = New-Object Windows.Forms.Button
$refresh.Text = '刷新串口'
$refresh.Location = New-Object Drawing.Point(591, 136)
$refresh.Size = New-Object Drawing.Size(145, 36)
$form.Controls.Add($refresh)

$confirmed = New-Object Windows.Forms.CheckBox
$confirmed.Text = '我已确认连接的是上方型号的仪表，而不是其他 ESP32 设备'
$confirmed.Location = New-Object Drawing.Point(24, 185)
$confirmed.Size = New-Object Drawing.Size(712, 32)
$form.Controls.Add($confirmed)
$first = New-Object Windows.Forms.CheckBox
$first.Text = '首次安装：替换原厂 / 其他固件，并写入开机动画'
$first.Location = New-Object Drawing.Point(24, 222)
$first.Size = New-Object Drawing.Size(712, 32)
$form.Controls.Add($first)
$null = Add-Label '默认保留同分区 BRZ 固件的设置与开机动画；始终先备份整片 Flash。' 263

$start = New-Object Windows.Forms.Button
$start.Text = '开始烧录'
$start.Location = New-Object Drawing.Point(24, 302)
$start.Size = New-Object Drawing.Size(240, 46)
$start.BackColor = [Drawing.Color]::FromArgb(28, 98, 210)
$start.ForeColor = [Drawing.Color]::White
$start.FlatStyle = 'Flat'
$start.Enabled = $false
$form.Controls.Add($start)
$backupButton = New-Object Windows.Forms.Button
$backupButton.Text = '打开备份目录'
$backupButton.Location = New-Object Drawing.Point(282, 302)
$backupButton.Size = New-Object Drawing.Size(170, 46)
$form.Controls.Add($backupButton)
$help = New-Object Windows.Forms.Button
$help.Text = '连接帮助'
$help.Location = New-Object Drawing.Point(470, 302)
$help.Size = New-Object Drawing.Size(130, 46)
$form.Controls.Add($help)

$status = Add-Label "固件 $($manifest.version) 校验通过。请选择串口并确认开发板型号。" 366 44
$bar = New-Object Windows.Forms.ProgressBar
$bar.Location = New-Object Drawing.Point(24, 408)
$bar.Size = New-Object Drawing.Size(712, 20)
$form.Controls.Add($bar)
$output = New-Object Windows.Forms.TextBox
$output.Location = New-Object Drawing.Point(24, 434)
$output.Size = New-Object Drawing.Size(712, 195)
$output.Multiline = $true
$output.ScrollBars = 'Vertical'
$output.ReadOnly = $true
$output.Font = New-Object Drawing.Font('Consolas', 9)
$form.Controls.Add($output)
$script:job = $null
$script:runDirectory = $null
$script:portSignature = $null
$script:updatingPorts = $false
$script:stderrTask = $null
$script:elapsed = New-Object Diagnostics.Stopwatch
$backupRoot = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'BRZ-Garage/Flasher/backups'

function Update-Ready {
    $start.Enabled = ($null -eq $script:job -and $confirmed.Checked -and $ports.SelectedIndex -ge 0)
}
function Refresh-Ports {
    if ($null -ne $script:job) { return }
    try { $catalog = @(Get-SerialPortCatalog) }
    catch { $status.Text = '读取串口信息失败，请点击刷新。' + $_.Exception.Message; return }
    $signature = ($catalog | ForEach-Object { $_.Key + '|' + $_.Name }) -join ';'
    if ($null -ne $script:portSignature -and $signature -eq $script:portSignature) { return }
    $script:portSignature = $signature
    $oldKey = if ($ports.SelectedIndex -ge 0) { $ports.SelectedItem.Key } else { '' }
    $recommended = Select-RecommendedPort $catalog
    $script:updatingPorts = $true
    try {
        $ports.Items.Clear()
        foreach ($item in @($catalog | Where-Object { -not $_.IsBluetooth })) { $null = $ports.Items.Add($item) }
        foreach ($item in $ports.Items) { if ($item.Key -eq $oldKey) { $ports.SelectedItem = $item; break } }
        if ($ports.SelectedIndex -lt 0) {
            $confirmed.Checked = $false
            foreach ($item in $ports.Items) { if ($item.Port -eq $recommended) { $ports.SelectedItem = $item; break } }
        }
    } finally { $script:updatingPorts = $false }
    $excluded = @($catalog | Where-Object { $_.IsBluetooth }).Count
    if ($ports.Items.Count -eq 0) {
        $status.Text = "未检测到 USB 仪表（已排除 $excluded 个蓝牙串口）。插上 USB 数据线后会自动识别。"
    } elseif ($ports.SelectedIndex -ge 0) {
        $status.Text = "已选择 $($ports.SelectedItem.Port)：$($ports.SelectedItem.Name)。请确认开发板型号；尚未连接或烧录。"
    } else {
        $status.Text = '发现多个或无法识别的串口，请按设备名称选择。拔插 USB 可观察变化。'
    }
    Update-Ready
}
$refresh.Add_Click({ $script:portSignature = $null; Refresh-Ports })
$confirmed.Add_CheckedChanged({ Update-Ready })
$ports.Add_SelectedIndexChanged({
    if (-not $script:updatingPorts) { $confirmed.Checked = $false }
    Update-Ready
})
$backupButton.Add_Click({
    if (Test-Path -LiteralPath $backupRoot) { Start-Process explorer.exe -ArgumentList ('"' + $backupRoot + '"') }
    else { [Windows.Forms.MessageBox]::Show('首次烧录后，这里会保存完整备份和日志。', '备份目录') | Out-Null }
})
$help.Add_Click({
    [Windows.Forms.MessageBox]::Show("蓝牙 COM 串口不能用于 USB 烧录，程序已自动排除。`r`n请用支持数据传输的 USB 线连接仪表的数据接口，并关闭串口监视器。`r`n`r`n程序每 2 秒检测一次 USB 插拔，优先选择唯一的 Espressif USB 设备。自动发现不会打开串口或复位仪表。`r`n若卡在 Connecting：按住 BOOT，短按 RESET/RST，再松开 BOOT；等待列表更新后重试。`r`n`r`n若设备管理器显示未知设备，请安装开发板官方驱动。", '连接帮助') | Out-Null
})
$timer = New-Object Windows.Forms.Timer
$timer.Interval = 500
$timer.Add_Tick({
    if ($null -eq $script:job) { return }
    $log = Join-Path $script:runDirectory 'flash.log'
    if (Test-Path -LiteralPath $log) {
        $text = Get-Content -LiteralPath $log -Encoding UTF8 -Raw -ErrorAction SilentlyContinue
        $lines = @($text -split "`r?`n" | Select-Object -Last 180)
        $output.Text = $lines -join "`r`n"
        $output.SelectionStart = $output.TextLength
        $output.ScrollToCaret()
        $progress = Get-FlasherProgress $text
        if ($progress.Percent -ge 0) {
            $bar.Style = 'Blocks'
            $bar.Value = $progress.Percent
        } else { $bar.Style = 'Marquee' }
        $label = switch ($progress.Phase) {
            'backup' { '备份原有数据 {0}%（{1:N1} / 16 MB）' -f $progress.Percent, ($progress.Bytes / 1MB) }
            'write' { if ($progress.Percent -ge 0) { "写入当前分区 $($progress.Percent)%" } else { '准备写入固件' } }
            'verify' { '校验已写入的数据' }
            'restart' { '校验完成，正在重启仪表' }
            default { '正在识别仪表' }
        }
        $seconds = [int]$script:elapsed.Elapsed.TotalSeconds
        $status.Text = "$label · 已用时 $seconds 秒，请勿拔线"
        $quiet = [int]((Get-Date) - (Get-Item -LiteralPath $log).LastWriteTime).TotalSeconds
        if ($quiet -ge 30) { $status.Text += "（$quiet 秒未收到新进度，尚未确认完成）" }
    }
    if ($script:job.HasExited) {
        $code = $script:job.ExitCode
        $errorText = $script:stderrTask.GetAwaiter().GetResult()
        $script:job.Dispose()
        $script:job = $null
        $script:stderrTask = $null
        $script:elapsed.Stop()
        $timer.Stop()
        $bar.Style = 'Blocks'
        $refresh.Enabled = $true
        $ports.Enabled = $true
        $confirmed.Enabled = $true
        $first.Enabled = $true
        Update-Ready
        if ($code -eq 0) {
            $bar.Value = 100
            $status.Text = '烧录并验证成功，仪表正在重启。备份和日志已保存。'
        } else {
            $bar.Value = 0
            $status.Text = '未完成：请查看下方日志。已有备份会保留；排除问题后可重试。'
            $output.AppendText("`r`n" + $errorText)
        }
    }
})
$start.Add_Click({
    if (-not $confirmed.Checked -or $ports.SelectedIndex -lt 0 -or $null -ne $script:job) { return }
    if ($first.Checked) {
        $answer = [Windows.Forms.MessageBox]::Show('首次安装将替换原厂或其他固件及开机动画。旧系统的数据无法保证在新固件中使用；完整备份会先保存。是否继续？', '确认首次安装', 'YesNo', 'Warning')
        if ($answer -ne 'Yes') { return }
    }
    try {
        $null = Read-FlashPackage $PSScriptRoot
        $selectedPort = $ports.SelectedItem.Port
        $selectedKey = $ports.SelectedItem.Key
        $live = @(Get-SerialPortCatalog | Where-Object { $_.Key -eq $selectedKey -and -not $_.IsBluetooth })
        if ($live.Count -ne 1) { throw '串口已拔出或设备发生变化，请等待自动识别后重试。' }
        New-Item -ItemType Directory -Force -Path $backupRoot | Out-Null
        $script:runDirectory = Join-Path $backupRoot ((Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $selectedPort + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
        $info = New-Object Diagnostics.ProcessStartInfo
        $info.FileName = Join-Path $PSHOME 'powershell.exe'
        $argsList = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $PSScriptRoot 'flash_worker.ps1'), '-PackageRoot', $PSScriptRoot, '-Port', $selectedPort, '-RunDirectory', $script:runDirectory, '-Confirmed')
        if ($first.Checked) { $argsList += '-FirstInstall' }
        # These arguments are fixed flags, validated COM names and Windows paths.
        # Windows file names cannot contain a double quote; no shell is invoked.
        $info.Arguments = ($argsList | ForEach-Object { '"' + $_ + '"' }) -join ' '
        $info.UseShellExecute = $false
        $info.CreateNoWindow = $true
        $info.RedirectStandardError = $true
        $script:job = [Diagnostics.Process]::Start($info)
        $script:stderrTask = $script:job.StandardError.ReadToEndAsync()
        $script:elapsed.Restart()
        $start.Enabled = $false
        $refresh.Enabled = $false
        $ports.Enabled = $false
        $confirmed.Enabled = $false
        $first.Enabled = $false
        $bar.Value = 0
        $bar.Style = 'Marquee'
        $status.Text = '正在检测设备。请保持 USB 连接，等待备份及烧录完成。'
        $output.Clear()
        $timer.Start()
    } catch {
        [Windows.Forms.MessageBox]::Show($_.Exception.Message, '无法开始烧录') | Out-Null
    }
})
$form.Add_FormClosing({
    param($sender, $eventArgs)
    if ($null -ne $script:job) {
        $eventArgs.Cancel = $true
        [Windows.Forms.MessageBox]::Show('正在备份或烧录，请等待完成后再关闭，勿拔 USB。', '操作进行中') | Out-Null
    }
})
$portTimer = New-Object Windows.Forms.Timer
$portTimer.Interval = 2000
$portTimer.Add_Tick({ Refresh-Ports })
if ($SelfTest) {
    if (-not $form.Controls.Contains($start) -or $start.Enabled) { throw 'Initial flash action must be disabled.' }
    $confirmed.Checked = $true
    if ($start.Enabled) { throw 'Confirmation without a port must not enable flashing.' }
    $null = $ports.Items.Add([pscustomobject]@{Port='COM42'; Key='COM42|test'; Name='Test USB'; Display='COM42 - Test USB'})
    $ports.SelectedIndex = 0
    if ($start.Enabled) { throw 'Changing device must clear earlier confirmation.' }
    $confirmed.Checked = $true
    if (-not $start.Enabled) { throw 'Confirmation and a port must enable flashing.' }
    $confirmed.Checked = $false
    if ($start.Enabled) { throw 'Removing confirmation must disable flashing.' }
    # Exercise actual refresh/selection logic with an injected passive inventory.
    function Get-SerialPortCatalog { $script:testInventory }
    $bt = @(ConvertTo-SerialPortCatalog @([pscustomobject]@{Port='COM3'; PnpId=''; DevicePath='\Device\BthModem2'; Name='Bluetooth'}))
    $usb = @(ConvertTo-SerialPortCatalog @([pscustomobject]@{Port='COM7'; PnpId='USB\VID_303A&PID_1001\test'; DevicePath='\Device\USBSER000'; Name='ESP USB'}))
    $script:testInventory = $bt
    Refresh-Ports
    if ($ports.Items.Count -ne 0 -or $start.Enabled) { throw 'Bluetooth-only inventory must disable flashing.' }
    $script:testInventory = @($bt + $usb)
    Refresh-Ports
    if ($ports.SelectedItem.Port -ne 'COM7' -or $start.Enabled) { throw 'USB plug-in must select COM7 but require confirmation.' }
    $confirmed.Checked = $true
    Refresh-Ports
    if (-not $start.Enabled) { throw 'An unchanged timer refresh must preserve selection and confirmation.' }
    $script:testInventory = $bt
    Refresh-Ports
    if ($ports.SelectedIndex -ge 0 -or $start.Enabled -or $confirmed.Checked) { throw 'USB unplug must clear selection and confirmation.' }
    $script:testInventory = @($bt + $usb)
    Refresh-Ports
    if ($ports.SelectedItem.Port -ne 'COM7' -or $start.Enabled) { throw 'USB reconnect must require confirmation again.' }
    Write-Output 'PASS: USB plug/unplug/reconnect and Bluetooth filtering verified without a device.'
    Write-Output 'PASS: UI constructed; port and board confirmation gates verified. No device accessed.'
} else {
    Refresh-Ports
    $portTimer.Start()
    [Windows.Forms.Application]::Run($form)
}
$timer.Dispose()
$portTimer.Dispose()
$form.Dispose()
