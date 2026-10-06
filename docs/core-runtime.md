# Dual-core runtime architecture

## Boundaries

Node/subscription storage, the editor, notifications, traffic presentation and
Android service permissions are shared. Runtime configuration and native control
are not shared between engines.

```text
LauncherManager
  -> CoreVpnService / CoreRootService / CoreProxyOnlyService
  -> CoreServiceManager (Android events and common resources)
  -> CoreSession (serialized operations and service ownership)
  -> CoreRuntimeFactory (one selection per launch)
       -> core/xray/XrayRuntime
       -> core/singbox/SingBoxRuntime
```

`CoreRuntime` exposes start, stop, restart, delay measurement, traffic counters and
screen events. It does not expose libbox or libv2ray types. Only the factory
switches on the selected engine.

## Xray

- `CoreConfigManager`, `CoreConfigContextBuilder` and `CoreOutboundBuilder` remain
  the Xray configuration pipeline; their existing file paths are retained.
- `XrayRuntime` owns its controller, process finder and browser dialer.
- `XrayVpnConfigurator` builds the Android TUN passed to Xray.
- Network handover and the optional OEM guard request recovery of the current
  session. Those mechanisms are not installed for sing-box.

## sing-box

- `SingBoxConfigGenerator` creates a libbox configuration.
- `SingBoxRuntime` owns a session-scoped `SingBoxCoreManager`.
- No Xray controller or Xray environment is initialized by this runtime.
- `SingBoxPlatformInterface` reports the physical network directly to libbox.
  Network handover does not trigger the Xray whole-core restart path.
- `SingBoxLocalDns` binds local/bootstrap DNS to the physical network.
- Domain-based servers use `bootstrap-local`, independently of DNS routed through
  the proxy being established.
- `SingBoxVpnConfigurator` consumes libbox's TUN addresses, MTU, DNS and route
  ranges. The JSON configuration is their source of truth.
- The Android service owns the returned TUN descriptor; libbox duplicates it.
  The service closes its descriptor after serialized native shutdown.

## Lifecycle and cancellation

`CoreSession` serializes native start, restart, stop and delay operations.
Each launch has a revision and an Android service owner. A stop invalidates
pending recovery before waiting for native code to return. A destroyed old
service cannot stop a replacement service's runtime.

Native startup is successful when the core is running and its required Android
interface exists. A public website being slow does not fail service startup.
Connectivity tests are diagnostics, not an implicit permission to restart a core.
Root modes still verify outbound connectivity before installing redirection.

## Dashboard and diagnostics

Exit IP and location are separate from runtime state. Initial exit IP requests
retry promptly; if all fail, foreground recovery retries every 15 seconds without
restarting the core. Backgrounding, disconnecting or replacing the request
invalidates that recovery. Location enrichment retains a known IP.

The app log page includes bounded private logs:

- `files/diagnostics/core-runtime.log`: lifecycle and selected engine.
- `files/diagnostics/exit-ip.log`: attempts and sanitized failure categories.

Each channel keeps one rotated file. These records deliberately omit node
credentials, subscription links, addresses and response bodies. They supplement,
not replace, a future full libbox CommandClient log integration.

## Verification

Unit tests cover ownership replacement, stop-during-start, stop-during-reload,
cleanup after failure, independent runtime selection, DNS bootstrap/TUN JSON,
and cancellation/recovery of dashboard queries.

Device checks remain necessary for Android VPN permissions, real native DNS
callbacks, network handover, Root routing and protocol interoperability.
