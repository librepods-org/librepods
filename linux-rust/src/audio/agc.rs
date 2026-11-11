//! AGC (Automatic Gain Control) for audio processing.

pub struct Agc {
    envelope: f32,
    gain: f32,
}

impl Agc {
    const TARGET: f32 = 0.25; // ≈ -12 dBFS
    const MAX_GAIN: f32 = 8.0; // +18 dB
    const ATTACK: f32 = 0.02;
    const RELEASE: f32 = 0.001;

    pub fn new() -> Self {
        Self {
            envelope: 0.0,
            gain: 1.0,
        }
    }

    pub fn process(&mut self, samples: &mut [i16]) {
        for s in samples {
            let x = *s as f32 / 32768.0;
            let level = x.abs();

            if level > self.envelope {
                self.envelope += (level - self.envelope) * Self::ATTACK;
            } else {
                self.envelope += (level - self.envelope) * Self::RELEASE;
            }

            // Target gain
            let desired = (Self::TARGET / self.envelope.max(1e-4)).min(Self::MAX_GAIN);

            // Smooth gain
            self.gain += (desired - self.gain) * 0.01;

            // Apply gain
            let mut y = x * self.gain;

            // Soft limiter
            y = y.tanh();

            *s = (y * 32767.0) as i16;
        }
    }
}
