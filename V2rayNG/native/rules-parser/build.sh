#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ndk="${FREEDOM_RULES_NDK:?Set FREEDOM_RULES_NDK to Android NDK 28.2.13676358}"
output="${FREEDOM_RULES_OUTPUT:?Set FREEDOM_RULES_OUTPUT}"
case "$(uname -s)" in
  Darwin) host="darwin-x86_64" ;;
  Linux) host="linux-x86_64" ;;
  *) echo "Unsupported build host" >&2; exit 1 ;;
esac
IFS=';' read -r -a abis <<< "${FREEDOM_RULES_ABIS:-arm64-v8a;armeabi-v7a;x86_64;x86}"
for abi in "${abis[@]}"; do
  case "$abi" in
    arm64-v8a) target="aarch64-linux-android"; clang="aarch64-linux-android24-clang" ;;
    armeabi-v7a) target="armv7-linux-androideabi"; clang="armv7a-linux-androideabi24-clang" ;;
    x86_64) target="x86_64-linux-android"; clang="x86_64-linux-android24-clang" ;;
    x86) target="i686-linux-android"; clang="i686-linux-android24-clang" ;;
    *) echo "Unsupported ABI: $abi" >&2; exit 1 ;;
  esac
  key="$(echo "$target" | tr '[:lower:]-' '[:upper:]_')"
  env "CARGO_TARGET_${key}_LINKER=$ndk/toolchains/llvm/prebuilt/$host/bin/$clang" \
    "CARGO_TARGET_${key}_RUSTFLAGS=-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384" \
    cargo build --manifest-path "$root/Cargo.toml" --release --locked --target "$target"
  mkdir -p "$output/$abi"
  cp "$root/target/$target/release/libfreedom_rules.so" "$output/$abi/"
done
