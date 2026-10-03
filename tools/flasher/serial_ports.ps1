# Passive enumeration only: never open/reset a serial device during discovery.
function ConvertTo-SerialPortCatalog {
    param([object[]]$Devices)
    $seen = @{}
    foreach ($device in @($Devices)) {
        $port = [string]$device.Port
        if ($port -notmatch '^COM[1-9][0-9]{0,4}$' -or $seen.ContainsKey($port)) { continue }
        $seen[$port] = $true
        $id = [string]$device.PnpId
        $path = [string]$device.DevicePath
        $bluetooth = ($id -match '^BTH' -or $path -match 'BthModem')
        $usb = (-not $bluetooth -and $id -match '^(USB|FTDIBUS)\\')
        $esp = ($usb -and $id -match 'VID_303A')
        $name = [string]$device.Name
        if (-not $name) { $name = $port }
        $kind = if ($bluetooth) { 'Bluetooth - not USB flashing' } elseif ($esp) { 'Espressif USB' } elseif ($usb) { 'USB serial' } else { 'Unidentified port' }
        [pscustomobject]@{
            Port = $port; Name = $name; PnpId = $id; DevicePath = $path
            IsBluetooth = $bluetooth; IsUsb = $usb; IsEspressif = $esp
            Key = "$port|$id|$path"
            Display = "$port - $name [$kind]"
        }
    }
}

function Get-SerialPortCatalog {
    $present = @([IO.Ports.SerialPort]::GetPortNames())
    $devices = @{}
    foreach ($port in $present) {
        $devices[$port] = [pscustomobject]@{ Port=$port; Name=$port; PnpId=''; DevicePath='' }
    }
    $serialMap = [Microsoft.Win32.Registry]::LocalMachine.OpenSubKey('HARDWARE\DEVICEMAP\SERIALCOMM')
    if ($null -ne $serialMap) {
        try {
            foreach ($path in $serialMap.GetValueNames()) {
                $port = [string]$serialMap.GetValue($path)
                if ($devices.ContainsKey($port)) { $devices[$port].DevicePath = $path }
            }
        } finally { $serialMap.Dispose() }
    }
    foreach ($bus in @('USB', 'FTDIBUS')) {
        $busKey = [Microsoft.Win32.Registry]::LocalMachine.OpenSubKey("SYSTEM\CurrentControlSet\Enum\$bus")
        if ($null -eq $busKey) { continue }
        try {
            foreach ($deviceId in $busKey.GetSubKeyNames()) {
                $deviceKey = $busKey.OpenSubKey($deviceId)
                if ($null -eq $deviceKey) { continue }
                try {
                    foreach ($instance in $deviceKey.GetSubKeyNames()) {
                        $instanceKey = $deviceKey.OpenSubKey($instance)
                        if ($null -eq $instanceKey) { continue }
                        try {
                            $parameters = $instanceKey.OpenSubKey('Device Parameters')
                            if ($null -eq $parameters) { continue }
                            try { $port = [string]$parameters.GetValue('PortName', '') }
                            finally { $parameters.Dispose() }
                            # Ignore stale registrations for unplugged USB devices,
                            # and never relabel a live Bluetooth port as old USB.
                            if ($devices.ContainsKey($port) -and $devices[$port].DevicePath -notmatch 'BthModem') {
                                $devices[$port].PnpId = "$bus\$deviceId\$instance"
                                $devices[$port].Name = [string]$instanceKey.GetValue('FriendlyName', $port)
                            }
                        } finally { $instanceKey.Dispose() }
                    }
                } finally { $deviceKey.Dispose() }
            }
        } finally { $busKey.Dispose() }
    }
    ConvertTo-SerialPortCatalog @($devices.Values | Sort-Object { [int]($_.Port.Substring(3)) })
}

function Select-RecommendedPort {
    param([object[]]$Catalog)
    $esp = @($Catalog | Where-Object { $_.IsEspressif -and -not $_.IsBluetooth })
    if ($esp.Count -eq 1) { return $esp[0].Port }
    if ($esp.Count -gt 1) { return '' }
    $usb = @($Catalog | Where-Object { $_.IsUsb -and -not $_.IsBluetooth })
    if ($usb.Count -eq 1) { return $usb[0].Port }
    return ''
}
