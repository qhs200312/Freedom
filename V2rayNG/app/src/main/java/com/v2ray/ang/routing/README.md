# YAML routing import

The routing settings menu and add menu expose **Import YAML rules**. Users can paste a provider, open a UTF-8 file, or enter an HTTPS URL. GitHub `blob` links are normalized to raw content URLs before downloading.

The input is the block-list form of a Clash **classical** rule provider:

```yaml
payload:
  - DOMAIN,exact.example.com
  - DOMAIN-SUFFIX,example.com
  - DOMAIN-KEYWORD,service
  - 'DOMAIN-REGEX,^cdn[0-9]{1,3}\.example$'
  - IP-CIDR,192.0.2.0/24
  - IP-CIDR6,2001:db8::/32
  - DST-PORT,8443-8445
  - NETWORK,UDP
```

This focused parser supports plain, single-quoted and double-quoted single-line scalars, comments, UTF-8 BOM, CRLF, and document delimiters. It does not execute YAML tags or expand anchors. Inline collections, full Clash configurations, other rule types, outbound columns, and `no-resolve` flags are rejected with a line number. A failed preview never changes stored routing rules.

Users choose one target for the entire import: current node, direct, block, or a specific stored node. Preview reports unique domain, IP, port and network entry counts. Confirmation prepends generated rules while retaining all existing rules. Existing application-routing precedence still applies. Reconnect to activate the changed rules.

Conditions are stored as ordinary `RulesetItem` objects in the existing MMKV settings. Domain/IP/network entries are grouped independently; each destination port condition gets its own rule. Combining independent domain and IP entries in a single native rule would accidentally turn OR into AND. Both core config generators continue to consume the same model.

The parser is implemented in Rust under `native/rules-parser`. `ClashYamlRules` is a JVM test/compatibility adapter; application imports use cancellable JNI file parsing. Local files and HTTP responses stream into a private temporary file using an 8 KiB buffer. The raw provider never enters a large UI text field or crosses JNI as a giant string. Temporary files are deleted on completion, failure and cancellation.

Limits: 8 MiB input, 16 KiB per line, 100,000 raw entries, 50,000 unique entries, 2 MiB normalized content, 4 MiB JNI response, 128 regex rules of at most 1 KiB, and 256 port conditions. Rust checks cancellation and a 3-second wall-clock budget while reading. A single worker thread and at most two outstanding native sessions bound concurrency. HTTP calls have a separate 30-second timeout. Direct paste is limited to 64K characters; larger providers use the file/URL paths.

Total stored routing conditions also have a budget: 2,000 rows, 100,000 conditions, 4 MiB of condition/metadata characters, 256 regexes and 512 port rows. Imports exceeding a cumulative budget fail before the existing MMKV value is changed.

MMKV decoding, encoding and persistence run off the main thread in routing settings and the rule editor. List summaries inspect at most three entries. Rules exceeding 1,000 items or 32K characters are shown as a summary in the editor; their underlying conditions are retained when changing the name or target. URL imports remain local snapshots, updated by importing again.

Rust bounds regex compilation size and depth, then the preview checks compatibility with the bundled Go implementation. The source line is retained in either failure. Go literal quoting (`\Q...\E`) is preserved.

Verification:

- `ClashYamlRulesTest`: mappings, normalization, scalar forms, invalid/unsupported input, limits, target identity and both generated config schemas.
- `RoutingRuleImportLoaderTest`: GitHub URLs, bounded UTF-8 reads, HTTP errors and cancellation.
- `YamlRoutingInstrumentedTest`: bundled sing-box/Xray acceptance, failed-preview storage preservation, file import and persistence.
- `RoutingRuleLimitsTest`: bounded list presentation, safe editing limits and cumulative storage limits.
- `RustRuleParserInstrumentedTest`: cancellation, oversized paste, long lines, 50,000-rule import/save/editor performance with main-looper heartbeats.
- The Rust crate has its own tests for normalization, malformed inputs, cancellation, deadlines and all parsing budgets. Gradle unit tests load the host JNI library, so the same parser runs on the JVM and Android.

For a manual test, open routing settings, select **Import YAML rules**, paste the GitHub MetaCubeX classical `meta.yaml` link, preview, choose a destination, and confirm. Reopen the resulting rule to inspect the converted domains. Import a second file with an unsupported line such as `IP-ASN,12345` and confirm that it reports the offending line without adding any rules.
