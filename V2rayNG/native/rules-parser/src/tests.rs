use super::*;
use std::io::{BufReader, Cursor, Read, Write};
use std::sync::Arc;

fn decode(input: &str) -> Result<Parsed, ParseError> {
    parse(
        Cursor::new(input.as_bytes()),
        &Budget::new(&AtomicBool::new(false), Duration::from_secs(30)),
    )
}

#[test]
fn maps_all_supported_conditions_and_unicode() {
    let input = "payload:\n- DOMAIN,EXAMPLE.COM.\n- DOMAIN-SUFFIX,.example.com\n\
        - DOMAIN,例子.中国\n- DOMAIN-KEYWORD,service\n- 'DOMAIN-REGEX,^cdn[0-9]{1,3}\\.example$'\n\
        - IP-CIDR,192.000.002.1\n- IP-CIDR6,2001:DB8::/32\n- DST-PORT,8000-8080\n- NETWORK,UDP";
    let result = decode(input).unwrap();
    assert_eq!(result.count(), 9);
    assert_eq!(result.domains[0], "full:example.com");
    assert_eq!(result.domains[2], "full:xn--fsqu00a.xn--fiqs8s");
    assert_eq!(result.ips, ["192.0.2.1/32", "2001:db8::/32"]);
    assert_eq!(result.regex_lines, [(4, 6)]);
}

#[test]
fn accepts_scalar_forms_comments_and_bom() {
    for input in [
        "payload:\n  - DOMAIN-SUFFIX,example.com",
        "\u{feff}# comment\r\n---\r\npayload: # comment\r\n    - 'DOMAIN-SUFFIX,example.com' # comment\r\n...",
        "payload:\n- \"DOMAIN-SUFFIX,example.com\"",
    ] { assert_eq!(decode(input).unwrap().domains, ["domain:example.com"]); }
    assert_eq!(
        decode("payload:\n- 'DOMAIN-KEYWORD,a''b#c'")
            .unwrap()
            .domains,
        ["keyword:a'b#c"]
    );
    assert_eq!(
        decode(
            r#"payload:
- "DOMAIN-REGEX,^foo\\.example#part\u0024""#
        )
        .unwrap()
        .domains,
        [r"regexp:^foo\.example#part$"]
    );
}

#[test]
fn deduplicates_without_reordering() {
    let result = decode(
        "payload:\n- DOMAIN,EXAMPLE.COM.\n- DOMAIN,example.com\n- DOMAIN-SUFFIX,example.com",
    )
    .unwrap();
    assert_eq!(result.domains, ["full:example.com", "domain:example.com"]);
}

#[test]
fn preserves_go_literal_regex_quoting() {
    let result = decode(
        r"payload:
- DOMAIN-REGEX,^\Q(a{1000})\E$",
    )
    .unwrap();
    assert_eq!(result.domains, [r"regexp:^\Q(a{1000})\E$"]);
}

#[test]
fn reports_exact_failure_lines() {
    for (input, reason, line) in [
        ("", Failure::Format, 0),
        ("payload: []", Failure::Format, 1),
        ("rules:\n- DOMAIN,a.com", Failure::Format, 1),
        ("payload:\n- &x DOMAIN,a.com", Failure::Format, 2),
        ("payload:\n- *x", Failure::Format, 2),
        ("payload:\n\t- DOMAIN,a.com", Failure::Format, 2),
        ("payload:\n- \"DOMAIN,a.com", Failure::Format, 2),
        ("payload:\n- \"DOMAIN-REGEX,\\q\"", Failure::Format, 2),
        (
            "payload:\n- DOMAIN,a.com\n  - DOMAIN,b.com",
            Failure::Format,
            3,
        ),
        (
            "payload:\n- DOMAIN,a.com\n- IP-ASN,123",
            Failure::Unsupported,
            3,
        ),
        (
            "payload:\n- IP-CIDR,192.0.2.0/24,no-resolve",
            Failure::Unsupported,
            2,
        ),
        ("payload:\n- DOMAIN,a.com,DIRECT", Failure::Unsupported, 2),
        ("payload:\n- DOMAIN,*.example.com", Failure::Value, 2),
        ("payload:\n- IP-CIDR,256.0.0.1/32", Failure::Value, 2),
        ("payload:\n- IP-CIDR6,2001::db8::/32", Failure::Value, 2),
        ("payload:\n- DST-PORT,8080-8000", Failure::Value, 2),
        ("payload:\n- NETWORK,icmp", Failure::Value, 2),
        ("payload:\n- DOMAIN-REGEX,(?=lookahead)", Failure::Value, 2),
        ("payload:\n- DOMAIN-REGEX,(", Failure::Value, 2),
        ("payload:\n# empty", Failure::Empty, 0),
    ] {
        let error = decode(input).unwrap_err();
        assert_eq!((error.failure, error.line), (reason, line), "{input}");
    }
}

#[test]
fn never_allocates_an_unbounded_line() {
    let input = format!("payload:\n#{}", "a".repeat(MAX_LINE_BYTES + 1));
    assert_eq!(decode(&input).unwrap_err().failure, Failure::TooLarge);
}

#[test]
fn caps_total_bytes_even_for_comments() {
    let input = format!(
        "payload:\n{}",
        format!("#{}\n", "a".repeat(1000)).repeat(8400)
    );
    assert_eq!(decode(&input).unwrap_err().failure, Failure::TooLarge);
}

#[test]
fn accepts_fifty_thousand_and_refuses_the_next_unique_rule() {
    let mut input = String::from("payload:\n");
    for i in 0..MAX_UNIQUE {
        input.push_str(&format!("- DOMAIN-SUFFIX,d{i}.example\n"));
    }
    assert_eq!(decode(&input).unwrap().count(), MAX_UNIQUE);
    input.push_str("- DOMAIN-SUFFIX,overflow.example\n");
    assert_eq!(decode(&input).unwrap_err().failure, Failure::TooLarge);
}

#[test]
fn limits_entries_ports_regex_count_and_regex_size() {
    assert_eq!(
        decode(&format!(
            "payload:\n{}",
            "- DOMAIN,a.com\n".repeat(MAX_ENTRIES + 1)
        ))
        .unwrap_err()
        .failure,
        Failure::TooLarge
    );
    let ports = (1..=MAX_PORTS + 1)
        .map(|p| format!("- DST-PORT,{p}\n"))
        .collect::<String>();
    assert_eq!(
        decode(&format!("payload:\n{ports}")).unwrap_err().failure,
        Failure::TooLarge
    );
    let regexes = (0..=MAX_REGEX)
        .map(|i| format!("- DOMAIN-REGEX,^d{i}$\n"))
        .collect::<String>();
    assert_eq!(
        decode(&format!("payload:\n{regexes}")).unwrap_err().failure,
        Failure::TooLarge
    );
    assert_eq!(
        decode(&format!(
            "payload:\n- DOMAIN-REGEX,{}",
            "a".repeat(MAX_REGEX_BYTES + 1)
        ))
        .unwrap_err()
        .failure,
        Failure::TooLarge
    );
    assert_eq!(
        decode("payload:\n- DOMAIN-REGEX,(a{1000}){1000}")
            .unwrap_err()
            .failure,
        Failure::Value
    );
}

#[test]
fn rejects_invalid_utf8_without_replacement() {
    let input = [b"payload:\n- DOMAIN,".as_slice(), &[0xff]].concat();
    assert_eq!(
        parse(
            Cursor::new(input),
            &Budget::new(&AtomicBool::new(false), PARSE_TIMEOUT)
        )
        .unwrap_err()
        .failure,
        Failure::Format
    );
}

#[test]
fn cancellation_and_deadline_are_explicit_failures() {
    assert_eq!(
        parse(
            Cursor::new("payload:\n- DOMAIN,a.com"),
            &Budget::new(&AtomicBool::new(true), PARSE_TIMEOUT)
        )
        .unwrap_err()
        .failure,
        Failure::Cancelled
    );
    assert_eq!(
        parse(
            Cursor::new("payload:\n- DOMAIN,a.com"),
            &Budget::new(&AtomicBool::new(false), Duration::ZERO)
        )
        .unwrap_err()
        .failure,
        Failure::Timeout
    );
}

#[test]
fn cancels_while_reading_a_large_provider() {
    struct Slow(Cursor<Vec<u8>>);
    impl Read for Slow {
        fn read(&mut self, out: &mut [u8]) -> io::Result<usize> {
            std::thread::sleep(Duration::from_millis(1));
            self.0.read(out)
        }
    }
    let flag = Arc::new(AtomicBool::new(false));
    let worker_flag = flag.clone();
    let worker = std::thread::spawn(move || {
        let input = format!("payload:\n{}", "- DOMAIN,a.com\n".repeat(90_000));
        parse(
            BufReader::with_capacity(128, Slow(Cursor::new(input.into_bytes()))),
            &Budget::new(&worker_flag, PARSE_TIMEOUT),
        )
    });
    std::thread::sleep(Duration::from_millis(20));
    flag.store(true, Ordering::Relaxed);
    assert_eq!(
        worker.join().unwrap().unwrap_err().failure,
        Failure::Cancelled
    );
}

#[test]
fn caps_jni_result_serialization() {
    let mut writer = LimitedWriter { bytes: Vec::new() };
    assert!(writer.write_all(&vec![b'a'; MAX_JSON_BYTES + 1]).is_err());
    assert!(writer.bytes.is_empty());
}
