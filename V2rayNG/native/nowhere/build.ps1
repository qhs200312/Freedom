param(
    [string]$Cargo = 'cargo',
    [string]$Ndk = "$env:LOCALAPPDATA/Android/Sdk/ndk/28.2.13676358",
    [string]$TargetDirectory = '',
    [string[]]$Targets = @('aarch64-linux-android', 'x86_64-linux-android')
)
$ErrorActionPreference = 'Stop'
$ndkBin = Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if ($TargetDirectory) { $env:CARGO_TARGET_DIR = [IO.Path]::GetFullPath($TargetDirectory) }
foreach ($target in $Targets) {
    $abi = switch ($target) {
        'aarch64-linux-android' { 'arm64-v8a' }
        'x86_64-linux-android' { 'x86_64' }
        default { throw "Unsupported target: $target" }
    }
    $underscore = $target.Replace('-', '_')
    $upper = $underscore.ToUpperInvariant()
    [Environment]::SetEnvironmentVariable("CARGO_TARGET_${upper}_LINKER", (Join-Path $ndkBin "${target}24-clang.cmd"))
    [Environment]::SetEnvironmentVariable("CARGO_TARGET_${upper}_RUSTFLAGS", '-C link-arg=-Wl,-z,max-page-size=16384')
    [Environment]::SetEnvironmentVariable("CC_$underscore", (Join-Path $ndkBin "${target}24-clang.cmd"))
    [Environment]::SetEnvironmentVariable("AR_$underscore", (Join-Path $ndkBin 'llvm-ar.exe'))
    & $Cargo build --manifest-path (Join-Path $PSScriptRoot 'upstream/Cargo.toml') --release --locked --target $target
    if ($LASTEXITCODE -ne 0) { throw "Nowhere build failed: $target" }
    $buildRoot = if ($env:CARGO_TARGET_DIR) { $env:CARGO_TARGET_DIR } else { Join-Path $PSScriptRoot 'upstream/target' }
    $dest = Join-Path $workspace "app/libs/$abi"
    New-Item -ItemType Directory -Path $dest -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $buildRoot "$target/release/nowhere") -Destination (Join-Path $dest 'libnowhere.so')
}
