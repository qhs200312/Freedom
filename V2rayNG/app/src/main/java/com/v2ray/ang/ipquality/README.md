# Exit IP quality

Independent implementation of the supplied FlClash 0.9.2 behavior specification.
Classification/parser and scheduling files acknowledge the GPL-3.0 logic reference.
No FlClash source code or additional dependencies are imported.

## Integration

- Kotlin, Compose, OkHttp, Gson and the existing encrypted MMKV settings store.
- `IpQualityPanel` is attached to the existing home exit-IP card. It uses the
  already discovered exit IP, rather than probing every node or resolving DNS.
- The current core's authenticated SOCKS listener carries requests while connected.
  A failed local proxy does not cause a fallback to the physical connection.
- Detail UI accepts a numeric IPv4/IPv6 address and displays type, grade, flags,
  organization, ASN and source. The home and details use the same persisted
  hide-IP switch as Settings > UI settings.
- Home shows the grade and type directly beside the existing IP address,
  without prefixes or a duplicate quality subsection. Long addresses/small
  screens can wrap. Details retain explicitly labelled type and quality fields.
  Chinese residential/hosting labels are "家宽 IP" and "机房 IP"; inferred types
  are marked. Custom lookups in details never relabel the current exit address.
- Exit IP precedes runtime status on the dashboard. Upload/download number
  tiles remain; the live speed chart is removed from the dashboard.
- Only the visible, resumed home composable queries. Leaving it cancels jobs
  and the associated OkHttp calls. There is no worker, timer or background polling.

## Behavioral choices

Later waves are gated by completion of all earlier requests without an explicit
type. Wave 2, 3 and 4 have earliest starts at 1, 2 and 3 seconds, measured from
the start of the query. An unresolved earlier request never triggers additional
quota spending merely because one second elapsed. Each source has an 8-second
timeout. A declared winner cancels the other requests immediately.

An HTTP-200 response containing a recognized record but no type is inferred
residential. An empty/missing record is `noType`; malformed JSON is `failed`.
ident.me must return a matching IP, including canonical IPv6 equivalence.
Only actual JSON boolean `true` sets flags, not strings or numeric substitutes.

Cache keys use canonical numeric IP addresses, with a three-day success TTL and
a five-minute persistent failure cooldown. Manual refresh bypasses successful
cache entries, but does not bypass failure cooldowns. Cancelling a lookup does
not write a failure entry.

`good` follows the requested type rule and is not a promise that an IP has no
proxy/VPN flags. No extra risk heuristic is added.

## Mock JSON manual validation (no network)

From the project root, in PowerShell:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
.\gradlew.bat :app:testFdroidDebugUnitTest --offline --info --tests 'com.v2ray.ang.ipquality.IpQualityMockExampleTest'
```

The fixture is `app/src/test/resources/ipquality/mobile.json`. Other sources
return `noType`; the scheduler reaches ipApiIs and prints:

```text
MOCK: ip=203.0.113.8, type=mobile, level=good, proxy=true, vpn=true, source=ipApiIs, asn=AS64500
```

Change `is_tor` to `true`, and change the example assertion from `good` to
`risky` to validate the risk override. Change `is_datacenter` to `true` to
validate that hosting dominates mobile. Tests never send this documentation IP
to an external service.

## Changed files

Production module (this directory):
`IpQuality.kt`, `IpQualityParser.kt`, `IpQualityScheduler.kt`,
`IpQualityRepository.kt`, `IpQualityPlatform.kt`, `IpQualityUi.kt`, `README.md`.

Existing integration/build files:
`app/build.gradle.kts`, `app/proguard-rules.pro`,
`app/src/main/java/com/v2ray/ang/util/HttpUtil.kt`,
`app/src/main/java/com/v2ray/ang/ui/main/HomeDashboard.kt`,
`app/src/main/java/com/v2ray/ang/ui/settings/SettingsActivity.kt`.

New resources:
`app/src/main/res/values/ip_quality.xml`,
`app/src/main/res/values-zh-rCN/ip_quality.xml`.

New verification files:
`app/src/test/java/com/v2ray/ang/ipquality/IpQualityParserTest.kt`,
`app/src/test/java/com/v2ray/ang/ipquality/IpQualitySchedulerTest.kt`,
`app/src/test/java/com/v2ray/ang/ipquality/IpQualityTransportTest.kt`,
`app/src/test/java/com/v2ray/ang/ipquality/IpQualityMockExampleTest.kt`,
`app/src/test/resources/ipquality/mobile.json`,
`app/src/androidTest/java/com/v2ray/ang/IpQualityInstrumentedTest.kt`,
`app/src/androidTest/java/com/v2ray/ang/ReleaseHardeningInstrumentedTest.kt`.

## Release hardening

Release builds enable R8 and resource shrinking. Gson protocol/storage models
and JNI/gomobile interfaces have explicit keep rules. Keep the generated
`app/build/outputs/mapping/<variant>/mapping.txt` privately for crash retracing;
do not ship it alongside a public APK.

This is code obfuscation and optimization, not an encryption/packing shell.
The prebuilt native cores are outside R8's scope. Existing source/license
obligations are unaffected. APK signing uses the project's existing release
certificate configuration.
