use crate::{Failure, ParseError, Parsed};

pub const MAX_PACKET: usize = 16 * 1024 * 1024;

pub struct Packet(pub Vec<u8>);

impl Packet {
    pub fn new(kind: u32) -> Self {
        let mut packet = Self(Vec::with_capacity(4096));
        packet.number(0x46525031);
        packet.number(kind);
        packet
    }

    pub fn number(&mut self, value: u32) {
        self.0.extend_from_slice(&value.to_be_bytes());
    }

    pub fn string(&mut self, value: &str) -> Result<(), ParseError> {
        if self.0.len() + 4 + value.len() > MAX_PACKET {
            return Err(ParseError::new(Failure::TooLarge, 0, "result"));
        }
        self.number(value.len() as u32);
        self.0.extend_from_slice(value.as_bytes());
        Ok(())
    }

    pub fn strings(&mut self, values: &[String]) -> Result<(), ParseError> {
        self.number(values.len() as u32);
        for value in values {
            self.string(value)?;
        }
        Ok(())
    }
}

pub fn error(error: ParseError) -> Vec<u8> {
    let mut packet = Packet::new(0);
    packet.string(failure_name(error.failure)).unwrap();
    packet.number(error.line as u32);
    packet.string(&error.detail).unwrap();
    packet.0
}

pub fn failure_name(failure: Failure) -> &'static str {
    match failure {
        Failure::Format => "FORMAT",
        Failure::Unsupported => "UNSUPPORTED",
        Failure::Value => "VALUE",
        Failure::Empty => "EMPTY",
        Failure::TooLarge => "TOO_LARGE",
        Failure::Read => "READ",
        Failure::Timeout => "TIMEOUT",
        Failure::Cancelled => "CANCELLED",
        Failure::Busy => "BUSY",
    }
}

pub fn rules(parsed: Parsed) -> Result<Vec<u8>, ParseError> {
    let mut packet = Packet::new(1);
    for group in [
        &parsed.domains,
        &parsed.ips,
        &parsed.ports,
        &parsed.networks,
    ] {
        packet.strings(group)?;
    }
    packet.number(parsed.regex_lines.len() as u32);
    for (index, line) in parsed.regex_lines {
        packet.number(index as u32);
        packet.number(line as u32);
    }
    Ok(packet.0)
}

#[cfg(test)]
pub struct Reader<'a> {
    bytes: &'a [u8],
    offset: usize,
}

#[cfg(test)]
impl<'a> Reader<'a> {
    pub fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, offset: 0 }
    }
    pub fn number(&mut self) -> Result<usize, ParseError> {
        let end = self.offset.checked_add(4).ok_or_else(invalid)?;
        let data = self.bytes.get(self.offset..end).ok_or_else(invalid)?;
        self.offset = end;
        Ok(u32::from_be_bytes(data.try_into().unwrap()) as usize)
    }
    pub fn string(&mut self) -> Result<&'a str, ParseError> {
        let len = self.number()?;
        let end = self.offset.checked_add(len).ok_or_else(invalid)?;
        let data = self.bytes.get(self.offset..end).ok_or_else(invalid)?;
        self.offset = end;
        std::str::from_utf8(data).map_err(|_| invalid())
    }
    pub fn finish(&self) -> Result<(), ParseError> {
        if self.offset == self.bytes.len() {
            Ok(())
        } else {
            Err(invalid())
        }
    }
}

#[cfg(test)]
fn invalid() -> ParseError {
    ParseError::new(Failure::Format, 0, "packet")
}
