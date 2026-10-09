# Consume both native streams concurrently and flush partial progress immediately.
# esptool 4 emits backspaces without newlines; PowerShell's native pipeline waits
# for a newline and hides an entire read_flash operation until it finishes.
function Invoke-FlasherTool {
    param([string]$File, [string[]]$ToolArguments, [string]$Log)
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $File
    $info.Arguments = ($ToolArguments | ForEach-Object {
        if ($_ -match '"') { throw 'Unexpected quote in tool argument.' }
        '"' + ($_ -replace '(\\+)$', '$1$1') + '"'
    }) -join ' '
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $info
    $writer = New-Object IO.StreamWriter($Log, $true, (New-Object Text.UTF8Encoding($false)))
    $writer.AutoFlush = $true
    $result = New-Object Text.StringBuilder
    try {
        $null = $process.Start()
        $streams = @($process.StandardOutput, $process.StandardError) | ForEach-Object {
            $buffer = New-Object char[] 4096
            [pscustomobject]@{ Reader = $_; Buffer = $buffer; Task = $_.ReadAsync($buffer, 0, $buffer.Length); Backspace = $false }
        }
        while (@($streams | Where-Object { $null -ne $_.Task }).Count) {
            foreach ($stream in $streams) {
                if ($null -eq $stream.Task -or -not $stream.Task.IsCompleted) { continue }
                $count = $stream.Task.GetAwaiter().GetResult()
                if ($count -eq 0) { $stream.Task = $null; continue }
                $chunk = New-Object string($stream.Buffer, 0, $count)
                if ($result.Length -lt 2097152) { $null = $result.Append($chunk) }
                # Collapse each backspace run to one line break, including runs
                # split across read chunks. Partial records are still flushed.
                $visible = New-Object Text.StringBuilder
                foreach ($char in $chunk.ToCharArray()) {
                    if ($char -eq [char]8) {
                        if (-not $stream.Backspace) { $null = $visible.Append("`n") }
                        $stream.Backspace = $true
                    } else {
                        $null = $visible.Append($char)
                        $stream.Backspace = $false
                    }
                }
                $writer.Write($visible.ToString())
                $stream.Task = $stream.Reader.ReadAsync($stream.Buffer, 0, $stream.Buffer.Length)
            }
            Start-Sleep -Milliseconds 25
        }
        $process.WaitForExit()
        $writer.WriteLine()
        if ($process.ExitCode -ne 0) {
            throw "esptool failed (exit $($process.ExitCode)). See the log. No automatic retry was performed."
        }
        return $result.ToString()
    } finally {
        $writer.Dispose()
        $process.Dispose()
    }
}

function Get-FlasherProgress {
    param([string]$Text)
    $phases = [regex]::Matches($Text, '(?m)^PHASE: ([^\r\n]+)')
    $phase = if ($phases.Count) { $phases[$phases.Count - 1].Groups[1].Value } else { '' }
    $segment = if ($phases.Count) { $Text.Substring($phases[$phases.Count - 1].Index) } else { $Text }
    $state = [pscustomobject]@{ Phase = 'detect'; Percent = -1; Bytes = 0; Total = 16777216 }
    if ($phase -like 'Backing*') {
        $state.Phase = 'backup'
        $matches = [regex]::Matches($segment, '(\d+) \((\d+) %\)')
        if ($matches.Count) {
            $last = $matches[$matches.Count - 1]
            $state.Bytes = [Math]::Min(16777216, [long]$last.Groups[1].Value)
            $state.Percent = [Math]::Min(100, [Math]::Max(0, [int]$last.Groups[2].Value))
        } else { $state.Percent = 0 }
    } elseif ($phase -like 'Writing*') {
        $state.Phase = 'write'
        $matches = [regex]::Matches($segment, 'Writing at 0x[0-9a-fA-F]+[^\r\n]*?\(\s*(\d+)\s*%\)')
        if ($matches.Count) { $state.Percent = [Math]::Min(100, [int]$matches[$matches.Count - 1].Groups[1].Value) }
    } elseif ($phase -like 'Verifying*') { $state.Phase = 'verify' }
    elseif ($phase -like 'Restarting*') { $state.Phase = 'restart' }
    return $state
}
