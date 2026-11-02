# Freedom Rust rule parser

This independent Rust crate parses the supported block-list form of Clash classical YAML. It normalizes domain, IP, port and network conditions and returns bounded bulk text to the existing Kotlin routing model. It is not a general-purpose YAML deserializer and does not expand aliases or execute tags.

The source is independent project code. `Cargo.lock` pins the Rust dependencies. JNI is provided by `jni`; `idna` normalizes internationalized domain names; `regex` supplies bounded regex validation; `serde`/`serde_json` handle VMess JSON and the legacy result protocol. `base64` 0.22.1 (MIT OR Apache-2.0) handles subscription envelopes and VMess payloads.

## Native interfaces

- YAML results use an `int[]` for versioned lengths/counts and a `char[]` for UTF-16 text, returned in one two-element object array. Kotlin checks all boundaries before reconstructing strings. This avoids per-rule JNI calls, JSON parsing, and a separate UTF-8 decoder invocation for every string.
- The original JSON and intermediate byte-packet entry points remain available for compatibility and local comparison tools; normal YAML import uses the bulk-text interface.
- Subscription preparation reads a bounded private file, optionally decodes standard/URL-safe Base64, deduplicates exact lines, and returns the original reverse import order. VMess JSON scalar fields use a native fast path; unusual VMess data and other protocol links retain the existing Kotlin protocol parsers.
- Subscription records use a versioned, length-prefixed UTF-8 byte packet. Downloading respects HTTP charset/BOM decoding while writing bounded UTF-8 files, and deletes temporary files on completion or failure.
- Runtime rule conversion uses a per-build snapshot and a Kotlin identity cache. A prototype native domain classifier was removed after release benchmarks showed that its extra marshalling cost outweighed the small classification loop.

## Build

Requirements: Rust with Cargo (verified with rustc 1.99.1), Java 17 and Android NDK 28.2.13676358.

```text
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
cargo fetch --manifest-path native/rules-parser/Cargo.toml --locked
cargo test --manifest-path native/rules-parser/Cargo.toml --locked --offline
```

Normal Gradle builds run `buildAndroidRuleParser` before merging native libraries. It uses `build.ps1` on Windows and `build.sh` on Linux/macOS, respects `ABI_FILTERS`, and generates JNI libraries under `app/build/generated/rulesParser/jniLibs`. Gradle unit tests also build/load the host library, so no Kotlin parser fallback is involved.

All four Android ABIs use API 24 and 16 KiB ELF page alignment. JNI class/method names are retained by the application's ProGuard configuration. The only unsafe platform boundary is handled by the JNI crate; the parser uses safe Rust, bounded buffers and standard owned collections.

## Budgets

| Resource | Limit |
| --- | --- |
| Input | 8 MiB |
| Single line | 16 KiB, checked before extending the buffer |
| Raw entries / unique entries | 100,000 / 50,000 |
| Normalized strings / legacy JSON response | 2 MiB / 4 MiB |
| YAML bulk-text metadata / text | 100,000 integers / 2 Mi UTF-16 code units |
| Regular expressions | 128, each at most 1 KiB |
| Regex compiled size / nesting depth | 256 KiB / 32 |
| Independent port conditions | 256 |
| Rust parsing deadline | 3 seconds, checked during reading and parsing |
| Concurrent outstanding sessions | 2; asynchronous YAML parsing uses one Kotlin worker |
| Subscription decoded input / unique lines / individual link | 8 MiB / 20,000 / 64 KiB |
| Subscription binary response | 16 MiB |

Cancellation flags live in a small mutex-protected registry of `Arc<AtomicBool>` values, avoiding raw-pointer lifetime races. Rust checks cancellation at every bounded read and line. JNI catches parser panics and reports an error. Kotlin also checks cancellation while decoding and validating results. The Go compatibility check has a separate 3-second cumulative budget.

The application additionally bounds pasting, total stored routing conditions and editor text sizes. These limits matter alongside using Rust: moving parsing to native code alone cannot prevent UI stalls from large text fields or persistence on the main thread.
