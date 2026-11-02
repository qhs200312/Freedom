mod bridge;
mod packet;
mod subscription;
mod text_packet;

use serde::Serialize;
use std::collections::HashSet;
use std::io::{self, BufRead};
use std::net::Ipv6Addr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

pub const MAX_BYTES: usize = 8 * 1024 * 1024;
pub const MAX_LINE_BYTES: usize = 16 * 1024;
pub const MAX_ENTRIES: usize = 100_000;
pub const MAX_UNIQUE: usize = 50_000;
pub const MAX_NORMALIZED_BYTES: usize = 2 * 1024 * 1024;
pub const MAX_JSON_BYTES: usize = 4 * 1024 * 1024;
pub const MAX_REGEX: usize = 128;
pub const MAX_REGEX_BYTES: usize = 1024;
pub const MAX_PORTS: usize = 256;
pub const PARSE_TIMEOUT: Duration = Duration::from_secs(3);

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Failure {
    Format,
    Unsupported,
    Value,
    Empty,
    TooLarge,
    Read,
    Timeout,
    Cancelled,
    Busy,
}

#[derive(Debug, Serialize)]
pub struct ParseError {
    pub failure: Failure,
    pub line: usize,
    pub detail: String,
}

impl ParseError {
    fn new(failure: Failure, line: usize, detail: &str) -> Self {
        Self {
            failure,
            line,
            detail: detail.chars().take(80).collect(),
        }
    }
}

#[derive(Debug, Default, Serialize)]
pub struct Parsed {
    pub domains: Vec<String>,
    pub ips: Vec<String>,
    pub ports: Vec<String>,
    pub networks: Vec<String>,
    pub regex_lines: Vec<(usize, usize)>,
}

impl Parsed {
    pub fn count(&self) -> usize {
        self.domains.len() + self.ips.len() + self.ports.len() + self.networks.len()
    }
}

pub struct Budget<'a> {
    cancelled: &'a AtomicBool,
    started: Instant,
    timeout: Duration,
}

impl<'a> Budget<'a> {
    pub fn new(cancelled: &'a AtomicBool, timeout: Duration) -> Self {
        Self {
            cancelled,
            started: Instant::now(),
            timeout,
        }
    }

    fn check(&self, line: usize) -> Result<(), ParseError> {
        if self.cancelled.load(Ordering::Relaxed) {
            return Err(ParseError::new(Failure::Cancelled, line, ""));
        }
        if self.started.elapsed() >= self.timeout {
            return Err(ParseError::new(Failure::Timeout, line, ""));
        }
        Ok(())
    }
}

// fill_buf avoids read_line/read_until allocating an arbitrarily long input line.
fn read_line<R: BufRead>(
    reader: &mut R,
    buffer: &mut Vec<u8>,
    total: &mut usize,
    line: usize,
    budget: &Budget<'_>,
) -> Result<bool, ParseError> {
    buffer.clear();
    loop {
        budget.check(line)?;
        let available = reader
            .fill_buf()
            .map_err(|_| ParseError::new(Failure::Read, line, ""))?;
        if available.is_empty() {
            return Ok(!buffer.is_empty());
        }
        let end = available.iter().position(|b| *b == b'\n').map(|p| p + 1);
        let count = end.unwrap_or(available.len());
        if *total + count > MAX_BYTES || buffer.len() + count > MAX_LINE_BYTES {
            return Err(ParseError::new(Failure::TooLarge, line, "input/line"));
        }
        buffer.extend_from_slice(&available[..count]);
        *total += count;
        reader.consume(count);
        if end.is_some() {
            return Ok(true);
        }
    }
}

pub fn parse<R: BufRead>(mut reader: R, budget: &Budget<'_>) -> Result<Parsed, ParseError> {
    let mut parsed = Parsed::default();
    let mut seen = HashSet::<String>::new();
    let mut normalized_bytes = 0;
    let mut total = 0;
    let mut line = 0;
    let mut entries = 0;
    let mut payload = false;
    let mut ended = false;
    let mut indent = None;
    let mut buffer = Vec::with_capacity(4096);
    while read_line(&mut reader, &mut buffer, &mut total, line + 1, budget)? {
        line += 1;
        budget.check(line)?;
        let raw = std::str::from_utf8(&buffer)
            .map_err(|_| ParseError::new(Failure::Format, line, "UTF-8"))?;
        let raw = if line == 1 {
            raw.trim_start_matches('\u{feff}')
        } else {
            raw
        };
        if raw
            .chars()
            .take_while(|c| c.is_whitespace())
            .any(|c| c == '\t')
        {
            return Err(ParseError::new(Failure::Format, line, "indent"));
        }
        let text = strip_comment(raw).trim_end();
        if text.is_empty() {
            continue;
        }
        if text == "---" && !payload {
            continue;
        }
        if text == "..." && payload {
            ended = true;
            continue;
        }
        if ended {
            return Err(ParseError::new(Failure::Format, line, "document"));
        }
        if !payload {
            if text != "payload:" {
                return Err(ParseError::new(Failure::Format, line, "payload"));
            }
            payload = true;
            continue;
        }
        let current_indent = text.len() - text.trim_start().len();
        if *indent.get_or_insert(current_indent) != current_indent {
            return Err(ParseError::new(Failure::Format, line, "indent"));
        }
        let item = text
            .trim_start()
            .strip_prefix("- ")
            .ok_or_else(|| ParseError::new(Failure::Format, line, "list"))?;
        entries += 1;
        if entries > MAX_ENTRIES {
            return Err(ParseError::new(Failure::TooLarge, line, "entries"));
        }
        let value =
            scalar(item.trim()).map_err(|_| ParseError::new(Failure::Format, line, "scalar"))?;
        let (kind, argument) = value
            .split_once(',')
            .filter(|(kind, _)| !kind.trim().is_empty())
            .ok_or_else(|| ParseError::new(Failure::Format, line, "rule"))?;
        let kind = kind.trim().to_ascii_uppercase();
        let argument = argument.trim();
        if argument.is_empty() || argument.chars().any(|c| c.is_control()) {
            return Err(ParseError::new(Failure::Value, line, &kind));
        }
        if kind != "DOMAIN-REGEX" && argument.contains(',') {
            return Err(ParseError::new(Failure::Unsupported, line, &kind));
        }
        let invalid = || ParseError::new(Failure::Value, line, &kind);
        let normalized = match kind.as_str() {
            "DOMAIN" => format!("full:{}", domain(argument).ok_or_else(invalid)?),
            "DOMAIN-SUFFIX" => format!(
                "domain:{}",
                domain(argument.strip_prefix('.').unwrap_or(argument)).ok_or_else(invalid)?
            ),
            "DOMAIN-KEYWORD" => format!("keyword:{argument}"),
            "DOMAIN-REGEX" => format!("regexp:{argument}"),
            "IP-CIDR" => cidr(argument, false).ok_or_else(invalid)?,
            "IP-CIDR6" => cidr(argument, true).ok_or_else(invalid)?,
            "DST-PORT" => port(argument).ok_or_else(invalid)?,
            "NETWORK" => {
                let network = argument.to_ascii_lowercase();
                if network != "tcp" && network != "udp" {
                    return Err(invalid());
                }
                network
            }
            _ => return Err(ParseError::new(Failure::Unsupported, line, &kind)),
        };
        // Prefix the deduplication key so a keyword/IP/port cannot collide across rule types.
        let group = match kind.as_str() {
            "IP-CIDR" | "IP-CIDR6" => 'i',
            "DST-PORT" => 'p',
            "NETWORK" => 'n',
            _ => 'd',
        };
        let key = format!("{group}{normalized}");
        if seen.contains(&key) {
            continue;
        }
        if parsed.count() >= MAX_UNIQUE
            || normalized_bytes + normalized.len() > MAX_NORMALIZED_BYTES
        {
            return Err(ParseError::new(Failure::TooLarge, line, "normalized rules"));
        }
        if kind == "DOMAIN-REGEX" {
            if parsed.regex_lines.len() >= MAX_REGEX || argument.len() > MAX_REGEX_BYTES {
                return Err(ParseError::new(Failure::TooLarge, line, "regex budget"));
            }
            // A compiled-size and nesting limit bounds adversarial regex expansion before Go validation.
            regex::RegexBuilder::new(&rust_regex_pattern(argument))
                .size_limit(256 * 1024)
                .dfa_size_limit(128 * 1024)
                .nest_limit(32)
                .build()
                .map_err(|_| invalid())?;
            budget.check(line)?;
            parsed.regex_lines.push((parsed.domains.len(), line));
        }
        if group == 'p' && parsed.ports.len() >= MAX_PORTS {
            return Err(ParseError::new(Failure::TooLarge, line, "port rules"));
        }
        normalized_bytes += normalized.len();
        seen.insert(key);
        match group {
            'i' => parsed.ips.push(normalized),
            'p' => parsed.ports.push(normalized),
            'n' => parsed.networks.push(normalized),
            _ => parsed.domains.push(normalized),
        }
    }
    budget.check(line)?;
    if !payload {
        return Err(ParseError::new(Failure::Format, 0, "payload"));
    }
    if parsed.count() == 0 {
        return Err(ParseError::new(Failure::Empty, 0, ""));
    }
    Ok(parsed)
}

// Go supports \Q...\E literal quoting; adapt only the budget validator, preserving the stored pattern.
fn rust_regex_pattern(pattern: &str) -> String {
    let mut output = String::with_capacity(pattern.len());
    let mut chars = pattern.chars().peekable();
    let mut quoted = false;
    while let Some(c) = chars.next() {
        if quoted {
            if c == '\\' && chars.peek() == Some(&'E') {
                chars.next();
                quoted = false;
            } else {
                output.push_str(&regex::escape(&c.to_string()));
            }
        } else if c == '\\' {
            match chars.next() {
                Some('Q') => quoted = true,
                Some(next) => {
                    output.push('\\');
                    output.push(next);
                }
                None => output.push('\\'),
            }
        } else {
            output.push(c);
        }
    }
    output
}

fn domain(value: &str) -> Option<String> {
    let ascii = idna::domain_to_ascii(value.strip_suffix('.').unwrap_or(value))
        .ok()?
        .to_ascii_lowercase();
    if ascii.is_empty() || ascii.len() > 253 {
        return None;
    }
    for label in ascii.split('.') {
        if label.is_empty()
            || label.len() > 63
            || label.starts_with('-')
            || label.ends_with('-')
            || !label
                .bytes()
                .all(|c| c.is_ascii_alphanumeric() || c == b'_' || c == b'-')
        {
            return None;
        }
    }
    Some(ascii)
}

fn cidr(value: &str, ipv6: bool) -> Option<String> {
    let mut pieces = value.split('/');
    let address = pieces.next()?;
    let prefix = pieces.next();
    if pieces.next().is_some() {
        return None;
    }
    let bits = if ipv6 { 128u16 } else { 32u16 };
    let prefix = match prefix {
        Some(p) => p.parse::<u16>().ok()?,
        None => bits,
    };
    if prefix > bits {
        return None;
    }
    let normalized = if ipv6 {
        address.parse::<Ipv6Addr>().ok()?;
        address.to_ascii_lowercase()
    } else {
        let octets = address
            .split('.')
            .map(|part| {
                if part.is_empty() || !part.bytes().all(|b| b.is_ascii_digit()) {
                    return None;
                }
                part.parse::<u8>().ok()
            })
            .collect::<Option<Vec<_>>>()?;
        if octets.len() != 4 {
            return None;
        }
        octets
            .iter()
            .map(u8::to_string)
            .collect::<Vec<_>>()
            .join(".")
    };
    Some(format!("{normalized}/{prefix}"))
}

fn port(value: &str) -> Option<String> {
    let mut pieces = value.split('-');
    let first = pieces.next()?.parse::<u16>().ok()?;
    let last = pieces.next().map(str::parse::<u16>).transpose().ok()?;
    if first == 0 || pieces.next().is_some() || last.is_some_and(|last| last < first) {
        return None;
    }
    Some(match last {
        Some(last) => format!("{first}-{last}"),
        None => first.to_string(),
    })
}

fn strip_comment(value: &str) -> &str {
    let mut quote = None;
    let mut chars = value.char_indices().peekable();
    let mut previous_whitespace = true;
    while let Some((index, c)) = chars.next() {
        if quote == Some('"') && c == '\\' {
            chars.next();
            continue;
        }
        if quote == Some('\'') && c == '\'' && chars.peek().is_some_and(|(_, c)| *c == '\'') {
            chars.next();
            continue;
        }
        if quote == Some(c) {
            quote = None;
        } else if quote.is_none() && (c == '\'' || c == '"') && previous_whitespace {
            quote = Some(c);
        } else if quote.is_none() && c == '#' && previous_whitespace {
            return &value[..index];
        }
        previous_whitespace = c.is_whitespace();
    }
    value
}

fn scalar(value: &str) -> Result<String, ()> {
    let first = value.chars().next().ok_or(())?;
    if first == '\'' {
        let inner = value
            .strip_prefix('\'')
            .and_then(|v| v.strip_suffix('\''))
            .ok_or(())?;
        let body = inner.replace("''", "");
        if body.contains('\'') {
            return Err(());
        }
        return Ok(inner.replace("''", "'"));
    }
    if first != '"' {
        if "[]{}!&*|>@`".contains(first) || value.contains(": ") {
            return Err(());
        }
        return Ok(value.to_owned());
    }
    let inner = value
        .strip_prefix('"')
        .and_then(|v| v.strip_suffix('"'))
        .ok_or(())?;
    let mut output = String::with_capacity(inner.len());
    let mut chars = inner.chars();
    while let Some(c) = chars.next() {
        if c == '"' {
            return Err(());
        }
        if c != '\\' {
            output.push(c);
            continue;
        }
        let escaped = chars.next().ok_or(())?;
        match escaped {
            '\\' | '"' | '/' | ' ' => output.push(escaped),
            '0' => output.push('\0'),
            'a' => output.push('\u{7}'),
            'b' => output.push('\u{8}'),
            't' => output.push('\t'),
            'n' => output.push('\n'),
            'v' => output.push('\u{b}'),
            'f' => output.push('\u{c}'),
            'r' => output.push('\r'),
            'e' => output.push('\u{1b}'),
            'N' => output.push('\u{85}'),
            '_' => output.push('\u{a0}'),
            'L' => output.push('\u{2028}'),
            'P' => output.push('\u{2029}'),
            'x' | 'u' | 'U' => {
                let size = match escaped {
                    'x' => 2,
                    'u' => 4,
                    _ => 8,
                };
                let digits: String = chars.by_ref().take(size).collect();
                if digits.len() != size {
                    return Err(());
                }
                let code = u32::from_str_radix(&digits, 16).map_err(|_| ())?;
                output.push(char::from_u32(code).ok_or(())?);
            }
            _ => return Err(()),
        }
    }
    Ok(output)
}

pub struct LimitedWriter {
    pub bytes: Vec<u8>,
}

impl io::Write for LimitedWriter {
    fn write(&mut self, buffer: &[u8]) -> io::Result<usize> {
        if self.bytes.len() + buffer.len() > MAX_JSON_BYTES {
            return Err(io::Error::other("result budget"));
        }
        self.bytes.extend_from_slice(buffer);
        Ok(buffer.len())
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

#[cfg(test)]
mod tests;
