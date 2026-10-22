//! Independent case advertisements, observed on product 0x2035.
//! macOS 26A434 confirms subtype 6 and battery fields; initial key matching
//! still uses capture heuristics because the case need not resolve to the bud IRK.
//! Structural checks are not cryptographic authentication. Never use this for
//! control commands. Initial identification heuristics are separate from parsing.
use aes::{
    Aes128,
    cipher::{Array, BlockCipherDecrypt, KeyInit},
};
fn decrypted_case(data: &[u8], key: &[u8; 16]) -> Option<[u8; 16]> {
    let payload = super::apple_advertisement::pairing_payload(data, 6)?;
    let mut block = Array::from(<[u8; 16]>::try_from(payload.get(1..17)?).ok()?);
    Aes128::new(&Array::from(*key)).decrypt_block(&mut block);
    (block[..2] == [0x35, 0x20]).then(|| block.into())
}
/// Initial unbound-advertiser matching retains conservative capture heuristics.
/// These zero fields are not Apple format requirements or authentication.
pub fn decode_components(data: &[u8], key: &[u8; 16]) -> Option<[u8; 3]> {
    let block = decrypted_case(data, key)?;
    if block[10..12] != [0; 2]
        || block[15] != 0
        || block[3..6].iter().any(|v| *v != 0xff && v & 0x7f > 100)
    {
        return None;
    }
    Some([block[4], block[5], block[3]])
}
/// Once this advertiser has been identified, use Apple's field semantics.
pub(crate) fn decode_for_bound_identity(data: &[u8], key: &[u8; 16]) -> Option<[u8; 3]> {
    let block = decrypted_case(data, key)?;
    let normalize = |byte: u8| if byte == 255 { 255 } else { (byte & 128) | (byte & 127).min(100) };
    Some([normalize(block[4]), normalize(block[5]), normalize(block[3])])
}
#[derive(Default)]
pub(crate) struct AdvertiserDecoder {
    binding: Option<(String, [u8; 16])>,
}
impl AdvertiserDecoder {
    pub(crate) fn is_bound(&self) -> bool { self.binding.is_some() }
    pub(crate) fn decode(&mut self, data: &[u8], keys: &[(String, [u8; 16])]) -> Option<(String, [u8; 3])> {
        if let Some((pair, key)) = &self.binding
            && let Some(parts) = decode_for_bound_identity(data, key)
        {
            return Some((pair.clone(), parts));
        }
        let (pair, parts) = match_components(data, keys)?;
        let key = keys.iter().find(|(id, _)| id == pair)?.1;
        self.binding = Some((pair.to_owned(), key));
        Some((pair.to_owned(), parts))
    }
}
pub fn match_components<'a>(
    data: &[u8],
    keys: &'a [(String, [u8; 16])],
) -> Option<(&'a str, [u8; 3])> {
    let mut matches = keys
        .iter()
        .filter_map(|(id, key)| decode_components(data, key).map(|v| (id.as_str(), v)));
    let result = matches.next()?;
    if matches.next().is_some() {
        None
    } else {
        Some(result)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use aes::cipher::BlockCipherEncrypt;
    const KEY: [u8; 16] = [0x42; 16]; // Synthetic; not an accessory key.
    fn packet(plain: [u8; 16]) -> Vec<u8> {
        let mut block = Array::from(plain);
        Aes128::new(&Array::from(KEY)).encrypt_block(&mut block);
        let mut data = vec![7, 17, 6];
        data.extend_from_slice(&block);
        data
    }
    fn plain(level: u8) -> [u8; 16] {
        [
            0x35, 0x20, 1, level, 255, 255, 90, 134, 0, 0, 0, 0, 1, 2, 0, 0,
        ]
    }
    #[test]
    fn empty_case_reports_percentage_and_charging_bit() {
        for level in [0, 73, 79, 100] {
            for charging in [false, true] {
                let value = level | if charging { 128 } else { 0 };
                assert_eq!(
                    decode_components(&packet(plain(value)), &KEY),
                    Some([255, 255, value])
                );
            }
        }
    }

    #[test]
    fn opaque_status_change_does_not_retain_old_charging_bit() {
        for opaque in 0..=u8::MAX {
            let mut p = plain(128 + 100);
            p[2] = opaque;
            assert_eq!(decode_components(&packet(p), &KEY), Some([255, 255, 228]));
            p[3] = 100;
            assert_eq!(decode_components(&packet(p), &KEY), Some([255, 255, 100]));
        }
    }

    #[test]
    fn independent_case_exposes_both_earbud_slots_and_unknowns() {
        let mut p = plain(81);
        p[4] = 128 + 65;
        p[5] = 255;
        assert_eq!(decode_components(&packet(p), &KEY), Some([193, 255, 81]));
        p[4] = 255;
        p[5] = 128 + 70;
        assert_eq!(decode_components(&packet(p), &KEY), Some([255, 198, 81]));
    }
    #[test]
    fn observed_flag_one_preserves_charging_right_bud() {
        let mut p = plain(51);
        p[2] = 0x0b;
        p[14] = 1;
        for level in [30, 31, 32, 33, 40] {
            p[5] = 128 + level;
            assert_eq!(
                decode_components(&packet(p), &KEY),
                Some([255, 128 + level, 51])
            );
        }
        p[5] = 40;
        assert_eq!(decode_components(&packet(p), &KEY), Some([255, 40, 51]));
        for flag in 2..=u8::MAX {
            p[14] = flag;
            assert_eq!(decode_components(&packet(p), &KEY), Some([255, 40, 51]));
        }
    }

    #[test]
    fn boundaries_unknown_and_invalid() {
        for level in [0, 100, 128, 228, 255] {
            assert!(decode_components(&packet(plain(level)), &KEY).is_some());
        }
        assert_eq!(
            decode_components(&packet(plain(255)), &KEY),
            Some([255, 255, 255])
        );
        for level in [101, 127, 229, 254] {
            assert!(decode_components(&packet(plain(level)), &KEY).is_none());
        }
        for (offset, value) in [
            (0, 0),
            (1, 0),
            (4, 101),
            (5, 254),
            (11, 1),
            (15, 1),
        ] {
            let mut p = plain(76);
            p[offset] = value;
            assert!(decode_components(&packet(p), &KEY).is_none());
        }
        let p = packet(plain(76));
        for len in 0..19 {
            assert!(decode_components(&p[..len], &KEY).is_none());
        }
        let mut extended = p.clone();
        extended.push(0);
        assert!(decode_components(&extended, &KEY).is_some());
        extended[1] = 18;
        assert!(decode_components(&extended[..19], &KEY).is_none());
        let mut wrong = p.clone();
        wrong[2] = 1;
        assert!(decode_components(&wrong, &KEY).is_none());
        assert!(decode_components(&p, &[0; 16]).is_none());
    }
    #[test]
    fn first_case_report_allows_real_firmware_and_obc_fields() {
        for value in 0..=255 {
            let mut p = plain(128 + 98);
            p[8] = value;
            p[9] = value;
            p[14] = value;
            assert_eq!(decode_components(&packet(p), &KEY), Some([255, 255, 226]));
        }
    }
    #[test]
    fn bound_case_accepts_firmware_time_and_uninterpreted_tail_bytes() {
        for offset in 8..16 {
            for value in 0..=255 {
                let mut p = plain(128 + 98);
                p[offset] = value;
                assert_eq!(decode_for_bound_identity(&packet(p), &KEY), Some([255, 255, 226]));
            }
        }
        let mut p = plain(229);
        p[4] = 127;
        assert_eq!(decode_for_bound_identity(&packet(p), &KEY), Some([100, 255, 228]));
        p[3] = 255;
        assert_eq!(decode_for_bound_identity(&packet(p), &KEY), Some([100, 255, 255]));
    }
    #[test]
    fn case_record_can_follow_another_apple_record() {
        let mut p = vec![16, 1, 0];
        let mut case = packet(plain(98));
        case[1] |= 0xe0;
        p.extend(case);
        p.extend([15, 0]);
        assert_eq!(decode_components(&p, &KEY), Some([255, 255, 98]));
    }
    #[test]
    fn relaxed_fields_require_prior_unambiguous_advertiser_binding() {
        let keys = vec![("pair-A".into(), KEY)];
        let mut decoder = AdvertiserDecoder::default();
        let mut changed = plain(128 + 98);
        changed[8] = 7;
        changed[9] = 42;
        changed[10] = 99;
        changed[14] = 200;
        let changed = packet(changed);
        assert!(decoder.decode(&changed, &keys).is_none());
        assert!(!decoder.is_bound());
        let ambiguous = vec![("pair-A".into(), KEY), ("pair-B".into(), KEY)];
        assert!(decoder.decode(&packet(plain(98)), &ambiguous).is_none());
        assert!(!decoder.is_bound());
        assert_eq!(decoder.decode(&packet(plain(98)), &keys), Some(("pair-A".into(), [255, 255, 98])));
        assert!(decoder.is_bound());
        assert_eq!(decoder.decode(&changed, &keys), Some(("pair-A".into(), [255, 255, 226])));
        let mut wrong_model = plain(98);
        wrong_model[0] = 0;
        assert!(decoder.decode(&packet(wrong_model), &keys).is_none());
    }
    #[test]
    fn unique_identity_required() {
        let p = packet(plain(76));
        assert!(match_components(&p, &[]).is_none());
        let one = vec![("pair-A".into(), KEY), ("pair-B".into(), [0; 16])];
        assert_eq!(match_components(&p, &one).unwrap().0, "pair-A");
        let ambiguous = vec![("pair-A".into(), KEY), ("pair-B".into(), KEY)];
        assert!(match_components(&p, &ambiguous).is_none());
    }
}
