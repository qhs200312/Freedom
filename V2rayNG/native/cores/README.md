# Android Core Bundle

This directory contains the Android binding sources used to build
`app/libs/libcores.aar` and its matching `libcores-sources.jar`.

Pinned core versions:

| Core | Version | Official commit |
| --- | --- | --- |
| Xray-core | v26.10.17 | `92ccc939aaf4f8965f369cf0b142e005aeaa11d5` |
| sing-box | v1.14.3 | `7054cac5124465ffdb145870d7c01885566711b1` |

Xray's Go module uses
`v1.260327.1-0.20261017152356-92ccc939aaf4`, which resolves to the exact
official release commit; the core itself reports `26.10.17`.

`AndroidLibXrayLite` is based on 2dust/AndroidLibXrayLite commit
`d0c6c4ae1b09c912070c8288bd0dbcc2e492ac29`, with its Xray dependency updated.
Its upstream LGPL-3.0 license is preserved. `AndroidCoreBundle` is the existing
Freedom facade, copied from the project's retained build sources. Both modules
include dependency lock files (`go.sum`).
The facade's `Close` method explicitly stops its native service before closing
the command server, releasing listeners and the cache database.
Upstream geodata downloads are omitted here; the app's existing bundled routing
assets remain in `app/src/main/assets`.

Build with Go 1.27, SagerNet gomobile 0.1.13, JDK 17 and Android NDK
28.2.13676358. Set `ANDROID_HOME`, `ANDROID_NDK_HOME`, `JAVA_HOME` and `GOPATH`,
then run:

```powershell
.\native\cores\build.ps1 -Go <path-to-go.exe> -Gomobile <path-to-gomobile.exe>
```

The default output is `native/cores/build/libcores.aar` with a matching
`libcores-sources.jar`. All four Android ABIs are built. The existing QUIC,
gVisor, WireGuard, uTLS, Naive, Clash API and Tailscale build tags are retained.
Go compilation defaults to two workers per architecture to limit memory usage;
adjust `-Parallelism` for a larger build machine.
Validate the generated library and runtime tests before replacing `app/libs`.

On this Windows machine, the previously prepared gomobile launcher is at
`.codex/gomobile-local-20261014/gomobile.exe`; its corresponding `gobind.exe`
must also be on `PATH`.
