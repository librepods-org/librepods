//! AirPods Pro 3 RTBuddy heart-rate decoding.
//!
//! A faithful Rust port of `RtBuddyHeartRateDecoder` (Android/Kotlin, PR #702).
//! Reassembles RTBuddy SensorDataWX frames across chunks and extracts the
//! verified HEARTRATE(19) payload. The protocol checks that stop control/startup
//! frames from being read as BPM are kept deliberately intact: live log type,
//! service 19, exact 18-byte payload, a known status trailer, and the validated
//! physiological range. Length-delimited wrappers are traversed only to the same
//! bounded depth as the observed firmware variants.
//!
//! Unlike the Kotlin decoder (which owns the whole recv stream and re-dispatches
//! non-HR "passthrough" packets), this runs *alongside* the daemon's existing
//! recv loop: `feed` is handed a copy of each chunk and returns only decoded BPM
//! samples. Battery/ANC/ear parsing still runs on the same bytes independently.

// ---- constants (mirror the Kotlin companion object) ----

const AACP_RTBUDDY_HEADER_LENGTH: usize = 12;
const MAX_RTBUDDY_PAYLOAD_LENGTH: usize = 16 * 1024;

/// Firmware has emitted live records with both log types.
const LIVE_SENSOR_DATA_LOG_TYPES: [u64; 2] = [1, 3];

/// Different exact status trailers depending on whether one or both earbuds
/// participate in the session.
const KNOWN_HEART_RATE_STATUS_TAILS: [[u8; 3]; 4] = [
    [0x10, 0x00, 0x00],
    [0x20, 0x00, 0x00],
    [0x20, 0x02, 0x80],
    [0x20, 0x82, 0x80],
];

const FIELD_LOG_TYPE: u32 = 2;
const FIELD_SERVICE: u32 = 1;
const FIELD_COMMAND_PAYLOAD: u32 = 3;
// Services that carry HR REPORTS, per the maintainer + thibaup (Discord 2026-08-13):
// 8* firmware sets AND reports on 19 (HEARTRATE); 9* firmware sets on 84
// (HEARTRATE_COMMAND) but the readings arrive on 20. Accept all three so we catch the
// data whichever service the firmware reports on.
const HEART_RATE_REPORT_SERVICES: [u64; 3] = [84, 20, 19];
const HEART_RATE_PAYLOAD_LENGTH: usize = 18;
const HEART_RATE_BPM_OFFSET: usize = 1;
const HEART_RATE_STATUS_TAIL_OFFSET: usize = 15;
const MIN_BPM: u8 = 30;
const MAX_BPM: u8 = 220;

const SENSOR_DATA_COMMAND_FIELDS: [u32; 5] = [5, 7, 8, 9, 12];
const MAX_COMMAND_ENVELOPE_DEPTH: u32 = 3;
const MAX_PAYLOAD_WRAPPER_DEPTH: u32 = 3;
const MAX_COMMANDS_PER_FRAME: usize = 16;
const MAX_PAYLOAD_CANDIDATES_PER_COMMAND: usize = 12;
const MAX_PROTO_MESSAGE_LENGTH: usize = MAX_RTBUDDY_PAYLOAD_LENGTH;
const MAX_PROTO_FIELDS: usize = 96;
const MAX_PROTO_FIELD_NUMBER: u32 = 4_096;

const WIRE_VARINT: u8 = 0;
const WIRE_FIXED64: u8 = 1;
const WIRE_LENGTH_DELIMITED: u8 = 2;
const WIRE_FIXED32: u8 = 5;

/// type=0x0004, service=0x0004, opcode=0x0017, descriptor=0x00100000 — the
/// AACP/RTBuddy SensorDataWX frame prefix (first 10 bytes of the 12-byte header).
const RTBUDDY_FRAME_PREFIX: [u8; 10] =
    [0x04, 0x00, 0x04, 0x00, 0x17, 0x00, 0x00, 0x00, 0x10, 0x00];

// ---- proto scaffolding ----

struct ProtoField {
    number: u32,
    wire_type: u8,
    varint_value: Option<u64>,
    value_start: usize,
    value_end: usize,
}

struct ProtoMessage {
    fields: Vec<ProtoField>,
}

impl ProtoMessage {
    fn first_varint(&self, field_number: u32) -> Option<u64> {
        self.fields
            .iter()
            .find(|f| f.number == field_number && f.wire_type == WIRE_VARINT)
            .and_then(|f| f.varint_value)
    }
}

struct VarintRead {
    value: u64,
    next_index: usize,
}

/// One HEARTRATE(19) command: its candidate payload byte-slices.
struct HeartRateCommand {
    payload_candidates: Vec<Vec<u8>>,
}

/// Reassembles RTBuddy frames and extracts verified HEARTRATE samples.
pub struct RtBuddyHeartRateDecoder {
    carry: Vec<u8>,
}

impl Default for RtBuddyHeartRateDecoder {
    fn default() -> Self {
        Self::new()
    }
}

impl RtBuddyHeartRateDecoder {
    pub fn new() -> Self {
        RtBuddyHeartRateDecoder { carry: Vec::new() }
    }

    /// Drop any partial-frame carry (call on connect/disconnect).
    pub fn reset(&mut self) {
        self.carry.clear();
    }

    /// Feed one received chunk; returns any newly-decoded BPM samples.
    pub fn feed(&mut self, chunk: &[u8]) -> Vec<u16> {
        let mut samples = Vec::new();
        if chunk.is_empty() {
            return samples;
        }

        let data: Vec<u8> = if self.carry.is_empty() {
            chunk.to_vec()
        } else {
            let mut d = std::mem::take(&mut self.carry);
            d.extend_from_slice(chunk);
            d
        };
        self.carry.clear();

        let mut cursor = 0usize;
        while cursor < data.len() {
            let frame_offset = match index_of_prefix(&data, &RTBUDDY_FRAME_PREFIX, cursor) {
                Some(off) => off,
                None => {
                    // No further frame starts; keep a partial prefix at the tail
                    // so it can complete with the next chunk.
                    let suffix_length =
                        longest_suffix_matching_prefix(&data, &RTBUDDY_FRAME_PREFIX, cursor);
                    if suffix_length > 0 {
                        let passthrough_end = data.len() - suffix_length;
                        self.carry = data[passthrough_end..].to_vec();
                    }
                    break;
                }
            };

            if data.len() - frame_offset < AACP_RTBUDDY_HEADER_LENGTH {
                self.carry = data[frame_offset..].to_vec();
                break;
            }

            let payload_length = read_le16(&data, frame_offset + 10);
            if payload_length > MAX_RTBUDDY_PAYLOAD_LENGTH {
                // The declared length is untrusted — drop the rest.
                break;
            }

            let frame_length = AACP_RTBUDDY_HEADER_LENGTH + payload_length;
            if data.len() - frame_offset < frame_length {
                self.carry = data[frame_offset..].to_vec();
                break;
            }

            let frame = &data[frame_offset..frame_offset + frame_length];
            if let Some(bpm) = classify_frame(frame) {
                samples.push(bpm);
            }
            cursor = frame_offset + frame_length;
        }

        samples
    }
}

/// Classify a reassembled frame; returns a validated BPM if (and only if) it is a
/// live HEARTRATE record with an accepted payload.
fn classify_frame(frame: &[u8]) -> Option<u16> {
    let top_level = parse_proto_message(frame, AACP_RTBUDDY_HEADER_LENGTH, frame.len())?;

    let log_type = top_level.first_varint(FIELD_LOG_TYPE).map(|v| v as i64).unwrap_or(-1);
    let mut commands: Vec<HeartRateCommand> = Vec::new();

    for field in &top_level.fields {
        if field.wire_type == WIRE_LENGTH_DELIMITED
            && SENSOR_DATA_COMMAND_FIELDS.contains(&field.number)
            && commands.len() < MAX_COMMANDS_PER_FRAME
        {
            collect_heart_rate_commands(frame, field.value_start, field.value_end, 0, &mut commands);
        }
    }

    if commands.is_empty() {
        return None;
    }
    if !LIVE_SENSOR_DATA_LOG_TYPES.contains(&(log_type as u64)) {
        // Related but the wrong log type — reject (control/startup frame).
        return None;
    }

    let payloads: Vec<&Vec<u8>> = commands
        .iter()
        .flat_map(|c| c.payload_candidates.iter())
        .collect();

    payloads
        .iter()
        .find(|p| is_valid_heart_rate_payload(p))
        .map(|p| p[HEART_RATE_BPM_OFFSET] as u16)
}

fn collect_heart_rate_commands(
    data: &[u8],
    start: usize,
    end: usize,
    depth: u32,
    commands: &mut Vec<HeartRateCommand>,
) {
    if depth > MAX_COMMAND_ENVELOPE_DEPTH || commands.len() >= MAX_COMMANDS_PER_FRAME {
        return;
    }
    let message = match parse_proto_message(data, start, end) {
        Some(m) => m,
        None => return,
    };
    let service = message.first_varint(FIELD_SERVICE);

    if service.is_some_and(|s| HEART_RATE_REPORT_SERVICES.contains(&s)) {
        let mut payloads: Vec<Vec<u8>> = Vec::new();
        for field in &message.fields {
            if field.number == FIELD_COMMAND_PAYLOAD && field.wire_type == WIRE_LENGTH_DELIMITED {
                collect_payload_candidates(data, field.value_start, field.value_end, 0, &mut payloads);
            }
        }
        commands.push(HeartRateCommand { payload_candidates: payloads });
    }

    if depth == MAX_COMMAND_ENVELOPE_DEPTH {
        return;
    }
    for field in &message.fields {
        if field.wire_type == WIRE_LENGTH_DELIMITED && commands.len() < MAX_COMMANDS_PER_FRAME {
            collect_heart_rate_commands(data, field.value_start, field.value_end, depth + 1, commands);
        }
    }
}

fn collect_payload_candidates(
    data: &[u8],
    start: usize,
    end: usize,
    depth: u32,
    candidates: &mut Vec<Vec<u8>>,
) {
    if candidates.len() >= MAX_PAYLOAD_CANDIDATES_PER_COMMAND {
        return;
    }

    let direct = data[start..end].to_vec();
    if !candidates.iter().any(|c| *c == direct) {
        candidates.push(direct);
    }
    if depth >= MAX_PAYLOAD_WRAPPER_DEPTH {
        return;
    }

    let wrapper = match parse_proto_message(data, start, end) {
        Some(m) => m,
        None => return,
    };
    for field in &wrapper.fields {
        if field.wire_type == WIRE_LENGTH_DELIMITED
            && candidates.len() < MAX_PAYLOAD_CANDIDATES_PER_COMMAND
        {
            collect_payload_candidates(data, field.value_start, field.value_end, depth + 1, candidates);
        }
    }
}

fn is_valid_heart_rate_payload(payload: &[u8]) -> bool {
    if payload.len() != HEART_RATE_PAYLOAD_LENGTH {
        return false;
    }
    let bpm = payload[HEART_RATE_BPM_OFFSET];
    if bpm < MIN_BPM || bpm > MAX_BPM {
        return false;
    }
    KNOWN_HEART_RATE_STATUS_TAILS.iter().any(|tail| {
        tail.iter()
            .enumerate()
            .all(|(i, b)| payload[HEART_RATE_STATUS_TAIL_OFFSET + i] == *b)
    })
}

fn parse_proto_message(data: &[u8], start: usize, end: usize) -> Option<ProtoMessage> {
    if end < start || end > data.len() || end - start > MAX_PROTO_MESSAGE_LENGTH {
        return None;
    }

    let mut fields: Vec<ProtoField> = Vec::new();
    let mut index = start;
    while index < end {
        if fields.len() >= MAX_PROTO_FIELDS {
            return None;
        }
        let key = read_varint(data, index, end)?;
        index = key.next_index;

        let field_number = (key.value >> 3) as u32;
        if field_number == 0 || field_number as u64 > MAX_PROTO_FIELD_NUMBER as u64 {
            return None;
        }
        let wire_type = (key.value & 0x07) as u8;

        match wire_type {
            WIRE_VARINT => {
                let value = read_varint(data, index, end)?;
                fields.push(ProtoField {
                    number: field_number,
                    wire_type,
                    varint_value: Some(value.value),
                    value_start: index,
                    value_end: value.next_index,
                });
                index = value.next_index;
            }
            WIRE_LENGTH_DELIMITED => {
                let length = read_varint(data, index, end)?;
                if length.value > usize::MAX as u64 {
                    return None;
                }
                let value_end = length.next_index.checked_add(length.value as usize)?;
                if value_end < length.next_index || value_end > end {
                    return None;
                }
                fields.push(ProtoField {
                    number: field_number,
                    wire_type,
                    varint_value: None,
                    value_start: length.next_index,
                    value_end,
                });
                index = value_end;
            }
            WIRE_FIXED64 => {
                if end - index < 8 {
                    return None;
                }
                fields.push(ProtoField {
                    number: field_number,
                    wire_type,
                    varint_value: None,
                    value_start: index,
                    value_end: index + 8,
                });
                index += 8;
            }
            WIRE_FIXED32 => {
                if end - index < 4 {
                    return None;
                }
                fields.push(ProtoField {
                    number: field_number,
                    wire_type,
                    varint_value: None,
                    value_start: index,
                    value_end: index + 4,
                });
                index += 4;
            }
            _ => return None,
        }
    }
    Some(ProtoMessage { fields })
}

fn read_varint(data: &[u8], start: usize, end: usize) -> Option<VarintRead> {
    let mut value: u64 = 0;
    let mut shift: u32 = 0;
    let mut index = start;

    while index < end && shift < 64 {
        let byte = data[index] as u64;
        index += 1;
        value |= (byte & 0x7F) << shift;
        if byte & 0x80 == 0 {
            return Some(VarintRead { value, next_index: index });
        }
        shift += 7;
    }
    None
}

// ---- byte-array helpers (mirror the Kotlin extension functions) ----

fn read_le16(data: &[u8], offset: usize) -> usize {
    (data[offset] as usize) | ((data[offset + 1] as usize) << 8)
}

/// Diagnostic: does this chunk contain an RTBuddy live-frame prefix? Used only
/// for logging whether the AirPods are actually streaming HR frames.
pub fn contains_frame_prefix(data: &[u8]) -> bool {
    index_of_prefix(data, &RTBUDDY_FRAME_PREFIX, 0).is_some()
}

fn index_of_prefix(data: &[u8], prefix: &[u8], start_index: usize) -> Option<usize> {
    if prefix.is_empty() {
        return Some(start_index.min(data.len()));
    }
    if data.len() < prefix.len() {
        return None;
    }
    let last_start = data.len() - prefix.len();
    if start_index > last_start {
        return None;
    }
    for start in start_index..=last_start {
        if prefix.iter().enumerate().all(|(i, b)| data[start + i] == *b) {
            return Some(start);
        }
    }
    None
}

/// The longest suffix of `data[start_index..]` that matches a *prefix* of
/// `prefix` (i.e. a truncated frame header at the tail we should carry forward).
fn longest_suffix_matching_prefix(data: &[u8], prefix: &[u8], start_index: usize) -> usize {
    let start_index = start_index.min(data.len());
    let available = data.len() - start_index;
    let max_length = available.min(prefix.len().saturating_sub(1));
    for length in (1..=max_length).rev() {
        let start = data.len() - length;
        if (0..length).all(|i| data[start + i] == prefix[i]) {
            return length;
        }
    }
    0
}
