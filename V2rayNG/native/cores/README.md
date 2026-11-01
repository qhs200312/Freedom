# Android Core Bundle

This directory contains the Android binding sources used to build
`app/libs/libcores.aar` and its matching `libcores-sources.jar`.

Pinned core versions:

| Core | Version | Official commit |
| --- | --- | --- |
| Xray-core | v26.10.31 | `a4a3dff5b20c2faae567b742c172c7b95e75c083` |
| sing-box | v1.14.4 | `c005f35300ce76427dc285481ec5695f5b179cd2` |

Xray's Go module uses
`v1.260327.1-0.20261031132019-a4a3dff5b20c`, which resolves to the exact
official release commit; the core itself reports `26.10.31`.

These are the official latest stable releases checked on November 1, 2026.
Release tags and full commit identities are verified before updating module
locks. The build retains `multipathtcp=0` in Go runtime defaults.

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

sing-box 1.14.4 removes `PlatformInterface.UseProcFS`. The Kotlin platform
adapter now performs connection-owner lookup through Android's API on API 29+
and calls the exported `Libbox.findConnectionOwner` procfs helper on older
Android versions, then resolves the UID's package names as before.
