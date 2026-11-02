# Routing node health

The home exit-IP card displays "Connection failed" only for failed routing targets.
Normal, unknown and initially checking targets add no status text or space.
The runtime card only shows memory and mode.
The warning opens details with target names, associated rule names and retry.
Routing rules show the same service-owned status beside their target name.
The main connection state and exit-IP classification are independent.

## Probe path

Each distinct enabled non-builtin routing tag gets an authenticated SOCKS
listener on loopback in the running primary core. A rule matching only that
inbound is prepended and points at the actual outbound (or Xray balancer).
Thus the check includes configured auxiliary-core bridges and proxy chains.
Ordinary routing rules and default outbounds are preserved. Probe traffic has
no fallback to a different outbound. Credentials remain in the service process;
only display status is sent through package-scoped broadcasts.

An HTTPS 2xx response from Cloudflare's connectivity endpoint is success.
Failure is retried after 800 ms against Google's connectivity endpoint before
marking the target unavailable. Each request has a five-second total timeout,
TLS verification, no redirects, and cancellable OkHttp calls. At most two
targets are checked concurrently. No separate testing core is started.

Connection readiness triggers one check. Resumed home/routing screens request
checks once per minute; the service coalesces overlap and throttles automatic
requests. Manual retry bypasses the interval but not an active check.
Already-failed targets retain their warning during retry. Success clears it.
Stopping/replacing the runtime cancels its probes and clears its status.
No available underlying network produces unknown rather than node failure.

## Limits

- These are HTTPS reachability checks, not guarantees for UDP, every website,
  DNS behavior, or every member of a policy group.
- There are no native per-request failure callbacks; a failure between checks
  becomes visible on the next visible-screen check or manual retry.
- At most 32 listeners are added; additional targets show not checked.
- Unresolvable targets show target not loaded. Existing config-generation
  fallback behavior for missing targets is not changed by this module.
- Custom Xray configuration files are not modified and have no probe results.
- Auxiliary-core startup/recovery failures still follow the existing global
  service failure path.

## Verification

`RoutingHealthTest` covers independent retry, cancellation, both config formats,
authentication, rule ordering, deduplication, missing targets, policy groups,
listener limits, port collisions and allocation failure.

Build/test commands (JDK 17):

```powershell
.\gradlew.bat :app:testFdroidDebugUnitTest :app:testPlaystoreDebugUnitTest :app:assembleFdroidRelease '-PABI_FILTERS=arm64-v8a'
```

Device verification should check a working routing node, temporarily reject
that node's destination only, observe the warning with the main connection
still active, then remove the rejection and retry. It must also check the
matching routing rule and cancellation after disconnect. Never leave a test
firewall rule or modified node behind.
