use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub(crate) struct WashingSymbols {
    pub(crate) temperature: Option<WashTemperature>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) hand_wash_only: Option<bool>,
    pub(crate) drying: Option<Drying>,
}

impl WashingSymbols {
    pub(crate) fn preserve_legacy_hand_wash(mut self, current: Option<&Self>) -> Self {
        if self.hand_wash_only.is_none() && self.temperature.is_none() {
            self.hand_wash_only = current.and_then(|saved| saved.hand_wash_only);
        }
        if self.hand_wash_only == Some(true) {
            self.temperature = None;
        }
        self
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub(crate) enum WashTemperature {
    #[serde(rename = "30")]
    Thirty,
    #[serde(rename = "40")]
    Forty,
    #[serde(rename = "60")]
    Sixty,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub(crate) enum Drying {
    Flat,
    Line,
    NoTumble,
}
