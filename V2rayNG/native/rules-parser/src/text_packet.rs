use crate::{ParseError, Parsed};

pub struct TextPacket {
    pub numbers: Vec<i32>,
    pub text: Vec<u16>,
}

impl TextPacket {
    fn string(&mut self, value: &str) {
        let start = self.text.len();
        self.text.extend(value.encode_utf16());
        self.numbers.push((self.text.len() - start) as i32);
    }
}

pub fn encode(result: Result<Parsed, ParseError>) -> TextPacket {
    let mut packet = TextPacket {
        numbers: vec![0x46525431],
        text: Vec::new(),
    };
    match result {
        Ok(parsed) => {
            packet
                .numbers
                .reserve(parsed.count() + parsed.regex_lines.len() * 2 + 6);
            packet.text.reserve(
                parsed
                    .domains
                    .iter()
                    .chain(&parsed.ips)
                    .chain(&parsed.ports)
                    .chain(&parsed.networks)
                    .map(String::len)
                    .sum(),
            );
            packet.numbers.push(1);
            for group in [parsed.domains, parsed.ips, parsed.ports, parsed.networks] {
                packet.numbers.push(group.len() as i32);
                for value in group {
                    packet.string(&value);
                }
            }
            packet.numbers.push(parsed.regex_lines.len() as i32);
            for (index, line) in parsed.regex_lines {
                packet.numbers.extend([index as i32, line as i32]);
            }
        }
        Err(error) => {
            packet.numbers.push(0);
            packet.string(crate::packet::failure_name(error.failure));
            packet.numbers.push(error.line as i32);
            packet.string(&error.detail);
        }
    }
    packet
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Failure;
    #[test]
    fn lengths_are_utf16_units_and_errors_keep_source_lines() {
        let packet = encode(Ok(Parsed {
            domains: vec!["keyword:\u{1f600}".into()],
            ..Default::default()
        }));
        assert_eq!(packet.numbers, [0x46525431, 1, 1, 10, 0, 0, 0, 0]);
        assert_eq!(
            String::from_utf16(&packet.text).unwrap(),
            "keyword:\u{1f600}"
        );
        let packet = encode(Err(ParseError::new(Failure::Value, 7, "bad")));
        assert_eq!(packet.numbers, [0x46525431, 0, 5, 7, 3]);
        assert_eq!(String::from_utf16(&packet.text).unwrap(), "VALUEbad");
    }
}
