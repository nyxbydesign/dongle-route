$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$output = Join-Path $repo '.test-classes/routing'
New-Item -ItemType Directory -Force -Path $output | Out-Null
& javac --release 8 -encoding UTF-8 -d $output (Join-Path $repo 'routing-helper/src/main/java/dev/usbmic/inspector/shell/RoutingEngine.java') (Join-Path $repo 'routing-helper/src/main/java/dev/usbmic/inspector/shell/MicrophoneSelector.java') (Join-Path $PSScriptRoot 'InputRoutingEngineTest.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp $output InputRoutingEngineTest
exit $LASTEXITCODE
