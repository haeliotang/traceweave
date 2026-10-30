//! Length-prefixed framing with per-frame and per-session limits.

use crate::{MAX_FRAME_BYTES, MAX_SESSION_BYTES, MAX_SESSION_FRAMES};

#[derive(Debug, PartialEq, Eq)]
pub enum FrameError {
    TooLarge(usize),
    SessionBytesExceeded,
    SessionFramesExceeded,
    Truncated,
}

/// Encode a payload as a 4-byte big-endian length followed by the bytes.
pub fn encode(payload: &[u8]) -> Result<Vec<u8>, FrameError> {
    if payload.len() > MAX_FRAME_BYTES {
        return Err(FrameError::TooLarge(payload.len()));
    }
    let mut out = Vec::with_capacity(4 + payload.len());
    out.extend_from_slice(&(payload.len() as u32).to_be_bytes());
    out.extend_from_slice(payload);
    Ok(out)
}

/// Read one frame header. Returns the declared payload length.
pub fn declared_len(header: &[u8]) -> Result<usize, FrameError> {
    if header.len() < 4 {
        return Err(FrameError::Truncated);
    }
    let len = u32::from_be_bytes([header[0], header[1], header[2], header[3]]) as usize;
    if len > MAX_FRAME_BYTES {
        return Err(FrameError::TooLarge(len));
    }
    Ok(len)
}

/// Tracks the bytes and frames consumed by one session.
#[derive(Debug, Default)]
pub struct SessionBudget {
    bytes: usize,
    frames: usize,
}

impl SessionBudget {
    pub fn new() -> Self {
        Self::default()
    }

    /// Account for one frame; rejects before the receiver allocates or applies it.
    pub fn accept(&mut self, payload_len: usize) -> Result<(), FrameError> {
        if payload_len > MAX_FRAME_BYTES {
            return Err(FrameError::TooLarge(payload_len));
        }
        if self.frames >= MAX_SESSION_FRAMES {
            return Err(FrameError::SessionFramesExceeded);
        }
        if self.bytes.saturating_add(payload_len) > MAX_SESSION_BYTES {
            return Err(FrameError::SessionBytesExceeded);
        }
        self.frames += 1;
        self.bytes += payload_len;
        Ok(())
    }

    pub fn bytes(&self) -> usize {
        self.bytes
    }

    pub fn frames(&self) -> usize {
        self.frames
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_a_small_frame() {
        let encoded = encode(b"hello").unwrap();
        assert_eq!(declared_len(&encoded).unwrap(), 5);
        assert_eq!(&encoded[4..], b"hello");
    }

    #[test]
    fn rejects_oversized_frame_at_encode_and_decode() {
        let big = vec![0u8; MAX_FRAME_BYTES + 1];
        assert_eq!(encode(&big), Err(FrameError::TooLarge(MAX_FRAME_BYTES + 1)));
        let mut header = (MAX_FRAME_BYTES as u32 + 1).to_be_bytes().to_vec();
        header.extend_from_slice(&[0; 8]);
        assert_eq!(declared_len(&header), Err(FrameError::TooLarge(MAX_FRAME_BYTES + 1)));
    }

    #[test]
    fn rejects_a_truncated_header() {
        assert_eq!(declared_len(&[0, 1]), Err(FrameError::Truncated));
    }

    #[test]
    fn enforces_the_session_byte_budget() {
        let mut budget = SessionBudget::new();
        for _ in 0..8 {
            budget.accept(MAX_FRAME_BYTES).unwrap();
        }
        assert_eq!(budget.bytes(), MAX_SESSION_BYTES);
        assert_eq!(budget.accept(1), Err(FrameError::SessionBytesExceeded));
    }

    #[test]
    fn enforces_the_session_frame_budget() {
        let mut budget = SessionBudget::new();
        for _ in 0..MAX_SESSION_FRAMES {
            budget.accept(0).unwrap();
        }
        assert_eq!(budget.accept(0), Err(FrameError::SessionFramesExceeded));
    }
}
