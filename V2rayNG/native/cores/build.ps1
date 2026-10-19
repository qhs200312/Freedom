param(
    [string]$Go = 'go',
    [string]$Gomobile = 'gomobile',
    [string]$Output = (Join-Path $PSScriptRoot 'build\libcores.aar'),
    [int]$Parallelism = 2
)

$ErrorActionPreference = 'Stop'
if ($Parallelism -lt 1) { throw 'Parallelism must be positive' }
$previousMaxProcs = $env:GOMAXPROCS
$previousGoFlags = $env:GOFLAGS
$env:GOMAXPROCS = $Parallelism.ToString()
$env:GOFLAGS = "$previousGoFlags -p=$Parallelism".Trim()
$outputPath = [IO.Path]::GetFullPath($Output)
New-Item -ItemType Directory -Force (Split-Path -Parent $outputPath) | Out-Null
$tags = @(
    'with_gvisor', 'with_quic', 'with_wireguard', 'with_utls',
    'with_naive_outbound', 'with_clash_api', 'badlinkname', 'tfogo_checklinkname0',
    'with_tailscale', 'ts_omit_logtail', 'ts_omit_ssh', 'ts_omit_drive',
    'ts_omit_taildrop', 'ts_omit_webclient', 'ts_omit_doctor', 'ts_omit_capture',
    'ts_omit_kube', 'ts_omit_aws', 'ts_omit_synology', 'ts_omit_bird'
) -join ','

Push-Location (Join-Path $PSScriptRoot 'AndroidCoreBundle')
try {
    $xrayVersion = (& $Go list -m -f '{{.Version}}' github.com/xtls/xray-core).Trim()
    if ($LASTEXITCODE -ne 0 -or $xrayVersion -ne 'v1.260327.1-0.20261017152356-92ccc939aaf4') {
        throw 'The Xray module must match official v26.10.17'
    }
    $singBoxVersion = (& $Go list -m -f '{{.Version}}' github.com/sagernet/sing-box).Trim()
    if ($LASTEXITCODE -ne 0 -or $singBoxVersion -ne 'v1.14.3') {
        throw 'The sing-box module must match official v1.14.3'
    }
    & $Go mod verify
    if ($LASTEXITCODE -ne 0) { throw 'Go dependency verification failed' }
    $ldflags = '-X github.com/sagernet/sing-box/constant.Version=1.14.3 ' +
        '-X runtime.godebugDefault=multipathtcp=0,tlssha1=1 ' +
        '-s -w -buildid= -checklinkname=0'
    & $Gomobile bind -v -o $outputPath -target android -androidapi 24 -libname=cores `
        -trimpath -buildvcs=false -ldflags $ldflags -tags $tags `
        github.com/2dust/AndroidLibXrayLite `
        github.com/sagernet/sing-box/experimental/libbox `
        github.com/qhs200312/Freedom/AndroidCoreBundle
    if ($LASTEXITCODE -ne 0) { throw 'Android core bundle build failed' }
} finally {
    Pop-Location
    $env:GOMAXPROCS = $previousMaxProcs
    $env:GOFLAGS = $previousGoFlags
}
