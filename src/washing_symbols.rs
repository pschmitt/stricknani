use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub(crate) struct WashingSymbols {
    pub(crate) temperature: Option<WashTemperature>,
    pub(crate) drying: Option<Drying>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub(crate) enum WashTemperature {
    #[serde(rename = "hand_wash")]
    HandWash,
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
