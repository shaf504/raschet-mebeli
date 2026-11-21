[CmdletBinding()]
param(
    [Parameter(Mandatory)][string] $WebSource,
    [Parameter(Mandatory)][string] $Vendor,
    [Parameter(Mandatory)][string] $BuildTools,
    [Parameter(Mandatory)][string] $AndroidJar,
    [Parameter(Mandatory)][string] $JavaDirectory,
    [Parameter(Mandatory)][string] $WorkDirectory,
    [Parameter(Mandatory)][string] $KeyStore,
    [Parameter(Mandatory)][string] $KeyPasswordFile,
    [Parameter(Mandatory)][string] $Output,
    [string] $Alias = 'mebelshaf'
)

$ErrorActionPreference = 'Stop'

function Invoke-BuildTool {
    param([string] $Executable, [string[]] $Arguments)
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$([IO.Path]::GetFileName($Executable)) failed ($LASTEXITCODE)." }
}

$stage = New-Item -ItemType Directory -Path (Join-Path $WorkDirectory ([guid]::NewGuid().ToString('N')))
$assets = New-Item -ItemType Directory -Path (Join-Path $stage.FullName 'assets')
$web = New-Item -ItemType Directory -Path (Join-Path $assets.FullName 'web')
$vendorAssets = New-Item -ItemType Directory -Path (Join-Path $assets.FullName 'vendor')
$drawable = New-Item -ItemType Directory -Path (Join-Path $stage.FullName 'res/drawable')
$classes = New-Item -ItemType Directory -Path (Join-Path $stage.FullName 'classes')
$dex = New-Item -ItemType Directory -Path (Join-Path $stage.FullName 'dex')

foreach ($entry in @('index.html', 'drawing.html', 'manifest.webmanifest', 'assets')) {
    Copy-Item -LiteralPath (Join-Path $WebSource $entry) -Destination $web.FullName -Recurse
}
foreach ($entry in @('three.min.js', 'OrbitControls.js', 'three-LICENSE.txt')) {
    Copy-Item -LiteralPath (Join-Path $Vendor $entry) -Destination $vendorAssets.FullName
}
Copy-Item -LiteralPath (Join-Path $WebSource 'assets/welcome/apple-icon.png') -Destination (Join-Path $drawable.FullName 'app_icon.png')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'android-downloads.js') -Destination $assets.FullName

$resources = Join-Path $stage.FullName 'resources.zip'
$classesJar = Join-Path $stage.FullName 'classes.jar'
$unsigned = Join-Path $stage.FullName 'unsigned.apk'
$aligned = Join-Path $stage.FullName 'aligned.apk'
$java = Join-Path $JavaDirectory 'bin/java.exe'
$javac = Join-Path $JavaDirectory 'bin/javac.exe'
$jar = Join-Path $JavaDirectory 'bin/jar.exe'
$aapt = Join-Path $BuildTools 'aapt2.exe'
$zipalign = Join-Path $BuildTools 'zipalign.exe'
$signer = Join-Path $BuildTools 'lib/apksigner.jar'

Invoke-BuildTool $aapt @('compile', '--dir', (Join-Path $stage.FullName 'res'), '-o', $resources)
Invoke-BuildTool $aapt @('link', '-o', $unsigned, '-I', $AndroidJar, '--manifest', (Join-Path $PSScriptRoot 'AndroidManifest.xml'), $resources)
Invoke-BuildTool $javac @('--release', '8', '-encoding', 'UTF-8', '-classpath', $AndroidJar, '-d', $classes.FullName, (Join-Path $PSScriptRoot 'MainActivity.java'))
Invoke-BuildTool $jar @('--create', '--file', $classesJar, '-C', $classes.FullName, '.')
Invoke-BuildTool $java @('-cp', (Join-Path $BuildTools 'lib/d8.jar'), 'com.android.tools.r8.D8', '--release', '--min-api', '24', '--lib', $AndroidJar, '--output', $dex.FullName, $classesJar)
Invoke-BuildTool $jar @('--update', '--file', $unsigned, '-C', $stage.FullName, 'assets', '-C', $dex.FullName, 'classes.dex')
Invoke-BuildTool $zipalign @('-f', '4', $unsigned, $aligned)
Invoke-BuildTool $java @('-jar', $signer, 'sign', '--ks', $KeyStore, '--ks-key-alias', $Alias, '--ks-pass', "file:$KeyPasswordFile", '--out', $Output, $aligned)
Invoke-BuildTool $zipalign @('-c', '4', $Output)
Invoke-BuildTool $java @('-jar', $signer, 'verify', '--verbose', $Output)
