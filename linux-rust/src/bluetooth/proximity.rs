//! Decoding of the AirPods' BLE "proximity pairing" adverts (Apple manufacturer data, company
//! 0x004C). Everything here is pure and panic-free on short or garbage input; the layouts follow
//! the Android app's BLEManager.

use crate::utils::ah;
use aes::Aes128;
use aes::cipher::{Array, BlockCipherDecrypt, KeyInit};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Level {
    pub percent: u8,
    pub charging: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AccessoryState {
    Disconnected,
    Idle,
    Music,
    Call,
    Ringing,
    HangingUp,
    Other(u8),
}

impl AccessoryState {
    fn from_byte(byte: u8) -> Self {
        match byte {
            0x00 => Self::Disconnected,
            0x04 => Self::Idle,
            0x05 => Self::Music,
            0x06 => Self::Call,
            0x07 => Self::Ringing,
            0x09 => Self::HangingUp,
            other => Self::Other(other),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BudsAdvert {
    pub model_id: u16,
    pub primary_left: bool,
    pub left_in_ear: bool,
    pub right_in_ear: bool,
    pub lid_open: bool,
    pub color: u8,
    pub state: AccessoryState,
    pub left: Option<Level>,
    pub right: Option<Level>,
    pub case: Option<Level>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CaseAdvert {
    pub case: Option<Level>,
    pub left: Option<Level>,
    pub right: Option<Level>,
}

fn decrypt(key: &[u8; 16], data: &[u8; 16]) -> [u8; 16] {
    let cipher = Aes128::new(&Array::from(*key));
    let mut block = Array::from(*data);
    cipher.decrypt_block(&mut block);
    block.into()
}

/// 0xFF means "not present"; otherwise bit 7 is charging and the low 7 bits the percentage.
fn level(byte: u8) -> Option<Level> {
    (byte != 0xFF).then_some(Level {
        percent: byte & 0x7F,
        charging: byte & 0x80 != 0,
    })
}

/// Whether a resolvable private address was generated from `irk` (Bluetooth `ah()` check).
pub fn resolve_rpa(address: &str, irk: &[u8; 16]) -> bool {
    let Ok(octets) = address
        .split(':')
        .map(|s| u8::from_str_radix(s, 16))
        .collect::<Result<Vec<u8>, _>>()
    else {
        return false;
    };
    let [a, b, c, d, e, f] = octets[..] else {
        return false;
    };
    // The address string is most-significant octet first: prand is the top half, hash the bottom.
    ah(irk, &[c, b, a]) == [f, e, d]
}

/// Decodes the buds' own advert (`07 19 01 …`, the last 16 bytes encrypted with ENC_KEY).
pub fn parse_buds_advert(apple_data: &[u8], enc_key: &[u8; 16]) -> Option<BudsAdvert> {
    if apple_data.len() < 27 {
        return None;
    }
    let [0x07, _, _, model_hi, model_lo, status, _, _, lid, color, state, ..] = *apple_data else {
        return None;
    };
    let encrypted: [u8; 16] = apple_data.get(apple_data.len() - 16..)?.try_into().ok()?;
    let plaintext = decrypt(enc_key, &encrypted);

    let primary_left = (status >> 5) & 0x01 == 1;
    let this_in_case = (status >> 6) & 0x01 == 1;
    let xor_factor = primary_left ^ this_in_case;
    let left_in_ear = status & if xor_factor { 0x08 } else { 0x02 } != 0;
    let right_in_ear = status & if xor_factor { 0x02 } else { 0x08 } != 0;

    let (left_index, right_index) = if primary_left { (1, 2) } else { (2, 1) };
    let left = level(plaintext[left_index]);
    let right = level(plaintext[right_index]);
    let case = level(plaintext[3]);

    let any_charging = [left, right, case]
        .iter()
        .any(|l| l.is_some_and(|l| l.charging));
    let lid_open = (lid >> 3) & 0x01 == 0 && (!left_in_ear || !right_in_ear) && any_charging;

    Some(BudsAdvert {
        model_id: u16::from_be_bytes([model_hi, model_lo]),
        primary_left,
        left_in_ear,
        right_in_ear,
        lid_open,
        color,
        state: AccessoryState::from_byte(state),
        left,
        right,
        case,
    })
}

fn is_valid_case_plaintext(plaintext: &[u8; 16]) -> bool {
    plaintext[1] == 0x20
        && plaintext[15] == 0
        && plaintext[3..=5]
            .iter()
            .all(|&b| b == 0xFF || (b & 0x7F) <= 100)
}

/// Decodes the charging case's own advert: a type-0x07 record whose payload is `06` followed by
/// 16 encrypted bytes. The case isn't address-resolvable, so every known ENC_KEY is tried.
pub fn parse_case_advert(apple_data: &[u8], enc_keys: &[[u8; 16]]) -> Option<CaseAdvert> {
    let mut offset = 0;
    while let [kind, length, ..] = *apple_data.get(offset..)? {
        let length = (length & 0x1F) as usize;
        let Some(payload) = apple_data.get(offset + 2..offset + 2 + length) else {
            break;
        };
        if kind == 0x07
            && length >= 17
            && let [0x06, rest @ ..] = payload
            && let Some(encrypted) = rest.get(..16)
            && let Ok(encrypted) = <[u8; 16]>::try_from(encrypted)
        {
            for key in enc_keys {
                let plaintext = decrypt(key, &encrypted);
                if is_valid_case_plaintext(&plaintext) {
                    return Some(CaseAdvert {
                        case: level(plaintext[3]),
                        left: level(plaintext[4]),
                        right: level(plaintext[5]),
                    });
                }
            }
        }
        offset += 2 + length;
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use aes::Aes128;
    use aes::cipher::{Array, BlockCipherEncrypt, KeyInit};

    const IRK: [u8; 16] = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16];
    const OTHER_IRK: [u8; 16] = [0xAA; 16];
    const ENC: [u8; 16] = [
        0x10, 0x21, 0x32, 0x43, 0x54, 0x65, 0x76, 0x87, 0x98, 0xA9, 0xBA, 0xCB, 0xDC, 0xED, 0xFE,
        0x0F,
    ];
    const WRONG: [u8; 16] = [0x55; 16];

    fn encrypt(key: &[u8; 16], block: &[u8; 16]) -> [u8; 16] {
        let cipher = Aes128::new(&Array::from(*key));
        let mut b = Array::from(*block);
        cipher.encrypt_block(&mut b);
        b.into()
    }

    fn rpa_for(irk: &[u8; 16]) -> String {
        let prand = [0x12, 0x34, 0x56 | 0x40];
        let hash = crate::utils::ah(irk, &prand);
        [prand[2], prand[1], prand[0], hash[2], hash[1], hash[0]]
            .iter()
            .map(|b| format!("{:02X}", b))
            .collect::<Vec<_>>()
            .join(":")
    }

    fn buds_data(status: u8, lid: u8, state: u8, plaintext: [u8; 16]) -> Vec<u8> {
        let mut data = vec![0x07, 0x19, 0x01, 0x0E, 0x20, status, 0x00, 0x00, lid, 0x01, state];
        data.extend_from_slice(&encrypt(&ENC, &plaintext));
        data
    }

    fn plaintext(b1: u8, b2: u8, b3: u8) -> [u8; 16] {
        let mut pt = [0u8; 16];
        pt[1] = b1;
        pt[2] = b2;
        pt[3] = b3;
        pt
    }

    fn case_data(key: &[u8; 16]) -> Vec<u8> {
        let mut pt = [0u8; 16];
        pt[1] = 0x20;
        pt[3] = 0x80 | 90;
        pt[4] = 50;
        pt[5] = 0xFF;
        let mut data = vec![0x07, 0x11, 0x06];
        data.extend_from_slice(&encrypt(key, &pt));
        data
    }

    #[test]
    fn own_rpa_resolves() {
        assert!(resolve_rpa(&rpa_for(&IRK), &IRK));
    }

    #[test]
    fn foreign_rpa_does_not_resolve() {
        assert!(!resolve_rpa(&rpa_for(&IRK), &OTHER_IRK));
    }

    #[test]
    fn malformed_address_is_false() {
        for address in ["zz:00:00:00:00:00", "", "8C:7A"] {
            assert!(!resolve_rpa(address, &IRK), "{address}");
        }
    }

    #[test]
    fn buds_advert_primary_left() {
        let advert =
            parse_buds_advert(&buds_data(0x20 | 0x08, 0x08, 0x00, plaintext(0x80 | 55, 60, 0xFF)), &ENC)
                .unwrap();
        assert_eq!(advert.model_id, 0x0E20);
        assert!(advert.primary_left);
        assert_eq!(advert.left, Some(Level { percent: 55, charging: true }));
        assert_eq!(advert.right, Some(Level { percent: 60, charging: false }));
        assert_eq!(advert.case, None);
        assert_eq!(advert.state, AccessoryState::Disconnected);
        assert!(advert.left_in_ear);
        assert!(!advert.right_in_ear);
    }

    #[test]
    fn buds_advert_primary_right_swaps_batteries() {
        let advert =
            parse_buds_advert(&buds_data(0x00, 0x08, 0x04, plaintext(40, 70, 0xFF)), &ENC).unwrap();
        assert!(!advert.primary_left);
        assert_eq!(advert.left.map(|l| l.percent), Some(70));
        assert_eq!(advert.right.map(|l| l.percent), Some(40));
        assert_eq!(advert.state, AccessoryState::Idle);
    }

    #[test]
    fn buds_advert_lid_open_needs_charging_bud() {
        let charging = parse_buds_advert(
            &buds_data(0x20 | 0x40, 0x00, 0x00, plaintext(0x80 | 30, 0x80 | 40, 80)),
            &ENC,
        )
        .unwrap();
        assert!(charging.lid_open);
        let not_charging =
            parse_buds_advert(&buds_data(0x20 | 0x40, 0x00, 0x00, plaintext(30, 40, 80)), &ENC)
                .unwrap();
        assert!(!not_charging.lid_open);
    }

    #[test]
    fn case_advert_tries_each_key() {
        let advert = parse_case_advert(&case_data(&ENC), &[WRONG, ENC]).unwrap();
        assert_eq!(advert.case, Some(Level { percent: 90, charging: true }));
        assert_eq!(advert.left, Some(Level { percent: 50, charging: false }));
        assert_eq!(advert.right, None);
    }

    #[test]
    fn case_advert_rejects_invalid_plaintext() {
        assert_eq!(parse_case_advert(&case_data(&ENC), &[WRONG]), None);
    }

    #[test]
    fn no_decoder_panics_on_truncation() {
        let buds = buds_data(0x20, 0x08, 0x00, plaintext(1, 2, 3));
        let case = case_data(&ENC);
        for fixture in [&buds, &case] {
            for n in 0..=fixture.len() {
                let _ = parse_buds_advert(&fixture[..n], &ENC);
                let _ = parse_case_advert(&fixture[..n], &[ENC]);
            }
        }
    }
}
