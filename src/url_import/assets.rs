//! Supporting files of a pattern page that are not gallery photos: diagrams, charts, schematics
//! and measurement drawings (images) and linked PDFs. They are stored as project attachments,
//! labelled by kind, so they are not mixed into the photo gallery.

use std::{collections::HashSet, future::Future};

use scraper::{ElementRef, Html};
use url::Url;

use super::facts::{IMAGE_REJECT, collapse, image_identity, sel};
use crate::import_document::{AssetKind, ImportAsset, ProjectImportDraft, resolve_url};

pub const MAX_DIAGRAMS: usize = 6;
pub const MAX_PDFS: usize = 4;
const MAX_LABEL_CHARS: usize = 120;

/// Whole words that mark a picture as a diagram, chart or drawing.
const DIAGRAM_WORDS: &[&str] = &[
    "diagram",
    "diagrams",
    "diagramm",
    "diagramme",
    "chart",
    "charts",
    "schema",
    "schemas",
    "schematic",
    "schematics",
    "sketch",
    "schnittdiagramm",
];
/// Fragments that mark a diagram inside a longer (compound) word, such as "Maßskizze".
const DIAGRAM_FRAGMENTS: &[&str] = &["skizze", "diagram", "schematic"];

/// Whether `text` (a URL, alt text, class list or heading) names a diagram.
pub fn diagram_hint(text: &str) -> bool {
    text.to_lowercase()
        .split(|c: char| !c.is_alphanumeric())
        .filter(|token| !token.is_empty())
        .any(|token| {
            DIAGRAM_WORDS.contains(&token)
                || DIAGRAM_FRAGMENTS
                    .iter()
                    .any(|fragment| token.contains(fragment))
        })
}

fn path_of(url: &str) -> &str {
    url.split(['?', '#']).next().unwrap_or(url)
}

fn short_label(text: &str) -> Option<String> {
    let text = collapse(text);
    (!text.is_empty()).then(|| text.chars().take(MAX_LABEL_CHARS).collect())
}

fn heading_text(element: ElementRef<'_>) -> Option<String> {
    matches!(
        element.value().name(),
        "h1" | "h2" | "h3" | "h4" | "h5" | "h6"
    )
    .then(|| element.text().collect::<String>())
    .and_then(|text| short_label(&text))
}

/// The closest heading before the picture, looking at the previous siblings of the picture and of
/// its first few ancestors.
fn nearby_heading(img: ElementRef<'_>) -> Option<String> {
    let mut node = img;
    for _ in 0..3 {
        if let Some(text) = node
            .prev_siblings()
            .filter_map(ElementRef::wrap)
            .take(2)
            .find_map(heading_text)
        {
            return Some(text);
        }
        node = ElementRef::wrap(node.parent()?)?;
    }
    None
}

/// Diagram images and PDF links of an HTML document, lazy-loaded pictures included.
pub fn collect(document: &Html, base: Option<&Url>) -> Vec<ImportAsset> {
    let mut assets: Vec<ImportAsset> = Vec::new();
    let mut seen = HashSet::new();

    for img in document.select(&sel("img")).take(400) {
        if assets
            .iter()
            .filter(|asset| asset.kind == AssetKind::Diagram)
            .count()
            >= MAX_DIAGRAMS
        {
            break;
        }
        let element = img.value();
        let raw = element
            .attr("data-zoom-image")
            .or_else(|| element.attr("data-large_image"))
            .or_else(|| element.attr("data-src"))
            .or_else(|| element.attr("data-lazy-src"))
            .or_else(|| element.attr("data-original"))
            .or_else(|| element.attr("src"));
        let Some(url) = raw.and_then(|raw| resolve_url(base, raw)) else {
            continue;
        };
        let path = path_of(&url).to_ascii_lowercase();
        if path.ends_with(".svg") || path.ends_with(".ico") || path.starts_with("data:") {
            continue;
        }
        let alt = element.attr("alt").and_then(short_label);
        let title = element.attr("title").and_then(short_label);
        let own_hint = format!(
            "{} {} {} {}",
            path,
            alt.as_deref().unwrap_or_default(),
            title.as_deref().unwrap_or_default(),
            element.attr("class").unwrap_or_default(),
        );
        let heading = nearby_heading(img);
        let diagram = diagram_hint(&own_hint)
            || element.attr("id").is_some_and(diagram_hint)
            || heading.as_deref().is_some_and(diagram_hint);
        if !diagram {
            continue;
        }
        let reject_hint = format!(
            "{} {}",
            path,
            element.attr("class").unwrap_or_default().to_lowercase()
        );
        if IMAGE_REJECT.iter().any(|word| reject_hint.contains(word)) {
            continue;
        }
        let small = |name: &str| {
            element
                .attr(name)
                .and_then(|value| value.trim_end_matches("px").parse::<u32>().ok())
                .is_some_and(|value| value < 120)
        };
        if small("width") || small("height") {
            continue;
        }
        if seen.insert(image_identity(&url)) {
            assets.push(ImportAsset {
                url,
                kind: AssetKind::Diagram,
                label: alt.or(title).or(heading),
            });
        }
    }

    let mut pdfs = 0;
    for link in document.select(&sel("a[href]")).take(800) {
        if pdfs >= MAX_PDFS {
            break;
        }
        let element = link.value();
        let Some(url) = element
            .attr("href")
            .and_then(|href| resolve_url(base, href))
        else {
            continue;
        };
        if !path_of(&url).to_ascii_lowercase().ends_with(".pdf") {
            continue;
        }
        if seen.insert(url.to_ascii_lowercase()) {
            pdfs += 1;
            assets.push(ImportAsset {
                url,
                kind: AssetKind::Pdf,
                label: short_label(&link.text().collect::<String>())
                    .or_else(|| element.attr("title").and_then(short_label)),
            });
        }
    }
    assets
}

/// Add `assets` to the draft, skipping duplicates and respecting the per-kind caps.
/// Returns how many were added.
pub fn merge_assets(project: &mut ProjectImportDraft, assets: Vec<ImportAsset>) -> usize {
    let mut added = 0;
    for asset in assets {
        let identity = asset_identity(&asset);
        if project
            .assets
            .iter()
            .any(|existing| asset_identity(existing) == identity)
        {
            continue;
        }
        let (limit, count) = match asset.kind {
            AssetKind::Diagram => (MAX_DIAGRAMS, count_kind(project, AssetKind::Diagram)),
            AssetKind::Pdf => (MAX_PDFS, count_kind(project, AssetKind::Pdf)),
        };
        if count >= limit {
            continue;
        }
        project.assets.push(asset);
        added += 1;
    }
    added
}

fn count_kind(project: &ProjectImportDraft, kind: AssetKind) -> usize {
    project
        .assets
        .iter()
        .filter(|asset| asset.kind == kind)
        .count()
}

fn asset_identity(asset: &ImportAsset) -> String {
    match asset.kind {
        AssetKind::Diagram => image_identity(&asset.url),
        AssetKind::Pdf => asset.url.to_ascii_lowercase(),
    }
}

/// Keep diagrams out of the photo gallery: drop gallery pictures that are also listed as assets
/// or whose URL names a diagram.
pub fn remove_diagrams_from_gallery(project: &mut ProjectImportDraft) {
    let diagrams: HashSet<String> = project
        .assets
        .iter()
        .filter(|asset| asset.kind == AssetKind::Diagram)
        .map(asset_identity)
        .collect();
    project
        .image_urls
        .retain(|url| !diagrams.contains(&image_identity(url)) && !diagram_hint(path_of(url)));
}

// ------------------------------------------------------------------ DROPS

/// A DROPS diagram address derived from a photo address, with a label for it.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DerivedAsset {
    pub url: String,
    pub label: String,
}

/// DROPS patterns keep their diagram next to the photos: `drops/mag/{issue}/{n}/{n}-chart.jpg`.
/// Derive that address from any photo of the pattern (`{n}-2.jpg`, `{n}b-2.jpg`, ...).
pub fn derive_drops_charts(urls: &[String]) -> Vec<DerivedAsset> {
    let mut out: Vec<DerivedAsset> = Vec::new();
    for raw in urls {
        let Ok(url) = Url::parse(raw) else { continue };
        let Some(host) = url.host_str().map(str::to_lowercase) else {
            continue;
        };
        if !(host == "garnstudio.com" || host.ends_with(".garnstudio.com")) {
            continue;
        }
        let segments: Vec<&str> = url
            .path_segments()
            .map(Iterator::collect)
            .unwrap_or_default();
        let Some(at) = segments
            .windows(2)
            .position(|pair| pair == ["drops", "mag"])
        else {
            continue;
        };
        let (Some(issue), Some(number), Some(_file)) = (
            segments.get(at + 2),
            segments.get(at + 3),
            segments.get(at + 4),
        ) else {
            continue;
        };
        let digits = |value: &str| !value.is_empty() && value.chars().all(|c| c.is_ascii_digit());
        if !digits(issue) || !digits(number) {
            continue;
        }
        let derived = DerivedAsset {
            url: format!(
                "{}://{host}/drops/mag/{issue}/{number}/{number}-chart.jpg",
                url.scheme()
            ),
            label: format!("Diagram for DROPS {issue}-{number}"),
        };
        if !out.iter().any(|existing| existing.url == derived.url) {
            out.push(derived);
        }
    }
    out
}

/// Add the DROPS diagram of the pattern when its derived address exists. `exists` is the
/// (SSRF-safe) check that the address serves a picture. Returns how many were added.
pub async fn complete_drops_diagram<F, Fut>(project: &mut ProjectImportDraft, exists: F) -> usize
where
    F: Fn(String) -> Fut,
    Fut: Future<Output = bool>,
{
    let sources: Vec<String> = project
        .image_urls
        .iter()
        .cloned()
        .chain(project.assets.iter().map(|asset| asset.url.clone()))
        .collect();
    let mut added = 0;
    for derived in derive_drops_charts(&sources).into_iter().take(2) {
        let already = project
            .assets
            .iter()
            .any(|asset| image_identity(&asset.url) == image_identity(&derived.url));
        if already || !exists(derived.url.clone()).await {
            continue;
        }
        added += merge_assets(
            project,
            vec![ImportAsset {
                url: derived.url,
                kind: AssetKind::Diagram,
                label: Some(derived.label),
            }],
        );
    }
    added
}

#[cfg(test)]
mod tests {
    use super::*;

    const DROPS_HTML: &str = r#"<html><body><main>
        <h1>Sweater / DROPS 273-22</h1>
        <img src="https://images.garnstudio.com/drops/mag/273/22/22-2.jpg" alt="Photo">
        <h2>Diagram</h2>
        <img class="img-fluid" alt="Diagram for DROPS 273-22"
             data-src="https://images.garnstudio.com/drops/mag/273/22/22-chart.jpg" src="/lazy.gif">
        <a href="/downloads/pattern-273-22.pdf?x=1">Download the pattern as PDF</a>
        <a href="/about.html">About</a>
        </main></body></html>"#;

    #[test]
    fn diagram_words_are_whole_words_or_known_fragments() {
        assert!(diagram_hint("https://x.test/drops/mag/1/2/2-chart.jpg"));
        assert!(diagram_hint("Maßskizze Pullover"));
        assert!(diagram_hint("Schema"));
        assert!(!diagram_hint("charter flight photo"));
        assert!(!diagram_hint("hero photo"));
    }

    #[test]
    fn lazy_chart_and_pdf_link_are_collected() {
        let base = Url::parse("https://www.garnstudio.com/pattern.php?id=12752&cid=9").unwrap();
        let assets = collect(&Html::parse_document(DROPS_HTML), Some(&base));
        assert_eq!(assets.len(), 2, "{assets:?}");
        assert_eq!(assets[0].kind, AssetKind::Diagram);
        assert_eq!(
            assets[0].url,
            "https://images.garnstudio.com/drops/mag/273/22/22-chart.jpg"
        );
        assert_eq!(assets[0].label.as_deref(), Some("Diagram for DROPS 273-22"));
        assert_eq!(assets[1].kind, AssetKind::Pdf);
        assert_eq!(
            assets[1].url,
            "https://www.garnstudio.com/downloads/pattern-273-22.pdf?x=1"
        );
        assert_eq!(
            assets[1].label.as_deref(),
            Some("Download the pattern as PDF")
        );
    }

    #[test]
    fn heading_before_a_plain_image_marks_it() {
        let html = r#"<div><h3>Maßskizze</h3><div><img src="/img/a1.png"></div></div>"#;
        let base = Url::parse("https://example.test/p").unwrap();
        let assets = collect(&Html::parse_document(html), Some(&base));
        assert_eq!(assets.len(), 1);
        assert_eq!(assets[0].label.as_deref(), Some("Maßskizze"));
    }

    #[test]
    fn chart_urls_are_derived_from_any_drops_photo() {
        let photos = vec![
            "https://images.garnstudio.com/drops/mag/273/22/22b-2.jpg".to_owned(),
            "https://images.garnstudio.com/drops/mag/273/22/22-2.jpg".to_owned(),
            "https://example.test/drops/mag/1/2/2-2.jpg".to_owned(),
            "https://images.garnstudio.com/drops/mag/abc/22/22-2.jpg".to_owned(),
        ];
        let derived = derive_drops_charts(&photos);
        assert_eq!(derived.len(), 1);
        assert_eq!(
            derived[0].url,
            "https://images.garnstudio.com/drops/mag/273/22/22-chart.jpg"
        );
        assert_eq!(derived[0].label, "Diagram for DROPS 273-22");
    }

    #[tokio::test]
    async fn derived_chart_is_added_only_when_it_exists() {
        let mut project = ProjectImportDraft {
            image_urls: vec!["https://images.garnstudio.com/drops/mag/273/22/22-2.jpg".into()],
            ..ProjectImportDraft::default()
        };
        assert_eq!(
            complete_drops_diagram(&mut project, |_| async { false }).await,
            0
        );
        assert!(project.assets.is_empty());
        assert_eq!(
            complete_drops_diagram(&mut project, |_| async { true }).await,
            1
        );
        assert_eq!(project.assets.len(), 1);
        // A second pass does not add it again.
        assert_eq!(
            complete_drops_diagram(&mut project, |_| async { true }).await,
            0
        );
    }

    #[test]
    fn diagrams_leave_the_gallery() {
        let mut project = ProjectImportDraft {
            image_urls: vec![
                "https://images.garnstudio.com/drops/mag/273/22/22-2.jpg".into(),
                "https://images.garnstudio.com/drops/mag/273/22/22-chart.jpg".into(),
            ],
            assets: vec![ImportAsset {
                url: "https://images.garnstudio.com/drops/mag/273/22/22-chart.jpg".into(),
                kind: AssetKind::Diagram,
                label: None,
            }],
            ..ProjectImportDraft::default()
        };
        remove_diagrams_from_gallery(&mut project);
        assert_eq!(project.image_urls.len(), 1);
    }
}
