//! Apple manufacturer-data framing recovered from macOS build 26A434.
#[derive(Clone, Copy, Debug)]
pub(crate) struct Record<'a> {
    pub kind: u8,
    pub payload: &'a [u8],
}
pub(crate) struct Records<'a>(&'a [u8]);
pub(crate) fn records(data: &[u8]) -> Records<'_> {
    Records(data)
}
impl<'a> Iterator for Records<'a> {
    type Item = Record<'a>;
    fn next(&mut self) -> Option<Self::Item> {
        let header = self.0.get(..2)?;
        let length = usize::from(header[1] & 0x1f);
        let Some(payload) = self.0.get(2..2 + length) else {
            self.0 = &[];
            return None;
        };
        let record = Record { kind: header[0], payload };
        self.0 = &self.0[2 + length..];
        Some(record)
    }
}
pub(crate) fn pairing_payload(data: &[u8], subtype: u8) -> Option<&[u8]> {
    records(data).find(|r| r.kind == 7 && r.payload.first() == Some(&subtype)).map(|r| r.payload)
}
pub(crate) fn legacy_packet(data: &[u8]) -> Option<[u8; 27]> {
    let payload = pairing_payload(data, 1)?.get(..25)?;
    let mut result = [0; 27];
    result[..2].copy_from_slice(&[7, 25]);
    result[2..].copy_from_slice(payload);
    Some(result)
}
/// Input must already be associated with a paired device, e.g. by its IRK.
pub(crate) fn airpods5_pairing_batteries(data: &[u8]) -> Option<[Option<u8>; 3]> {
    let record = records(data).find(|r| r.kind == 7 && matches!(r.payload.first(), Some(4 | 7)))?;
    let payload = record.payload;
    let product = u16::from_le_bytes(payload.get(1..3)?.try_into().ok()?);
    if !matches!(product, 0x2030 | 0x2032 | 0x2036 | 0x2037) { return None; }
    let value = |index: usize| payload.get(index).copied().filter(|v| *v != 255)
        .map(|v| (v & 128) | (v & 127).min(100));
    Some([value(5), value(6), value(4)])
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn framing_accepts_length_flags_and_concatenated_records() {
        for flags in (0..=224).step_by(32) {
            let data = [16, 1, 0, 7, flags | 4, 7, 0x30, 0x20, 0, 15, 0];
            let result: Vec<_> = records(&data).collect();
            assert_eq!(result.len(), 3);
            assert_eq!(result[1].payload, [7, 0x30, 0x20, 0]);
        }
        assert!(records(&[7, 17, 6, 0]).next().is_none());
    }
    #[test]
    fn legacy_offsets_ignore_other_records_and_upper_length_bits() {
        let mut data = vec![16, 1, 0, 7, 0xf9, 1];
        data.extend(1..25);
        data.extend([15, 0]);
        let legacy = legacy_packet(&data).unwrap();
        assert_eq!(legacy[..3], [7, 25, 1]);
        assert_eq!(legacy[26], 24);
        data.truncate(10);
        assert!(legacy_packet(&data).is_none());
    }
    #[test]
    fn pairing_battery_slots_charge_and_unknown_are_per_component() {
        for subtype in [4, 7] {
            for product in [0x2030u16, 0x2032, 0x2036, 0x2037] {
                let [low, high] = product.to_le_bytes();
                let mut data = [7, 7, subtype, low, high, 0, 128 + 96, 255, 101];
                assert_eq!(airpods5_pairing_batteries(&data), Some([None, Some(100), Some(224)]));
                data[8] = 128 + 80;
                assert_eq!(airpods5_pairing_batteries(&data), Some([None, Some(208), Some(224)]));
            }
        }
        assert!(airpods5_pairing_batteries(&[7, 7, 7, 0, 0, 0, 96, 99, 98]).is_none());
    }
}
