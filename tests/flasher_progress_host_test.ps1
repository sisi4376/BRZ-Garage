param([Parameter(Mandatory=$true)][string]$Scratch)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../tools/flasher/process_output.ps1')
function Assert-That([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
$source = @'
using System;
using System.IO;
using System.Threading;
class ProgressTool {
    static int Main(string[] args) {
        Console.Write("4096 (0 %)\b\b\b8192 (1 %)");
        Console.Out.Flush();
        Thread.Sleep(1500);
        // Verify progress reached disk while this native process is still alive.
        using (var file = new FileStream(args[0], FileMode.Open, FileAccess.Read, FileShare.ReadWrite))
        using (var reader = new StreamReader(file)) {
            if (!reader.ReadToEnd().Contains("8192 (1 %)")) return 91;
        }
        Console.Error.Write(new string('E', 70000));
        Console.Error.Flush();
        Console.Write("\b\b16777216 (100 %)\n");
        return args.Length > 1 ? 7 : 0;
    }
}
'@
$exe = Join-Path $Scratch 'progress tool.exe'
Add-Type -TypeDefinition $source -OutputAssembly $exe -OutputType ConsoleApplication
$log = Join-Path $Scratch 'progress output.log'
$text = Invoke-FlasherTool $exe @($log) $log
$written = [IO.File]::ReadAllText($log)
Assert-That ($written.Contains("8192 (1 %)")) 'Partial progress missing'
Assert-That ($written.Contains('16777216 (100 %)')) 'Final progress missing'
Assert-That (-not $written.Contains([string][char]8)) 'Backspaces must be normalized'
Assert-That ([regex]::Matches($text, 'E').Count -eq 70000) 'stderr must drain concurrently without blocking'
Write-Output 'PASS: Live partial progress and large stderr drain without waiting for exit'
$failed = $false
try { $null = Invoke-FlasherTool $exe @($log, 'fail') $log } catch { $failed = $_.Exception.Message -like '*exit 7*' }
Assert-That $failed 'Nonzero native exit must fail'
Write-Output 'PASS: Nonzero exit propagated'
$backup = Get-FlasherProgress "PHASE: Backing up all 16 MB`n4096 (0 %)`b8192 (1 %)"
Assert-That ($backup.Phase -eq 'backup' -and $backup.Percent -eq 1 -and $backup.Bytes -eq 8192) 'Backup progress parsing failed'
$write = Get-FlasherProgress "PHASE: Backing up all 16 MB`n16777216 (100 %)`nPHASE: Writing verified firmware`nWriting at 0x00012345... ( 42 %)"
Assert-That ($write.Phase -eq 'write' -and $write.Percent -eq 42) 'Phase must reset stale backup progress'
$verify = Get-FlasherProgress "PHASE: Verifying written Flash`n"
Assert-That ($verify.Phase -eq 'verify' -and $verify.Percent -eq -1) 'Verification has indeterminate progress'
Write-Output 'PASS: Stage-local progress, capacity and unknown-duration verification'
