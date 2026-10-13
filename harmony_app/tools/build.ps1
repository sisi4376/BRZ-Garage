param([string]$DevEco = 'C:/Program Files/Huawei/DevEco Studio')
$ErrorActionPreference = 'Stop'
$env:DEVECO_SDK_HOME = Join-Path $DevEco 'sdk'
$env:JAVA_HOME = Join-Path $DevEco 'jbr'
$env:PATH = (Join-Path $DevEco 'tools/node') + ';' + (Join-Path $DevEco 'jbr/bin') + ';' + $env:PATH
Push-Location (Join-Path $PSScriptRoot '..')
try {
  & (Join-Path $DevEco 'tools/hvigor/bin/hvigorw.bat') --mode module -p product=default -p module=entry@default -p buildMode=debug assembleHap --no-daemon
  if ($LASTEXITCODE -ne 0) { throw 'Harmony build failed' }
} finally { Pop-Location }
