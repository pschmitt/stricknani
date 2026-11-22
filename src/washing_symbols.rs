use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct WashingSymbols {
    pub(crate) temperature: Option<WashTemperature>,
    pub(crate) drying: Option<Drying>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum WashTemperature {
    #[serde(rename = "hand_wash")]
    HandWash,
    #[serde(rename = "30")]
    Thirty,
    #[serde(rename = "40")]
    Forty,
    #[serde(rename = "60")]
    Sixty,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Drying {
    Flat,
    Line,
    NoTumble,
}
