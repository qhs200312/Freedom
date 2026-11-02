param(
    [string]$Ndk = $env:FREEDOM_RULES_NDK,
    [string]$OutputDirectory = $env:FREEDOM_RULES_OUTPUT,
    [string]$Abis = $env:FREEDOM_RULES_ABIS
)
$ErrorActionPreference = 'Stop'
if (-not $Ndk) { $Ndk = "$env:LOCALAPPDATA/Android/Sdk/ndk/28.2.13676358" }
if (-not $OutputDirectory) { throw 'FREEDOM_RULES_OUTPUT is required' }
if (-not $Abis) { $Abis = 'arm64-v8a;armeabi-v7a;x86_64;x86' }
$toolchain = Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin'
foreach ($abi in $Abis.Split(';')) {
    $target, $clang = switch ($abi) {
        'arm64-v8a' { 'aarch64-linux-android', 'aarch64-linux-android24-clang.cmd' }
        'armeabi-v7a' { 'armv7-linux-androideabi', 'armv7a-linux-androideabi24-clang.cmd' }
        'x86_64' { 'x86_64-linux-android', 'x86_64-linux-android24-clang.cmd' }
        'x86' { 'i686-linux-android', 'i686-linux-android24-clang.cmd' }
        default { throw "Unsupported ABI: $abi" }
    }
    $linker = Join-Path $toolchain $clang
    if (-not (Test-Path -LiteralPath $linker)) { throw "NDK 28.2.13676358 is required: $linker" }
    $key = $target.Replace('-', '_').ToUpperInvariant()
    [Environment]::SetEnvironmentVariable("CARGO_TARGET_${key}_LINKER", $linker)
    [Environment]::SetEnvironmentVariable("CARGO_TARGET_${key}_RUSTFLAGS", '-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384')
    & cargo build --manifest-path (Join-Path $PSScriptRoot 'Cargo.toml') --release --locked --target $target
    if ($LASTEXITCODE -ne 0) { throw "Rust rules parser build failed: $abi" }
    $destination = Join-Path $OutputDirectory $abi
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot "target/$target/release/libfreedom_rules.so") -Destination $destination
}
