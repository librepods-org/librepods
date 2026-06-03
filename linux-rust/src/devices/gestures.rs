//! Head-gesture detection from the AirPods head-tracking acceleration stream.
//!
//! Operates on the horizontal and vertical acceleration values (offsets 51/53
//! of the 0x17 sensor packet). A nod ("yes") is a vertical oscillation; a shake
//! ("no") is a horizontal oscillation. The detector looks for several large
//! threshold crossings in one axis within a short window, with that axis clearly
//! dominating the other, then enforces a cooldown to avoid repeat triggers.

use std::collections::VecDeque;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Gesture {
    /// Nodding up/down — typically mapped to play/pause ("yes").
    Nod,
    /// Shaking left/right — typically mapped to next track ("no").
    Shake,
}

const WINDOW: usize = 25; // ~0.8s at the ~30 Hz sample rate
const CROSS_THRESHOLD: f32 = 300.0; // magnitude a swing must exceed to count
const MIN_CROSSINGS: u32 = 3; // distinct large swings needed for an oscillation
const DOMINANCE: f32 = 1.8; // active axis peak-to-peak must beat the other by this
const COOLDOWN_SAMPLES: u32 = 30; // ignore input briefly after a detection

pub struct GestureDetector {
    vertical: VecDeque<f32>,
    horizontal: VecDeque<f32>,
    cooldown: u32,
}

impl GestureDetector {
    pub fn new() -> Self {
        Self {
            vertical: VecDeque::with_capacity(WINDOW),
            horizontal: VecDeque::with_capacity(WINDOW),
            cooldown: 0,
        }
    }

    pub fn push(&mut self, horizontal: i16, vertical: i16) -> Option<Gesture> {
        push_capped(&mut self.horizontal, horizontal as f32);
        push_capped(&mut self.vertical, vertical as f32);

        if self.cooldown > 0 {
            self.cooldown -= 1;
            return None;
        }
        if self.vertical.len() < WINDOW {
            return None;
        }

        let (v_cross, v_pp) = analyze(&self.vertical);
        let (h_cross, h_pp) = analyze(&self.horizontal);

        // Nod: vertical oscillation dominating the horizontal axis.
        if v_cross >= MIN_CROSSINGS && v_pp > h_pp * DOMINANCE {
            self.reset();
            return Some(Gesture::Nod);
        }
        // Shake: horizontal oscillation dominating the vertical axis.
        if h_cross >= MIN_CROSSINGS && h_pp > v_pp * DOMINANCE {
            self.reset();
            return Some(Gesture::Shake);
        }
        None
    }

    fn reset(&mut self) {
        self.vertical.clear();
        self.horizontal.clear();
        self.cooldown = COOLDOWN_SAMPLES;
    }
}

fn push_capped(buf: &mut VecDeque<f32>, v: f32) {
    if buf.len() == WINDOW {
        buf.pop_front();
    }
    buf.push_back(v);
}

/// Returns (number of large threshold crossings, peak-to-peak amplitude).
fn analyze(buf: &VecDeque<f32>) -> (u32, f32) {
    let mut min = f32::MAX;
    let mut max = f32::MIN;
    // Count sign changes of the signal where the swing exceeds the threshold:
    // an oscillation alternates above +T and below -T.
    let mut crossings = 0u32;
    let mut last_sign = 0i8;
    for &v in buf {
        if v < min {
            min = v;
        }
        if v > max {
            max = v;
        }
        let sign = if v > CROSS_THRESHOLD {
            1
        } else if v < -CROSS_THRESHOLD {
            -1
        } else {
            0
        };
        if sign != 0 && sign != last_sign {
            crossings += 1;
            last_sign = sign;
        }
    }
    (crossings, max - min)
}
