[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$JdkHome,
    [Parameter(Mandatory)][string]$AndroidJar,
    [Parameter(Mandatory)][string]$BuildTools,
    [Parameter(Mandatory)][string]$ShizukuLibraries,
    [string]$SigningKey,
    [string]$SigningAlias = 'bridge',
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'dist')
)
$ErrorActionPreference = 'Stop'
function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Build step failed: $Executable ($LASTEXITCODE)" }
}
$build = Join-Path $PSScriptRoot 'build'
$classes = Join-Path $build 'classes'
$dex = Join-Path $build 'dex'
New-Item -ItemType Directory -Force $classes,$dex,$OutputDirectory | Out-Null
$upstream = Split-Path $PSScriptRoot -Parent
$sources = @(Get-ChildItem (Join-Path $PSScriptRoot 'src') -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName)
$generated = Join-Path $build 'generated'
New-Item -ItemType Directory -Force $generated | Out-Null
Invoke-Checked (Join-Path $BuildTools 'aidl.exe') @(('-I'+(Join-Path $PSScriptRoot 'aidl')),('-o'+$generated),(Join-Path $PSScriptRoot 'aidl/dev/evoker/homeholdcts/IWakeWatcher.aidl'))
$sources += @(Get-ChildItem $generated -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName)
$libraries = @('api','provider','aidl','shared') | ForEach-Object { Join-Path $ShizukuLibraries ($_+'/classes.jar') }
$libraries += Join-Path $ShizukuLibraries 'annotation.jar'
$classPath = (@($AndroidJar) + $libraries) -join ';'

$response = Join-Path $build 'sources.txt'
$sources | ForEach-Object { '"' + $_.Replace('\','/') + '"' } | Set-Content -LiteralPath $response -Encoding utf8NoBOM
Invoke-Checked (Join-Path $JdkHome 'bin/javac.exe') @('-encoding','UTF-8','-source','17','-target','17','-cp',$classPath,'-d',$classes,('@'+$response))
$classJar = Join-Path $build 'bridge-classes.jar'
if (Test-Path -LiteralPath $classJar) { Remove-Item -LiteralPath $classJar }
[IO.Compression.ZipFile]::CreateFromDirectory($classes,$classJar)
$previousJava = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $JdkHome
    Invoke-Checked (Join-Path $BuildTools 'd8.bat') (@('--min-api','34','--lib',$AndroidJar,'--output',$dex,$classJar) + $libraries)
    $resources = Join-Path $build 'resources.zip'
    Invoke-Checked (Join-Path $BuildTools 'aapt2.exe') @('compile','--dir',(Join-Path $PSScriptRoot 'res'),'-o',$resources)
    $unsigned = Join-Path $build 'bridge-unsigned.apk'
    Invoke-Checked (Join-Path $BuildTools 'aapt2.exe') @('link','-o',$unsigned,'--manifest',(Join-Path $PSScriptRoot 'AndroidManifest.xml'),'-I',$AndroidJar,'-A',(Join-Path $PSScriptRoot 'assets'),$resources)
    $archive = [IO.Compression.ZipFile]::Open($unsigned, [IO.Compression.ZipArchiveMode]::Update)
    try {
        Get-ChildItem $dex -Filter '*.dex' | ForEach-Object {
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$_.FullName,$_.Name,[IO.Compression.CompressionLevel]::Optimal) | Out-Null
        }
    } finally { $archive.Dispose() }
    $aligned = Join-Path $build 'bridge-aligned.apk'
    Invoke-Checked (Join-Path $BuildTools 'zipalign.exe') @('-f','-p','4',$unsigned,$aligned)
    $key = Join-Path $build 'bridge-local-test.p12'
    $storePass = 'pass:android'
    $keyPass = 'pass:android'
    if ($SigningKey) {
        $key = (Resolve-Path -LiteralPath $SigningKey).Path
        if (!$env:COLORGPT_SIGNING_STORE_PASS -or !$env:COLORGPT_SIGNING_KEY_PASS) {
            throw 'Set COLORGPT_SIGNING_STORE_PASS and COLORGPT_SIGNING_KEY_PASS for your signing key.'
        }
        $storePass = 'env:COLORGPT_SIGNING_STORE_PASS'
        $keyPass = 'env:COLORGPT_SIGNING_KEY_PASS'
    } elseif (!(Test-Path -LiteralPath $key)) {
        Invoke-Checked (Join-Path $JdkHome 'bin/keytool.exe') @('-genkeypair','-keystore',$key,'-storetype','PKCS12','-storepass','android','-keypass','android','-alias','bridge','-keyalg','RSA','-keysize','3072','-validity','3650','-dname','CN=ColorGPT Bridge Local Test')
    }
    $apk = Join-Path $OutputDirectory 'ColorGPT-Shizuku-0.4.5.apk'
    Invoke-Checked (Join-Path $BuildTools 'apksigner.bat') @('sign','--ks',$key,'--ks-key-alias',$SigningAlias,'--ks-pass',$storePass,'--key-pass',$keyPass,'--v1-signing-enabled','false','--v2-signing-enabled','true','--v3-signing-enabled','true','--v4-signing-enabled','false','--out',$apk,$aligned)
    Invoke-Checked (Join-Path $BuildTools 'apksigner.bat') @('verify','--verbose','--print-certs',$apk)
    Invoke-Checked (Join-Path $BuildTools 'zipalign.exe') @('-c','-p','4',$apk)
    (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + [IO.Path]::GetFileName($apk) | Set-Content -LiteralPath ($apk+'.sha256') -Encoding ascii
    Write-Output "Built APK: $apk"
} finally { $env:JAVA_HOME = $previousJava }
