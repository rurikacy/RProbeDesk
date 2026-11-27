use std::error::Error;

pub type Result<T> = std::result::Result<T, Box<dyn Error>>;
const MAX_DATA: u32 = 65_536;

pub fn header(tag: [u8; 4], size: u32) -> i64 {
    ((u32::from_be_bytes(tag) as i64) << 32) | size.swap_bytes() as i64
}

pub fn parse_header(header: i64) -> ([u8; 4], u32) {
    (
        ((header >> 32) as u32).to_be_bytes(),
        (header as u32).swap_bytes(),
    )
}

pub trait Channel {
    fn packet(&mut self, tag: [u8; 4], value: u32, payload: bool) -> Result<()>;
    fn reply(&mut self) -> Result<([u8; 4], u32)>;
    fn next_chunk(&mut self) -> Result<i32>;
    fn receive(&mut self, count: u32) -> Result<()>;
    fn failure(&mut self, count: u32) -> Result<String>;
}

fn request(io: &mut impl Channel, tag: [u8; 4], length: i32) -> Result<()> {
    if !(1..=1024).contains(&length) {
        return Err("ADB Sync path must be 1–1024 bytes".into());
    }
    io.packet(tag, length as u32, true)
}

fn unexpected(io: &mut impl Channel, tag: [u8; 4], size: u32) -> Result<()> {
    if &tag == b"FAIL" && size <= MAX_DATA {
        return Err(io.failure(size)?.into());
    }
    Err(format!(
        "Invalid ADB Sync response {:?} ({size})",
        String::from_utf8_lossy(&tag)
    )
    .into())
}

pub fn receive(io: &mut impl Channel, length: i32) -> Result<()> {
    request(io, *b"RECV", length)?;
    loop {
        let (tag, size) = io.reply()?;
        match &tag {
            b"DONE" => return Ok(()),
            b"DATA" if size <= MAX_DATA => io.receive(size)?,
            _ => return unexpected(io, tag, size),
        }
    }
}

pub fn send(io: &mut impl Channel, length: i32, expected: i64, timestamp: u32) -> Result<()> {
    request(io, *b"SEND", length)?;
    let mut total = 0i64;
    loop {
        match io.next_chunk()? {
            -1 => break,
            0 => continue,
            count @ 1..=65536 => {
                io.packet(*b"DATA", count as u32, true)?;
                total += i64::from(count);
            }
            _ => return Err("Invalid source chunk size".into()),
        }
    }
    if expected >= 0 && expected != total {
        return Err("本机源文件长度已变化".into());
    }
    io.packet(*b"DONE", timestamp, false)?;
    let (tag, size) = io.reply()?;
    if &tag == b"OKAY" && size == 0 {
        Ok(())
    } else {
        unexpected(io, tag, size)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn wire_layout() {
        let wire = *b"DATA\x01\x02\x03\x04";
        assert_eq!(header(*b"DATA", 0x04030201).to_be_bytes(), wire);
        assert_eq!(
            parse_header(i64::from_be_bytes(wire)),
            (*b"DATA", 0x04030201)
        );
    }
}
