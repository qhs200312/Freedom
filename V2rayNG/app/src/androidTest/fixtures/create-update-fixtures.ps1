param(
    [string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$Output = "$PSScriptRoot\..\..\..\build\update-fixtures",
    [string]$Java = 'C:\Program Files\Java\jdk-17'
)
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = $Java
$buildTools = Join-Path $Sdk 'build-tools\36.0.0'
$platform = Join-Path $Sdk 'platforms\android-37.0\android.jar'
$keyStore = Join-Path $env:USERPROFILE '.android\debug.keystore'
New-Item -ItemType Directory -Path $Output -Force | Out-Null
$Output = (Resolve-Path -LiteralPath $Output).Path

# Manifest-only APKs exercise Android's real package/signature parser. Never install these fixtures.
foreach ($version in @(9000001, 9000002)) {
    $manifest = Join-Path $Output "manifest-$version.xml"
    @"
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.v2ray.ang.fdroid" android:versionCode="$version" android:versionName="9.0.$version">
    <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="37" />
    <application android:hasCode="false" android:label="Freedom update test fixture" />
</manifest>
"@ | Set-Content -LiteralPath $manifest -Encoding utf8
    $unsigned = Join-Path $Output "unsigned-$version.apk"
    $aligned = Join-Path $Output "aligned-$version.apk"
    $signed = Join-Path $Output "update-$version.apk"
    & (Join-Path $buildTools 'aapt2.exe') link --manifest $manifest -I $platform -o $unsigned
    if ($LASTEXITCODE -ne 0) { throw 'aapt2 fixture build failed' }
    & (Join-Path $buildTools 'zipalign.exe') -f 4 $unsigned $aligned
    if ($LASTEXITCODE -ne 0) { throw 'zipalign fixture build failed' }
    & (Join-Path $buildTools 'apksigner.bat') sign --ks $keyStore --ks-pass pass:android --key-pass pass:android --out $signed $aligned
    if ($LASTEXITCODE -ne 0) { throw 'fixture signing failed' }
    Write-Output $signed
}
