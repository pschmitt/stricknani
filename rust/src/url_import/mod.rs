//! Higher-quality URL imports: structured data first, site extractors, then optional AI.
//!
//! * [`charset`] decodes fetched HTML.
//! * [`facts`] collects metadata, schema.org nodes, images and rendered text.
//! * [`extract`] maps facts to yarn and pattern drafts with field provenance.
//! * [`ravelry`] reads the Ravelry API when credentials are configured.
//! * [`ai_merge`] fills gaps with a validated, grounded model answer.

pub mod ai_merge;
pub mod charset;
pub mod extract;
pub mod facts;
pub mod ravelry;
pub mod text;

pub use extract::{Extraction, extract_html};

/// What the user wants to create from a URL.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ImportTarget {
    #[default]
    Project,
    Yarn,
}
