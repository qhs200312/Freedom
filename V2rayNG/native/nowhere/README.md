# Nowhere Android Bridge

This directory vendors the unmodified Rust source for NodePassProject/Nowhere
v3.0.0, commit `00000b3a9ba08ddb37f5c39ce0c9eda79bedaf03`.

Upstream: https://github.com/NodePassProject/Nowhere

License: GPL-3.0-only; the full upstream license and file headers are preserved
in `upstream/`. Keep this corresponding source and Cargo.lock available with
any redistributed APK containing Nowhere.

Build using Rust with the aarch64-linux-android and x86_64-linux-android targets
installed, and Android NDK 28.2:

```powershell
.\native\nowhere\build.ps1
```

Both executables use Android API 24 and 16 KB ELF segment alignment. They are
packaged as `libnowhere.so` so Android extracts them into the app's executable
native library directory. They are subprocess executables, not JNI libraries.

Freedom converts each Nowhere profile used in the active Xray configuration
into a distinct authenticated loopback SOCKS5 Vector. Xray retains ownership
of routing, per-app rules, DNS and traffic counters. Its VPN excludes the app
UID, and root mode also excludes the app UID, preventing Vector socket loops.
Selecting sing-box with a Nowhere routing target falls back to Xray.

Certificate verification uses either an explicitly supplied SHA-256 pin or
Android's system CA directories. No trust-on-first-use or insecure TLS mode is
added. Imported share links cannot change the loopback listener or credentials.

Proxy chains containing Nowhere are explicitly rejected. The first build
targets ARM64 and x86_64; other architectures fail with a clear runtime error.
Nowhere server URLs (`portal://`, `egress://`) are not accepted as client imports.
