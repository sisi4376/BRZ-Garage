param(
    [string]$Port = "COM7",
    [int]$DurationSeconds = 1200
)

$serial = [System.IO.Ports.SerialPort]::new($Port, 115200, "None", 8, "One")
$serial.ReadTimeout = 500
$serial.NewLine = "`n"
$serial.DtrEnable = $false
$serial.RtsEnable = $false
$started = [DateTimeOffset]::Now
$deadline = $started.AddSeconds($DurationSeconds)
$lastHeartbeat = $started

try {
    $serial.Open()
    Write-Output ("MONITOR_OPEN {0:O} port={1} duration_s={2}" -f $started, $Port, $DurationSeconds)
    while ([DateTimeOffset]::Now -lt $deadline) {
        try {
            $line = $serial.ReadLine().TrimEnd("`r")
            if ($line.Length -gt 0) {
                Write-Output ("{0:HH:mm:ss.fff} {1}" -f [DateTimeOffset]::Now, $line)
            }
        } catch [System.TimeoutException] {
            # A quiet UART is normal; emit a host heartbeat once per minute.
        }
        $now = [DateTimeOffset]::Now
        if (($now - $lastHeartbeat).TotalSeconds -ge 60) {
            Write-Output ("MONITOR_ALIVE {0:O} bytes_waiting={1}" -f $now, $serial.BytesToRead)
            $lastHeartbeat = $now
        }
    }
} finally {
    if ($serial.IsOpen) { $serial.Close() }
    $serial.Dispose()
    Write-Output ("MONITOR_CLOSED {0:O}" -f [DateTimeOffset]::Now)
}
