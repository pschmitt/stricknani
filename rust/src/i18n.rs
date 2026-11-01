//! Runtime translations for the Rust server-rendered UI.

use std::collections::HashMap;
use std::sync::LazyLock;

use crate::Language;

static CATALOGS: LazyLock<HashMap<String, HashMap<String, String>>> = LazyLock::new(|| {
    serde_json::from_str(include_str!("../locales/messages.json"))
        .expect("generated Rust i18n catalog is valid JSON")
});

/// Return the catalog translation, using the Rust label's German text as a fallback.
pub(crate) fn text(
    language: Language,
    msgid: &'static str,
    german_fallback: &'static str,
) -> &'static str {
    match language {
        Language::English => msgid,
        Language::German => CATALOGS
            .get("de")
            .and_then(|catalog| catalog.get(msgid))
            .map(String::as_str)
            .unwrap_or(german_fallback),
    }
}
