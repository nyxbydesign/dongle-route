param([string]$CompiledLibraryClasses = '')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrEmpty($CompiledLibraryClasses)) {
    $CompiledLibraryClasses = Join-Path $PSScriptRoot 'build\intermediates\javac\debug\compileDebugJavaWithJavac\classes'
}
if (!(Test-Path -LiteralPath (Join-Path $CompiledLibraryClasses 'io\github\muntashirakon\adb\AdbConnection.class'))) {
    throw 'Build :localadb:compileDebugJavaWithJavac first, or pass -CompiledLibraryClasses. This runner does not start another Gradle build.'
}
$output = Join-Path $PSScriptRoot '.test-classes\runner'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$sources = @(
    (Join-Path $projectRoot 'app\src\main\java\dev\usbmic\inspector\adb\AdbWire.java'),
    (Join-Path $PSScriptRoot 'tests\dev\usbmic\inspector\adb\AdbWireTest.java'),
    (Join-Path $PSScriptRoot 'tests\io\github\muntashirakon\adb\AdbConnectionTest.java')
)
$arguments = @('--release', '11', '-encoding', 'UTF-8', '-cp', $CompiledLibraryClasses, '-d', $output) + $sources
& javac @arguments
if ($LASTEXITCODE -ne 0) { throw "Protocol test compilation failed ($LASTEXITCODE)." }
$runtime = $output + [IO.Path]::PathSeparator + $CompiledLibraryClasses
& java @('-cp', $runtime, 'dev.usbmic.inspector.adb.AdbWireTest')
if ($LASTEXITCODE -ne 0) { throw 'Shell/sync framing tests failed.' }
& java @('-cp', $runtime, 'io.github.muntashirakon.adb.AdbConnectionTest')
if ($LASTEXITCODE -ne 0) { throw 'Loopback transport tests failed.' }
