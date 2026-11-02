use crate::{packet::Packet, Budget, Failure, ParseError, MAX_BYTES};
use base64::{
    alphabet,
    engine::{general_purpose::GeneralPurpose, DecodePaddingMode, GeneralPurposeConfig},
    Engine,
};
use serde_json::Value;
use std::collections::HashSet;

pub const MAX_NODES: usize = 20_000;
pub const MAX_LINK: usize = 64 * 1024;
const VMESS_FIELDS: [&str; 18] = [
    "ps", "add", "port", "id", "scy", "net", "type", "host", "path", "tls", "sni", "fp", "alpn",
    "insecure", "vcn", "pcs", "v", "aid",
];

fn decode(input: &[u8]) -> Option<Vec<u8>> {
    // Match Android's standard/URL-safe, optional-padding subscription inputs.
    let mut clean: Vec<u8> = input
        .iter()
        .copied()
        .filter(|b| !b.is_ascii_whitespace())
        .collect();
    while clean.last() == Some(&b'=') {
        clean.pop();
    }
    let config = GeneralPurposeConfig::new()
        .with_decode_padding_mode(DecodePaddingMode::Indifferent)
        .with_decode_allow_trailing_bits(true);
    GeneralPurpose::new(&alphabet::STANDARD, config)
        .decode(&clean)
        .ok()
        .or_else(|| {
            GeneralPurpose::new(&alphabet::URL_SAFE, config)
                .decode(&clean)
                .ok()
        })
}

fn vmess(line: &str) -> Option<Vec<String>> {
    if line.contains('?') && line.contains('&') {
        return None;
    }
    let decoded = decode(line.strip_prefix("vmess://")?.as_bytes())?;
    let value: Value = serde_json::from_slice(&decoded).ok()?;
    let object = value.as_object()?;
    let mut fields = Vec::with_capacity(VMESS_FIELDS.len());
    for key in VMESS_FIELDS {
        fields.push(match object.get(key) {
            None => {
                if key == "aid" {
                    "0".into()
                } else {
                    String::new()
                }
            }
            Some(Value::String(value)) => value.clone(),
            // Gson's String adapter preserves number spelling. Floats/exponents or
            // oversized integers could be reformatted by serde_json, so leave them
            // to the original parser instead of changing a node field.
            Some(Value::Number(value)) if value.is_i64() || value.is_u64() => value.to_string(),
            Some(Value::Bool(value)) => value.to_string(),
            _ => return None, // Keep legacy handling of null or unusual JSON values.
        });
    }
    if [1, 2, 3, 5].iter().any(|index| fields[*index].is_empty()) {
        return None;
    }
    Some(fields)
}

pub fn prepare(input: &[u8], encoded: bool, budget: &Budget<'_>) -> Result<Vec<u8>, ParseError> {
    if input.len() > MAX_BYTES {
        return Err(ParseError::new(Failure::TooLarge, 0, "subscription"));
    }
    budget.check(0)?;
    let decoded;
    let bytes = if encoded {
        decoded = decode(input).unwrap_or_default();
        &decoded[..]
    } else {
        input
    };
    let text = String::from_utf8_lossy(bytes);
    // CR/LF splitting reproduces Kotlin lines(), including untrimmed link identity.
    let mut seen = HashSet::new();
    let mut entries = Vec::new();
    for (index, line) in text.split(['\r', '\n']).enumerate() {
        budget.check(index + 1)?;
        if line.len() > MAX_LINK {
            return Err(ParseError::new(Failure::TooLarge, index + 1, "link"));
        }
        if line.is_empty() || !seen.insert(line) {
            continue;
        }
        if entries.len() >= MAX_NODES {
            return Err(ParseError::new(Failure::TooLarge, index + 1, "nodes"));
        }
        entries.push(line);
    }
    let mut packet = Packet::new(2);
    packet.number(entries.len() as u32);
    for line in entries.into_iter().rev() {
        budget.check(0)?;
        packet.string(line)?;
        if let Some(fields) = vmess(line) {
            packet.strings(&fields)?;
        } else {
            packet.number(0);
        }
    }
    Ok(packet.0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::packet::Reader;
    use std::sync::atomic::AtomicBool;
    use std::time::Duration;

    #[test]
    fn envelope_order_dedup_and_variants() {
        let flag = AtomicBool::new(false);
        let budget = Budget::new(&flag, Duration::from_secs(3));
        for (input, encoded) in [
            (b"a\r\nb\ra\n".to_vec(), false),
            (b"YQ0KYg1hCg==\n".to_vec(), true),
        ] {
            let result = prepare(&input, encoded, &budget).unwrap();
            let mut reader = Reader::new(&result);
            assert_eq!(reader.number().unwrap(), 0x46525031);
            assert_eq!(reader.number().unwrap(), 2);
            assert_eq!(reader.number().unwrap(), 2);
            assert_eq!(reader.string().unwrap(), "b");
            assert_eq!(reader.number().unwrap(), 0);
            assert_eq!(reader.string().unwrap(), "a");
            assert_eq!(reader.number().unwrap(), 0);
            reader.finish().unwrap();
        }
    }

    #[test]
    fn vmess_fields_preserve_gson_scalar_semantics_and_fallback() {
        let encode = |json: &str| {
            format!(
                "vmess://{}",
                base64::engine::general_purpose::STANDARD.encode(json)
            )
        };
        let good = encode(r#"{"add":"host","port":443,"id":"id","net":"tcp","insecure":true}"#);
        let fields = vmess(&good).unwrap();
        assert_eq!(fields[2], "443");
        assert_eq!(fields[13], "true");
        assert!(vmess(&encode(
            r#"{"add":"host","port":443,"id":"id","net":"tcp","ps":1e6}"#
        ))
        .is_none());
        assert!(vmess(&encode(
            r#"{"add":"host","port":443,"id":"id","net":"tcp","ps":123456789012345678901234567890}"#
        ))
        .is_none());
        assert!(vmess(&encode(
            r#"{"add":"host","port":443,"id":"id","net":"tcp","ps":null}"#
        ))
        .is_none());
    }

    #[test]
    fn rejects_limits_and_cancellation() {
        let flag = AtomicBool::new(false);
        let budget = Budget::new(&flag, Duration::from_secs(3));
        assert_eq!(
            prepare(&vec![b'a'; MAX_LINK + 1], false, &budget)
                .unwrap_err()
                .failure,
            Failure::TooLarge
        );
        flag.store(true, std::sync::atomic::Ordering::Relaxed);
        assert_eq!(
            prepare(b"node", false, &budget).unwrap_err().failure,
            Failure::Cancelled
        );
    }
}
