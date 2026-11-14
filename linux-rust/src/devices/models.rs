// Known AirPods models and their capabilities.
// Keep in sync with docs/airpods-features.md and the Android list
// (android/app/src/main/java/me/kavishdevar/librepods/data/AirPods.kt).

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Capability {
    ListeningMode,
    AdaptiveAudio,
    ConversationAwareness,
    AdaptiveVolume,
    StemConfig,
    HeadGestures,
    SleepDetection,
    LoudSoundReduction,
    HearingAid,
    HearingProtection,
    HeartRate,
    VolumeSwipe,
}

#[derive(Debug)]
pub struct AirPodsModel {
    pub name: &'static str,
    pub model_numbers: &'static [&'static str],
    pub capabilities: &'static [Capability],
}

impl AirPodsModel {
    pub fn has(&self, capability: Capability) -> bool {
        self.capabilities.contains(&capability)
    }

    pub fn from_model_number(model_number: &str) -> Option<&'static AirPodsModel> {
        MODELS
            .iter()
            .find(|model| model.model_numbers.contains(&model_number))
    }
}

/// Whether the model supports the capability, `None` if the model is unknown.
/// Callers should treat `None` like supported, so new models don't lose features.
pub fn supports(model: Option<&AirPodsModel>, capability: Capability) -> Option<bool> {
    model.map(|model| model.has(capability))
}

use Capability::*;

pub const MODELS: &[AirPodsModel] = &[
    AirPodsModel {
        name: "AirPods",
        model_numbers: &["A1523", "A1722"],
        capabilities: &[],
    },
    AirPodsModel {
        name: "AirPods Gen. 2",
        model_numbers: &["A2032", "A2031"],
        capabilities: &[],
    },
    AirPodsModel {
        name: "AirPods Gen. 3",
        model_numbers: &["A2565", "A2564"],
        capabilities: &[HeadGestures],
    },
    AirPodsModel {
        name: "AirPods 4",
        model_numbers: &["A3053", "A3050", "A3054"],
        capabilities: &[HeadGestures, SleepDetection, AdaptiveVolume],
    },
    AirPodsModel {
        name: "AirPods 4 ANC",
        model_numbers: &["A3056", "A3055", "A3057"],
        capabilities: &[
            ListeningMode,
            AdaptiveAudio,
            ConversationAwareness,
            AdaptiveVolume,
            StemConfig,
            HeadGestures,
            SleepDetection,
        ],
    },
    AirPodsModel {
        name: "AirPods 5",
        model_numbers: &["A3531", "A3532", "A3533"],
        capabilities: &[
            ListeningMode,
            AdaptiveAudio,
            ConversationAwareness,
            AdaptiveVolume,
            StemConfig,
            HeadGestures,
            SleepDetection,
        ],
    },
    AirPodsModel {
        name: "AirPods 5 (Wireless Charging Case)",
        model_numbers: &["A3439", "A3440", "A3441"],
        capabilities: &[
            ListeningMode,
            AdaptiveAudio,
            ConversationAwareness,
            AdaptiveVolume,
            StemConfig,
            HeadGestures,
            SleepDetection,
            VolumeSwipe,
        ],
    },
    AirPodsModel {
        name: "AirPods Pro",
        model_numbers: &["A2084", "A2083"],
        capabilities: &[ListeningMode],
    },
    AirPodsModel {
        name: "AirPods Pro 2 (Lightning)",
        model_numbers: &["A2931", "A2699", "A2698"],
        capabilities: &[
            ListeningMode,
            AdaptiveAudio,
            ConversationAwareness,
            AdaptiveVolume,
            StemConfig,
            HeadGestures,
            SleepDetection,
            LoudSoundReduction,
            HearingAid,
            VolumeSwipe,
        ],
    },
    AirPodsModel {
        name: "AirPods Pro 2 (USB-C)",
        model_numbers: &["A3047", "A3048", "A3049"],
        capabilities: &[
            ListeningMode,
            AdaptiveAudio,
            ConversationAwareness,
            AdaptiveVolume,
            StemConfig,
            HeadGestures,
            SleepDetection,
            LoudSoundReduction,
            HearingAid,
            VolumeSwipe,
        ],
    },
    AirPodsModel {
        name: "AirPods Pro 3",
        model_numbers: &["A3063", "A3064", "A3065"],
        capabilities: &[
            ListeningMode,
            AdaptiveAudio,
            ConversationAwareness,
            AdaptiveVolume,
            StemConfig,
            HeadGestures,
            SleepDetection,
            LoudSoundReduction,
            HearingAid,
            HearingProtection,
            HeartRate,
            VolumeSwipe,
        ],
    },
    AirPodsModel {
        name: "AirPods Max (Lightning)",
        model_numbers: &["A2096"],
        capabilities: &[ListeningMode],
    },
    AirPodsModel {
        name: "AirPods Max (USB-C)",
        model_numbers: &["A3184"],
        capabilities: &[ListeningMode],
    },
    AirPodsModel {
        name: "AirPods Max 2",
        model_numbers: &["A3454"],
        capabilities: &[
            ListeningMode,
            AdaptiveAudio,
            ConversationAwareness,
            AdaptiveVolume,
            LoudSoundReduction,
        ],
    },
];

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn model_numbers_are_unique() {
        let mut seen = std::collections::HashSet::new();
        for model in MODELS {
            for number in model.model_numbers {
                assert!(seen.insert(number), "duplicate model number {}", number);
            }
        }
    }

    #[test]
    fn lookup() {
        let pro1 = AirPodsModel::from_model_number("A2084").unwrap();
        assert_eq!(pro1.name, "AirPods Pro");
        assert!(!pro1.has(AdaptiveAudio));
        assert!(AirPodsModel::from_model_number("A3048").unwrap().has(AdaptiveAudio));
        assert!(AirPodsModel::from_model_number("A0000").is_none());
    }

    #[test]
    fn unknown_model_is_none() {
        assert_eq!(supports(None, AdaptiveAudio), None);
    }
}
