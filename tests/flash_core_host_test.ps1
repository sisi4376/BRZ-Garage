param([Parameter(Mandatory=$true)][string]$Scratch)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../tools/flasher/flash_core.ps1')
$root = Join-Path $Scratch 'package with spaces'
New-Item -ItemType Directory -Force -Path "$root/firmware", "$root/tools" | Out-Null
$mock = @'
using System;
using System.IO;
class FakeEsptool {
    static int Main(string[] args) {
        string root = Path.GetDirectoryName(Path.GetDirectoryName(System.Reflection.Assembly.GetExecutingAssembly().Location));
        string scenario = Environment.GetEnvironmentVariable("FLASH_TEST_SCENARIO") ?? "normal";
        File.AppendAllText(Path.Combine(root, "calls.txt"), String.Join("|", args) + "\n");
        int read = Array.IndexOf(args, "read_flash");
        int offset = read >= 0 ? Convert.ToInt32(args[read+1].Substring(2), 16) : 0;
        Console.WriteLine("MAC: " + (scenario == "devicechange" && offset > 0 ? "11:22:33:44:55:66" : "aa:bb:cc:dd:ee:ff"));
        if (Array.IndexOf(args, "flash_id") >= 0) {
            Console.WriteLine("Detected flash size: " + (scenario == "wrongsize" ? "8MB" : "16MB"));
        } else if (read >= 0) {
            if (scenario == "readerror") return 2;
            if ((scenario == "readretry" || scenario == "persistent") && offset == 13 * 1048576 &&
                (scenario == "persistent" || args[read+3].Contains("attempt1"))) {
                File.WriteAllBytes(args[read+3], new byte[2534]);
                Console.Error.WriteLine("A fatal error occurred: Corrupt data, expected 0x1000 bytes but received 0x9e6 bytes");
                return 2;
            }
            int length = Convert.ToInt32(args[read+2].Substring(2), 16);
            byte[] image = new byte[scenario == "shortbackup" ? 4096 : length];
            for (int i=0; i<image.Length; i++) image[i]=255;
            if (image.Length == 1048576 && offset == 0) {
                byte[] table = File.ReadAllBytes(Path.Combine(root, "firmware", "partition-table.bin"));
                Array.Copy(table, 0, image, 32768, table.Length);
                if (scenario == "foreign") image[32768]=0;
            }
            File.WriteAllBytes(args[read+3], image);
        } else if (Array.IndexOf(args, "verify_flash") >= 0) {
            bool backup = String.Join("|",args).Contains("flash-before.partial.bin");
            if ((backup && scenario == "backupverifyerror") || (!backup && scenario == "verifyerror")) return 3;
        } else if (Array.IndexOf(args, "write_flash") >= 0 && scenario == "writeerror") return 2;
        return 0;
    }
}
'@
Add-Type -TypeDefinition $mock -OutputAssembly "$root/tools/esptool.exe" -OutputType ConsoleApplication
[byte[]]$app = New-Object byte[] 256
$app[0] = 0xE9; $app[12] = 9
[BitConverter]::GetBytes([uint32]2882360370).CopyTo($app, 32)
[Text.Encoding]::ASCII.GetBytes('4.0.0').CopyTo($app, 48)
[Text.Encoding]::ASCII.GetBytes('obd_brz_gauge').CopyTo($app, 80)
$map = [ordered]@{'bootloader.bin'=0; 'partition-table.bin'=32768; 'ota_data_initial.bin'=61440; 'obd_brz_gauge.bin'=131072; 'bootmedia.bin'=6422528}
$entries = @()
foreach ($name in $map.Keys) {
    [byte[]]$data = if ($name -eq 'obd_brz_gauge.bin') { $app } else { @(170, 80, 1, 2) }
    [IO.File]::WriteAllBytes("$root/firmware/$name", $data)
    $entries += @{path=$name; offset=$map[$name]; size=$data.Length; sha256=(Get-PackageHash "$root/firmware/$name")}
}
$manifest = @{format=1; version='4.0.0'; chip='esp32s3'; variant='obd_brz_gauge_amoled175'; flash_bytes=16777216; esptool_sha256=(Get-PackageHash "$root/tools/esptool.exe"); files=$entries}
$manifest | ConvertTo-Json -Depth 6 | Set-Content "$root/package.json" -Encoding UTF8
$package = Read-FlashPackage $root
Write-Output 'PASS: Validated package in a path with spaces'

function Assert-That([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
[byte[]]$table = @(170,80,1,2)
$normal = @(Get-FlashPlan $package $table $table)
Assert-That ($normal.Count -eq 4) 'Normal reinstall must preserve bootmedia'
$fresh = @(Get-FlashPlan $package ([byte[]]@(255,255,255,255)) $table)
Assert-That ($fresh.Count -eq 5) 'Blank device must include bootmedia'
Write-Output 'PASS: Normal reinstall preserves animation; blank device initializes all required images'

foreach ($invalid in @('version', 'offset', 'duplicate')) {
    $altered = $manifest | ConvertTo-Json -Depth 6 | ConvertFrom-Json
    if ($invalid -eq 'version') { $altered.version = '9.0.0' }
    if ($invalid -eq 'offset') { $altered.files[0].offset = 36864 }
    if ($invalid -eq 'duplicate') { $altered.files[1] = $altered.files[0] }
    $altered | ConvertTo-Json -Depth 6 | Set-Content "$root/package.json" -Encoding UTF8
    $rejected = $false
    try { $null = Read-FlashPackage $root } catch { $rejected = $true }
    Assert-That $rejected "Invalid $invalid must be rejected"
    Write-Output "PASS: Invalid package $invalid rejected"
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content "$root/package.json" -Encoding UTF8

foreach ($scenario in @('normal', 'wrongsize', 'readerror', 'shortbackup', 'foreign', 'verifyerror', 'first', 'readretry', 'persistent', 'backupverifyerror', 'devicechange', 'writeerror')) {
    $env:FLASH_TEST_SCENARIO = if ($scenario -eq 'first') { 'foreign' } else { $scenario }
    [IO.File]::WriteAllText("$root/calls.txt", '')
    $failed = $false
    try { Invoke-GaugeFlash $root 'COM42' (Join-Path $Scratch $scenario) -FirstInstall:($scenario -eq 'first') }
    catch { $failed = $true }
    $calls = Get-Content "$root/calls.txt" -Raw
    if ($scenario -in @('normal', 'first', 'readretry')) {
        Assert-That (-not $failed) "Unexpected failure: $scenario"
        Assert-That ($calls.IndexOf('read_flash') -lt $calls.IndexOf('write_flash')) 'Backup must precede writing'
        Assert-That ($calls.IndexOf('verify_flash') -lt $calls.IndexOf('write_flash')) 'Full backup verification must precede writing'
        Assert-That ($calls.IndexOf('write_flash') -lt $calls.LastIndexOf('verify_flash')) 'Written firmware verification must follow writing'
        Assert-That (([regex]::Matches($calls, 'read_flash').Count) -eq $(if ($scenario -eq 'readretry') {17} else {16})) 'Unexpected chunk/retry count'
        if ($scenario -eq 'readretry') {
            Assert-That ($calls -match '--baud\|115200\|.*read_flash\|0xd00000\|') 'Retry must use compatibility baud for the failed chunk'
            Assert-That (([IO.File]::ReadAllBytes((Join-Path $Scratch "$scenario/flash-before.bin")))[13631488] -eq 255) 'Failed chunk must not enter backup'
        }
        Assert-That ($calls -match '\|run\s*$') 'Success must restart gauge after verification'
        Assert-That ($calls -notmatch 'erase_flash|\|0x9000\|') 'Never erase all or write NVS'
        Assert-That ($calls -match '^--chip\|esp32s3\|') 'Require esptool chip validation'
        Assert-That (($calls -match 'bootmedia.bin') -eq ($scenario -eq 'first')) 'Incorrect bootmedia preservation'
        Assert-That ((Get-Item (Join-Path $Scratch "$scenario/flash-before.bin")).Length -eq 16777216) 'Backup size mismatch'
    } elseif ($scenario -in @('verifyerror', 'writeerror')) {
        Assert-That $failed 'Verification failure must fail operation'
        Assert-That ($calls -notmatch '\|run\s*$') 'Do not report success or restart on verification failure'
        Assert-That (([regex]::Matches($calls, 'write_flash').Count) -eq 1) 'Writes must never retry automatically'
    } else {
        Assert-That $failed "Expected rejection for $scenario"
        Assert-That ($calls -notmatch 'write_flash') "Must not write for $scenario"
        if ($scenario -eq 'persistent') {
            Assert-That (([regex]::Matches($calls, 'read_flash\|0xd00000\|').Count) -eq 2) 'Read retries must be bounded'
            Assert-That (-not (Test-Path (Join-Path $Scratch "$scenario/flash-before.bin"))) 'Incomplete backup must not be published'
        }
    }
    Write-Output "PASS: simulated $scenario sequence"
}
[IO.File]::WriteAllText("$root/calls.txt", '')
[IO.File]::WriteAllBytes("$root/firmware/obd_brz_gauge.bin", [byte[]]@(1,2,3))
$rejected = $false
try { Invoke-GaugeFlash $root 'COM42' (Join-Path $Scratch 'corrupt') } catch { $rejected = $true }
Assert-That $rejected 'Corrupt package must be rejected'
Assert-That ((Get-Item "$root/calls.txt").Length -eq 0) 'Corrupt package must not access device'
Write-Output 'PASS: Corrupt firmware rejected before device access'
