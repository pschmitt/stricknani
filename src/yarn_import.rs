//! Yarns found by a project import: clean names, product-page enrichment and stash matching.
//!
//! A pattern page lists the yarns it needs. For each of them the review step offers three
//! choices (use a yarn from the stash, create a new one, skip it), so the server prepares:
//!
//! * [`normalize_yarn`]: strips publisher boilerplate such as "von Garnstudio" or "(gehört zur
//!   Garngruppe B)" from the name into brand and notes.
//! * [`finalize_project_yarns`]: one clean, de-duplicated list from every source (markup, rules,
//!   the model, the page's own links).
//! * [`enrich_project_yarns`]: runs the yarn-from-link import on each yarn's product page, a few
//!   at a time with a timeout, and falls back to the page data when it fails.
//! * [`match_stash`]: pre-selects the best matching yarn of the user's own stash.

use std::{collections::HashSet, future::Future, sync::Arc, time::Duration};

use serde::Serialize;
use serde_json::{Value, json};
use sqlx::SqlitePool;
use tokio::task::JoinSet;
use url::Url;

use crate::{
    ai::AiClient,
    import_document::{
        FieldSource, ImportDocumentDraft, PageKind, YarnDetails, YarnImportDraft, safe_web_url,
    },
    import_service,
    url_import::{ImportTarget, ai_merge, extract::PAGE_LINKS_EXTRA, facts::PageLinkFact, text},
};

/// Yarns kept per import.
pub const MAX_YARNS: usize = 8;
/// Product pages opened per import.
pub const MAX_ENRICHED: usize = 4;
/// Budget for one product page (fetch, parse and optional AI step). The pages run in parallel.
pub const ENRICH_TIMEOUT: Duration = Duration::from_secs(30);
/// Warning code when at least one product page could not be read.
pub const ENRICH_WARNING: &str = "yarn_enrichment_failed";

// ------------------------------------------------------------------ links in the draft

/// The page's links as the internal extra the worker sends back with the draft.
pub fn links_to_extra(links: &[PageLinkFact]) -> Value {
    Value::Array(
        links
            .iter()
            .take(120)
            .map(|link| {
                json!({
                    "url": link.url,
                    "text": link.text,
                    "image": link.image_url,
                    "alt": link.image_alt,
                })
            })
            .collect(),
    )
}

/// Links stored by [`links_to_extra`].
pub fn links_from_draft(draft: &ImportDocumentDraft) -> Vec<PageLinkFact> {
    let Some(Value::Array(items)) = draft.project.extras.get(PAGE_LINKS_EXTRA) else {
        return Vec::new();
    };
    let text_of = |item: &Value, key: &str| {
        item.get(key)
            .and_then(Value::as_str)
            .map(str::to_owned)
            .filter(|value| !value.is_empty())
    };
    items
        .iter()
        .filter_map(|item| {
            Some(PageLinkFact {
                url: text_of(item, "url")?,
                text: text_of(item, "text").unwrap_or_default(),
                image_url: text_of(item, "image"),
                image_alt: text_of(item, "alt"),
            })
        })
        .collect()
}

/// Remove the internal links extra; clients must never see it.
pub fn strip_internal_extras(draft: &mut ImportDocumentDraft) {
    draft.project.extras.remove(PAGE_LINKS_EXTRA);
}

// ------------------------------------------------------------------ name normalisation

const KNOWN_BRANDS: [(&str, &str); 3] = [
    ("garnstudio", "Garnstudio"),
    ("drops design", "DROPS Design"),
    ("drops", "DROPS"),
];

fn collapse(value: &str) -> String {
    value.split_whitespace().collect::<Vec<_>>().join(" ")
}

fn nonempty(value: String) -> Option<String> {
    (!value.is_empty()).then_some(value)
}

/// Take a trailing `von|by|from <known brand>` (optionally after a dash or bar) off a name.
fn strip_brand_suffix(name: &str) -> (String, Option<&'static str>) {
    let lower = name.to_lowercase();
    for (needle, brand) in KNOWN_BRANDS {
        for joiner in [" von ", " by ", " from ", " - ", " – ", " | ", " – von "] {
            let suffix = format!("{joiner}{needle}");
            if lower.ends_with(&suffix) {
                let keep = name.chars().count() - suffix.chars().count();
                let head: String = name.chars().take(keep).collect();
                return (head, Some(brand));
            }
        }
    }
    (name.to_owned(), None)
}

/// Remove "(gehört zur Garngruppe B)" and similar; returns the remaining text and the note.
fn strip_group(name: &str) -> (String, Option<String>) {
    let pattern = regex::Regex::new(
        r"(?i)[\(\[]?\s*(?:(?:gehört\s+zur|gehört\s+zu|belongs\s+to|in)\s+)?(garn\s*-?\s*gruppe|yarn\s+group)\s+([A-F])\b\s*[\)\]]?",
    )
    .expect("static regex");
    match pattern.captures(name) {
        Some(captures) => {
            let german = captures[1].to_lowercase().starts_with("garn");
            let note = format!(
                "{} {}",
                if german { "Garngruppe" } else { "Yarn group" },
                captures[2].to_uppercase()
            );
            let range = captures.get(0).map(|found| found.range()).unwrap_or(0..0);
            let mut cleaned = String::new();
            cleaned.push_str(&name[..range.start]);
            cleaned.push_str(&name[range.end..]);
            (cleaned, Some(note))
        }
        None => (name.to_owned(), None),
    }
}

fn has_lowercase(value: &str) -> bool {
    value.chars().any(char::is_lowercase)
}

/// "DROPS MERINO EXTRA FINE" becomes "DROPS Merino Extra Fine".
fn title_case_shouting(value: &str) -> String {
    if has_lowercase(value) || value.chars().filter(|c| c.is_alphabetic()).count() < 4 {
        return value.to_owned();
    }
    value
        .split(' ')
        .map(|word| {
            if word == "DROPS" || word.chars().any(|c| c.is_ascii_digit()) {
                return word.to_owned();
            }
            word.split('-')
                .map(|part| {
                    let mut chars = part.chars();
                    match chars.next() {
                        Some(first) => {
                            first.to_uppercase().collect::<String>()
                                + &chars.as_str().to_lowercase()
                        }
                        None => String::new(),
                    }
                })
                .collect::<Vec<_>>()
                .join("-")
        })
        .collect::<Vec<_>>()
        .join(" ")
}

/// A yarn name split into its parts.
#[derive(Debug, Default, Eq, PartialEq)]
pub struct CleanYarnName {
    pub brand: Option<String>,
    pub name: String,
    /// Yarn group or similar remark moved out of the name.
    pub note: Option<String>,
}

/// Clean a scraped yarn name: publisher suffixes and group remarks leave the name, the brand
/// is filled from them when it is not known yet, and shouting capitals are tamed.
pub fn clean_yarn_name(raw_name: &str, brand: Option<&str>) -> CleanYarnName {
    let mut name = collapse(raw_name);
    let (without_group, note) = strip_group(&name);
    name = collapse(&without_group);
    let (without_brand, suffix_brand) = strip_brand_suffix(&name);
    name = collapse(&without_brand)
        .trim_matches(|c: char| matches!(c, '-' | '–' | '|' | ',' | ':' | ' '))
        .to_owned();
    let mut brand = brand
        .map(collapse)
        .and_then(nonempty)
        .map(|brand| title_case_shouting(&brand));
    if let Some(found) = suffix_brand {
        match &brand {
            None => brand = Some(found.to_owned()),
            // "DROPS" says less than the publisher the page names.
            Some(existing) if existing.eq_ignore_ascii_case("drops") => {
                brand = Some(found.to_owned());
            }
            _ => {}
        }
    }
    let name = title_case_shouting(&name);
    if brand.is_none() && name.to_lowercase().starts_with("drops ") {
        brand = Some("DROPS".to_owned());
    }
    CleanYarnName { brand, name, note }
}

/// Clean one yarn in place. The group remark joins the notes.
pub fn normalize_yarn(yarn: &mut YarnImportDraft) {
    let Some(raw) = yarn.name.clone() else { return };
    let clean = clean_yarn_name(&raw, yarn.brand.as_deref());
    yarn.name = nonempty(clean.name);
    yarn.brand = clean.brand;
    if let Some(note) = clean.note {
        yarn.notes = Some(match yarn.notes.take() {
            Some(existing) if existing.contains(&note) => existing,
            Some(existing) => format!("{existing}\n{note}"),
            None => note,
        });
    }
    for field in [
        &mut yarn.colorway,
        &mut yarn.amount,
        &mut yarn.fiber_content,
    ] {
        if let Some(value) = field.take() {
            *field = nonempty(collapse(&value));
        }
    }
}

// ------------------------------------------------------------------ matching keys

fn fold_char(c: char) -> Option<String> {
    Some(match c {
        'ä' | 'Ä' => "a".into(),
        'ö' | 'Ö' => "o".into(),
        'ü' | 'Ü' => "u".into(),
        'ß' => "ss".into(),
        'é' | 'è' | 'ê' => "e".into(),
        'á' | 'à' | 'â' => "a".into(),
        c if c.is_alphanumeric() => c.to_lowercase().collect(),
        _ => return None,
    })
}

fn tokens(value: &str) -> Vec<String> {
    let mut words = Vec::new();
    let mut current = String::new();
    for c in value.chars() {
        match fold_char(c) {
            Some(folded) => current.push_str(&folded),
            None => {
                if !current.is_empty() {
                    words.push(std::mem::take(&mut current));
                }
            }
        }
    }
    if !current.is_empty() {
        words.push(current);
    }
    words
}

/// Publisher words carry no identity: "DROPS Air" and "Air" by Garnstudio are the same yarn.
const NOISE: [&str; 6] = ["garnstudio", "drops", "design", "von", "by", "the"];

/// Brand tokens, with the DROPS family folded into one brand.
fn brand_key(brand: Option<&str>) -> String {
    let words = tokens(brand.unwrap_or_default());
    if words.iter().any(|w| w == "drops" || w == "garnstudio") {
        return "garnstudio".to_owned();
    }
    words.join(" ")
}

/// Order-independent identity of a yarn name without publisher words.
fn name_tokens(brand: Option<&str>, name: Option<&str>) -> Vec<String> {
    let mut seen = HashSet::new();
    let mut words: Vec<String> = tokens(brand.unwrap_or_default())
        .into_iter()
        .chain(tokens(name.unwrap_or_default()))
        .filter(|word| !NOISE.contains(&word.as_str()))
        .filter(|word| seen.insert(word.clone()))
        .collect();
    // Words of the brand that the name repeats ("Rowan Rowan Felted Tweed") count once; a
    // brand-only match must not pass for the yarn itself.
    let brand_words: HashSet<String> = tokens(brand.unwrap_or_default()).into_iter().collect();
    let own: Vec<String> = tokens(name.unwrap_or_default())
        .into_iter()
        .filter(|word| !NOISE.contains(&word.as_str()))
        .collect();
    if !own.is_empty() {
        words.retain(|word| own.contains(word) || !brand_words.contains(word));
    }
    words.sort();
    words
}

fn bigrams(value: &str) -> Vec<(char, char)> {
    let chars: Vec<char> = value.chars().filter(|c| !c.is_whitespace()).collect();
    chars.windows(2).map(|pair| (pair[0], pair[1])).collect()
}

/// Sørensen–Dice similarity of the character bigrams, 0.0 to 1.0.
fn dice(a: &str, b: &str) -> f64 {
    let left = bigrams(a);
    let mut right = bigrams(b);
    if left.is_empty() || right.is_empty() {
        return if a == b && !a.is_empty() { 1.0 } else { 0.0 };
    }
    let total = left.len() + right.len();
    let mut shared = 0usize;
    for pair in &left {
        if let Some(position) = right.iter().position(|other| other == pair) {
            right.swap_remove(position);
            shared += 1;
        }
    }
    (2 * shared) as f64 / total as f64
}

/// Minimum similarity for a suggestion that is not an exact match.
pub const FUZZY_THRESHOLD: f64 = 0.8;

/// A yarn of the user's stash, as far as matching needs it.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct StashYarn {
    pub id: i64,
    pub name: String,
    pub brand: Option<String>,
}

/// The stash yarn suggested for the imported yarn at `index`.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct StashMatch {
    pub index: usize,
    pub yarn_id: i64,
    pub score: f64,
    pub exact: bool,
}

/// How well `candidate` fits `stash`, or `None` when it is a different yarn.
fn similarity(candidate: &YarnImportDraft, stash: &StashYarn) -> Option<(f64, bool)> {
    let wanted = name_tokens(candidate.brand.as_deref(), candidate.name.as_deref());
    let have = name_tokens(stash.brand.as_deref(), Some(&stash.name));
    if wanted.is_empty() || have.is_empty() {
        return None;
    }
    let (wanted_brand, have_brand) = (
        brand_key(candidate.brand.as_deref()),
        brand_key(stash.brand.as_deref()),
    );
    // Two named, different brands are two different yarns, however alike the names.
    if !wanted_brand.is_empty()
        && !have_brand.is_empty()
        && wanted_brand != have_brand
        && dice(&wanted_brand, &have_brand) < 0.6
    {
        return None;
    }
    if wanted == have {
        return Some((1.0, true));
    }
    let score = dice(&wanted.join(" "), &have.join(" "));
    (score >= FUZZY_THRESHOLD).then_some((score, false))
}

/// The best stash yarn for each imported yarn, matched on normalised brand and name. One stash
/// yarn is suggested for at most one imported yarn (the better fit wins).
pub fn match_stash(yarns: &[YarnImportDraft], stash: &[StashYarn]) -> Vec<StashMatch> {
    let mut candidates: Vec<StashMatch> = Vec::new();
    for (index, yarn) in yarns.iter().enumerate() {
        let best = stash
            .iter()
            .filter_map(|entry| similarity(yarn, entry).map(|(score, exact)| (entry, score, exact)))
            .max_by(|a, b| {
                a.1.partial_cmp(&b.1)
                    .unwrap_or(std::cmp::Ordering::Equal)
                    .then(b.0.id.cmp(&a.0.id))
            });
        if let Some((entry, score, exact)) = best {
            candidates.push(StashMatch {
                index,
                yarn_id: entry.id,
                score: (score * 100.0).round() / 100.0,
                exact,
            });
        }
    }
    candidates.sort_by(|a, b| {
        b.score
            .partial_cmp(&a.score)
            .unwrap_or(std::cmp::Ordering::Equal)
    });
    let mut used = HashSet::new();
    let mut matches: Vec<StashMatch> = candidates
        .into_iter()
        .filter(|found| used.insert(found.yarn_id))
        .collect();
    matches.sort_by_key(|found| found.index);
    matches
}

/// All of the user's own yarns for matching (bounded).
pub async fn load_stash(pool: &SqlitePool, owner_id: i64) -> Vec<StashYarn> {
    sqlx::query_as::<_, (i64, String, Option<String>)>(
        "SELECT id, name, brand FROM yarns WHERE owner_id = ? AND deleted_at IS NULL ORDER BY id LIMIT 5000",
    )
    .bind(owner_id)
    .fetch_all(pool)
    .await
    .unwrap_or_default()
    .into_iter()
    .map(|(id, name, brand)| StashYarn { id, name, brand })
    .collect()
}

// ------------------------------------------------------------------ building the list

/// Product-page links of the page itself: anchors into a yarn section of the same site.
pub fn candidates_from_links(
    links: &[PageLinkFact],
    page_url: Option<&str>,
) -> Vec<YarnImportDraft> {
    let page_host = page_url
        .and_then(|url| Url::parse(url).ok())
        .and_then(|url| url.host_str().map(str::to_lowercase));
    let path_hint = regex::Regex::new(r"(?i)(/|\b)(yarns?|garne?)(\.php|/|\?|$)|[?&]show=")
        .expect("static regex");
    let boilerplate = regex::Regex::new(r"(?i)^(product image\s+)?(.*?)(\s+(yarn|garn|wolle))?$")
        .expect("static regex");
    let generic: HashSet<&str> = [
        "yarn",
        "yarns",
        "garn",
        "garne",
        "wolle",
        "all yarns",
        "alle garne",
        "yarn groups",
        "garngruppen",
        "more",
        "mehr",
        "read more",
    ]
    .into_iter()
    .collect();
    let mut seen = HashSet::new();
    let mut found = Vec::new();
    for link in links {
        let Ok(url) = Url::parse(&link.url) else {
            continue;
        };
        if page_host.as_deref() != url.host_str().map(str::to_lowercase).as_deref() {
            continue;
        }
        let target = format!("{}?{}", url.path(), url.query().unwrap_or_default());
        if !path_hint.is_match(&target)
            || !target.to_lowercase().contains("yarn") && !target.to_lowercase().contains("garn")
        {
            continue;
        }
        let label = link
            .image_alt
            .as_deref()
            .filter(|alt| !alt.is_empty())
            .unwrap_or(&link.text);
        let label = boilerplate
            .captures(label.trim())
            .and_then(|captures| captures.get(2))
            .map_or(label.trim(), |found| found.as_str().trim());
        let words = label.split_whitespace().count();
        if label.is_empty()
            || label.chars().count() > 80
            || generic.contains(label.to_lowercase().as_str())
            || (words < 2 && link.image_alt.is_none())
        {
            continue;
        }
        if !seen.insert(link.url.clone()) {
            continue;
        }
        found.push(YarnImportDraft {
            name: Some(label.to_owned()),
            link: Some(link.url.clone()),
            image_url: link.image_url.clone(),
            ..YarnImportDraft::default()
        });
        if found.len() >= MAX_YARNS {
            break;
        }
    }
    found
}

fn same_yarn(a: &YarnImportDraft, b: &YarnImportDraft) -> bool {
    if let (Some(left), Some(right)) = (&a.link, &b.link)
        && left == right
    {
        return true;
    }
    let left = name_tokens(a.brand.as_deref(), a.name.as_deref());
    let right = name_tokens(b.brand.as_deref(), b.name.as_deref());
    !left.is_empty() && left == right
}

fn fill_missing(target: &mut YarnImportDraft, other: YarnImportDraft) {
    macro_rules! fill {
        ($($field:ident),+) => {
            $(if target.$field.as_ref().is_none_or(|value| value.trim().is_empty()) {
                target.$field = other.$field;
            })+
        };
    }
    fill!(
        name,
        brand,
        colorway,
        weight,
        length,
        weight_category,
        fiber_content,
        link,
        image_url,
        amount,
        notes
    );
}

/// One clean list of the yarns an import found: the yarns the sources named, the page's own
/// product links when nothing else did, and the loose `project.yarn` text as a last resort.
pub fn finalize_project_yarns(draft: &mut ImportDocumentDraft) {
    let links = links_from_draft(draft);
    let mut yarns = std::mem::take(&mut draft.project.yarns);
    if yarns.is_empty() {
        yarns = candidates_from_links(&links, draft.source_url.as_deref());
    }
    if yarns.is_empty()
        && let Some(text) = draft.project.yarn.clone().filter(|t| !t.trim().is_empty())
    {
        yarns.push(YarnImportDraft {
            name: Some(text),
            brand: draft.project.brand.clone(),
            colorway: draft.project.colorway.clone(),
            weight_category: draft.project.weight_category.clone(),
            fiber_content: draft.project.fiber_content.clone(),
            ..YarnImportDraft::default()
        });
    }
    let mut merged: Vec<YarnImportDraft> = Vec::new();
    for mut yarn in yarns {
        normalize_yarn(&mut yarn);
        if yarn.name.is_none() {
            continue;
        }
        match merged
            .iter()
            .position(|existing| same_yarn(existing, &yarn))
        {
            Some(index) => fill_missing(&mut merged[index], yarn),
            None => merged.push(yarn),
        }
    }
    merged.truncate(MAX_YARNS);
    draft.project.yarns = merged;
}

// ------------------------------------------------------------------ model answer

fn clean_text(value: Option<&Value>, max: usize) -> Option<String> {
    let text = collapse(value?.as_str()?);
    let lower = text.to_lowercase();
    if text.is_empty() || matches!(lower.as_str(), "null" | "none" | "n/a" | "unknown" | "-") {
        return None;
    }
    Some(text.chars().take(max).collect())
}

/// Fold `answer.project.yarns` into the draft. Names, colours and amounts must be supported by
/// the page text unless the model read the page itself; a link or picture is accepted only when
/// the page links to it (or, when browsed, when it passes the same-site rule for images).
pub fn accept_ai_yarns(
    draft: &mut ImportDocumentDraft,
    items: &Value,
    source_text: &str,
    grounding: ai_merge::Grounding<'_>,
) -> usize {
    let Some(items) = items.as_array() else {
        return 0;
    };
    let links = links_from_draft(draft);
    let linked: HashSet<&str> = links.iter().map(|link| link.url.as_str()).collect();
    let pictured: HashSet<&str> = links
        .iter()
        .filter_map(|link| link.image_url.as_deref())
        .collect();
    let (grounded, page_url) = match grounding {
        ai_merge::Grounding::PageText | ai_merge::Grounding::TextAndBrowser { .. } => {
            (true, draft.source_url.clone())
        }
        ai_merge::Grounding::Browsed { page_url } => (false, Some(page_url.to_owned())),
    };
    let evidence = format!(
        "{source_text}\n{}",
        draft.project.name.clone().unwrap_or_default()
    );
    let mut accepted = 0;
    for item in items.iter().take(MAX_YARNS) {
        let Some(name) = clean_text(item.get("name"), 160) else {
            continue;
        };
        if grounded && !ai_merge::text_supported(&name, &evidence) {
            continue;
        }
        let mut yarn = YarnImportDraft {
            name: Some(name),
            brand: clean_text(item.get("brand"), 120),
            colorway: clean_text(item.get("colorway"), 120)
                .filter(|value| !grounded || ai_merge::text_supported(value, &evidence)),
            amount: clean_text(item.get("amount"), 200)
                .filter(|value| !grounded || text::numbers_grounded(value, &evidence)),
            ..YarnImportDraft::default()
        };
        yarn.link = clean_text(item.get("url"), 2048).filter(|url| {
            linked.contains(url.as_str())
                || page_url.as_deref().is_some_and(|page| {
                    !grounded && !ai_merge::accept_page_urls(Some(&json!([url])), page).is_empty()
                })
        });
        yarn.image_url = clean_text(item.get("imageUrl"), 2048).filter(|url| {
            pictured.contains(url.as_str())
                || page_url.as_deref().is_some_and(|page| {
                    !grounded && !ai_merge::accept_image_urls(Some(&json!([url])), page).is_empty()
                })
        });
        normalize_yarn(&mut yarn);
        match draft
            .project
            .yarns
            .iter()
            .position(|existing| same_yarn(existing, &yarn))
        {
            Some(index) => {
                // Rule-based values stay; the model fills gaps and the amount.
                fill_missing(&mut draft.project.yarns[index], yarn);
            }
            None if draft.project.yarns.len() < MAX_YARNS => draft.project.yarns.push(yarn),
            None => continue,
        }
        accepted += 1;
    }
    if accepted > 0 {
        draft
            .provenance
            .insert("project.yarns".into(), FieldSource::Ai);
    }
    accepted
}

// ------------------------------------------------------------------ enrichment

/// Facts of a yarn product page, in the shape of a yarn import.
pub type Enrichment = YarnDetails;

fn grams_text(value: i64) -> String {
    format!("{value} g")
}

fn meters_text(value: i64) -> String {
    format!("{value} m")
}

/// Fold what a product page says about a yarn into the pattern's yarn. The pattern's own
/// colour and amount stay; the page fills grams, meters, fibre, weight category, image and
/// link, and supplies the clean name when it agrees with what the pattern called the yarn.
pub fn merge_enrichment(yarn: &mut YarnImportDraft, details: &YarnDetails, product_url: &str) {
    let probe = YarnImportDraft {
        name: details.name.clone(),
        brand: details.brand.clone(),
        ..YarnImportDraft::default()
    };
    let mut page = probe;
    normalize_yarn(&mut page);
    let agrees =
        yarn.name.is_none() || page.name.is_some() && similarity_of_names(yarn, &page) >= 0.6;
    if agrees && let Some(name) = page.name.take() {
        yarn.name = Some(name);
    }
    if yarn.brand.is_none() {
        yarn.brand = page.brand.take();
    }
    if yarn.weight.is_none()
        && let Some(grams) = details.weight_grams
    {
        yarn.weight = Some(grams_text(grams));
    }
    if yarn.length.is_none()
        && let Some(meters) = details.length_meters
    {
        yarn.length = Some(meters_text(meters));
    }
    if let Some(category) = details.weight_category.clone() {
        yarn.weight_category.get_or_insert(category);
    }
    if let Some(fibre) = details.fiber_content.clone() {
        yarn.fiber_content = Some(fibre);
    }
    if yarn.image_url.is_none() {
        yarn.image_url = details.image_urls.first().cloned();
    }
    yarn.link = Some(
        details
            .link
            .clone()
            .filter(|link| !link.is_empty())
            .unwrap_or_else(|| product_url.to_owned()),
    );
}

fn similarity_of_names(a: &YarnImportDraft, b: &YarnImportDraft) -> f64 {
    let left = name_tokens(a.brand.as_deref(), a.name.as_deref()).join(" ");
    let right = name_tokens(b.brand.as_deref(), b.name.as_deref()).join(" ");
    dice(&left, &right)
}

/// How an enrichment ended, for the warning and for tests.
#[derive(Debug, Default, Eq, PartialEq)]
pub struct EnrichReport {
    pub attempted: usize,
    pub enriched: usize,
}

/// Run `fetch` for the product page of at most [`MAX_ENRICHED`] yarns in parallel, each with its
/// own [`ENRICH_TIMEOUT`], and fold the results in. A failed or slow page leaves the yarn as the
/// pattern page described it. `fetch` returns `None` when the page is not a usable yarn page.
pub async fn enrich_with<F, Fut>(
    draft: &mut ImportDocumentDraft,
    timeout: Duration,
    fetch: F,
) -> EnrichReport
where
    F: Fn(String) -> Fut + Send + Sync + 'static,
    Fut: Future<Output = Option<YarnDetails>> + Send + 'static,
{
    let fetch = Arc::new(fetch);
    let mut jobs = JoinSet::new();
    let mut seen = HashSet::new();
    for (index, yarn) in draft.project.yarns.iter().enumerate() {
        let Some(link) = yarn
            .link
            .clone()
            .filter(|link| Url::parse(link).is_ok_and(|url| safe_web_url(&url).is_some()))
        else {
            continue;
        };
        if jobs.len() >= MAX_ENRICHED || !seen.insert(link.clone()) {
            continue;
        }
        let fetch = Arc::clone(&fetch);
        jobs.spawn(async move {
            let result = tokio::time::timeout(timeout, fetch(link.clone())).await;
            (index, link, result.ok().flatten())
        });
    }
    let mut report = EnrichReport {
        attempted: jobs.len(),
        enriched: 0,
    };
    while let Some(done) = jobs.join_next().await {
        let Ok((index, link, details)) = done else {
            continue;
        };
        let Some(details) = details else { continue };
        if let Some(yarn) = draft.project.yarns.get_mut(index) {
            merge_enrichment(yarn, &details, &link);
            report.enriched += 1;
        }
    }
    if report.enriched < report.attempted
        && !draft.warnings.iter().any(|code| code == ENRICH_WARNING)
    {
        draft.warnings.push(ENRICH_WARNING.to_owned());
    }
    report
}

/// The production fetch: the same structured yarn-from-link import the "From a link" flow runs
/// (safe fetch, isolated parser, and the AI step when the user asked for it).
pub async fn enrich_project_yarns(
    worker_path: &std::path::Path,
    ai: Option<Arc<AiClient>>,
    use_ai: bool,
    draft: &mut ImportDocumentDraft,
) -> EnrichReport {
    let worker_path = worker_path.to_path_buf();
    enrich_with(draft, ENRICH_TIMEOUT, move |link| {
        let worker_path = worker_path.clone();
        let ai = ai.clone();
        async move {
            let may_browse = use_ai && ai.as_deref().is_some_and(AiClient::browse_enabled);
            let mut imported = import_service::import_url_browsing(
                &worker_path,
                &link,
                ImportTarget::Yarn,
                may_browse,
            )
            .await
            .ok()?;
            import_service::normalize_with_ai(
                ai.as_deref(),
                &mut imported.draft,
                use_ai,
                &imported.source_text,
                ImportTarget::Yarn,
                &imported.browse,
            )
            .await;
            if imported.draft.page_kind == PageKind::Pattern {
                return None;
            }
            let mut details = imported.draft.yarn?;
            details.link.get_or_insert(link);
            let useful = details.weight_grams.is_some()
                || details.length_meters.is_some()
                || details.fiber_content.is_some()
                || details.weight_category.is_some()
                || !details.image_urls.is_empty();
            useful.then_some(details)
        }
    })
    .await
}

#[cfg(test)]
#[path = "yarn_import/tests.rs"]
mod tests;
