//! Turn an HTML document into reusable facts: metadata, schema.org nodes (JSON-LD and
//! microdata), image candidates and a structured plain-text rendering.

use std::collections::BTreeMap;

use scraper::{ElementRef, Html, Node, Selector};
use serde_json::{Map, Value};
use url::Url;

use crate::import_document::{nonempty, resolve_url};

const MAX_JSON_LD_BLOCKS: usize = 24;
const MAX_TEXT_CHARS: usize = 80_000;
const MAX_IMAGES_SCANNED: usize = 400;

/// A schema.org node flattened out of JSON-LD or microdata.
pub type SchemaNode = Map<String, Value>;

/// One hyperlink on the page, with the text and picture inside it.
#[derive(Clone, Debug, PartialEq)]
pub struct PageLinkFact {
    pub url: String,
    pub text: String,
    pub image_url: Option<String>,
    pub image_alt: Option<String>,
}

/// One `<img>` or social-preview image with a quality score.
#[derive(Clone, Debug, PartialEq)]
pub struct ImageCandidate {
    pub url: String,
    pub score: i32,
}

#[derive(Debug, Default)]
pub struct PageFacts {
    pub title: Option<String>,
    pub h1: Option<String>,
    pub lang: Option<String>,
    pub canonical: Option<String>,
    /// `property` / `name` / `itemprop` of `<meta>` tags, lowercase, first value wins.
    pub meta: BTreeMap<String, String>,
    pub nodes: Vec<SchemaNode>,
    pub images: Vec<ImageCandidate>,
    /// Diagram images and PDF links, kept apart from the photo gallery.
    pub assets: Vec<crate::import_document::ImportAsset>,
    /// Visible text, one logical line per block, `label: value` for table and definition rows.
    pub text: String,
    pub keywords: Vec<String>,
    /// Distinct `http(s)` links in document order (bounded).
    pub links: Vec<PageLinkFact>,
}

impl PageFacts {
    pub fn meta(&self, keys: &[&str]) -> Option<String> {
        keys.iter().find_map(|key| self.meta.get(*key).cloned())
    }

    /// Nodes whose `@type` equals (or contains) one of `types`, case-insensitively.
    pub fn nodes_of<'a>(
        &'a self,
        types: &'a [&'a str],
    ) -> impl Iterator<Item = &'a SchemaNode> + 'a {
        self.nodes.iter().filter(move |node| has_type(node, types))
    }
}

pub fn has_type(node: &SchemaNode, types: &[&str]) -> bool {
    let matches = |value: &str| {
        let value = value.rsplit(['/', '#']).next().unwrap_or(value);
        types
            .iter()
            .any(|wanted| wanted.eq_ignore_ascii_case(value))
    };
    match node.get("@type") {
        Some(Value::String(value)) => matches(value),
        Some(Value::Array(values)) => values.iter().filter_map(Value::as_str).any(matches),
        _ => false,
    }
}

pub(super) fn sel(css: &str) -> Selector {
    Selector::parse(css).expect("static CSS selector is valid")
}

#[allow(clippy::field_reassign_with_default)]
pub fn collect(html: &str, base: Option<&Url>) -> PageFacts {
    let document = Html::parse_document(html);
    let mut facts = PageFacts::default();

    facts.title = document
        .select(&sel("title"))
        .next()
        .map(|node| collapse(&node.text().collect::<String>()))
        .and_then(|value| nonempty(&value).map(str::to_owned));
    facts.h1 = document
        .select(&sel("h1"))
        .map(|node| collapse(&node.text().collect::<String>()))
        .find(|value| !value.is_empty());
    facts.lang = document
        .select(&sel("html"))
        .next()
        .and_then(|node| node.value().attr("lang"))
        .map(|lang| lang.to_ascii_lowercase());
    facts.canonical = document
        .select(&sel("link[rel='canonical']"))
        .next()
        .and_then(|node| node.value().attr("href"))
        .and_then(|href| resolve_url(base, href));

    for node in document.select(&sel("meta")) {
        let element = node.value();
        let key = element
            .attr("property")
            .or_else(|| element.attr("name"))
            .or_else(|| element.attr("itemprop"))
            .unwrap_or_default()
            .trim()
            .to_ascii_lowercase();
        if key.is_empty() {
            continue;
        }
        if let Some(value) = element.attr("content").and_then(nonempty) {
            facts.meta.entry(key).or_insert_with(|| value.to_owned());
        }
    }
    facts.keywords = facts
        .meta(&["keywords", "article:tag"])
        .map(|value| {
            value
                .split(',')
                .map(collapse)
                .filter(|tag| !tag.is_empty() && tag.len() <= 40)
                .take(12)
                .collect()
        })
        .unwrap_or_default();

    for script in document
        .select(&sel("script[type='application/ld+json']"))
        .take(MAX_JSON_LD_BLOCKS)
    {
        let raw = script.text().collect::<String>();
        if let Some(value) = parse_json_lenient(&raw) {
            flatten_nodes(&value, &mut facts.nodes);
        }
    }
    for item in document.select(&sel("[itemscope]")) {
        // Only top-level items; nested ones are reached through their parent.
        if item.value().attr("itemprop").is_some() {
            continue;
        }
        let object = microdata_item(item);
        flatten_nodes(&Value::Object(object), &mut facts.nodes);
    }

    facts.images = image_candidates(&document, &facts.meta, base);
    facts.links = page_links(&document, base);
    facts.assets = super::assets::collect(&document, base);
    facts.text = visible_text(&document);
    facts
}

const MAX_LINKS_SCANNED: usize = 600;
const MAX_LINKS_KEPT: usize = 200;

fn page_links(document: &Html, base: Option<&Url>) -> Vec<PageLinkFact> {
    let mut seen = std::collections::HashSet::new();
    let mut links = Vec::new();
    for anchor in document.select(&sel("a[href]")).take(MAX_LINKS_SCANNED) {
        let Some(url) = anchor
            .value()
            .attr("href")
            .and_then(|href| resolve_url(base, href))
        else {
            continue;
        };
        let image = anchor.select(&sel("img")).next();
        let text = collapse(&anchor.text().collect::<String>());
        let image_alt = image
            .and_then(|img| img.value().attr("alt"))
            .map(collapse)
            .filter(|alt| !alt.is_empty());
        let image_url = image
            .and_then(|img| {
                img.value()
                    .attr("src")
                    .or_else(|| img.value().attr("data-src"))
            })
            .and_then(|src| resolve_url(base, src));
        if !seen.insert(url.clone()) {
            continue;
        }
        links.push(PageLinkFact {
            url,
            text: text.chars().take(160).collect(),
            image_url,
            image_alt: image_alt.map(|alt| alt.chars().take(160).collect()),
        });
        if links.len() >= MAX_LINKS_KEPT {
            break;
        }
    }
    links
}

fn parse_json_lenient(raw: &str) -> Option<Value> {
    let raw = raw
        .trim()
        .trim_start_matches("<!--")
        .trim_end_matches("-->");
    serde_json::from_str(raw).ok().or_else(|| {
        // Raw newlines and tabs inside JSON strings are a common shop-template bug.
        let cleaned: String = raw
            .chars()
            .map(|c| if c.is_control() { ' ' } else { c })
            .collect();
        serde_json::from_str(&cleaned).ok()
    })
}

fn flatten_nodes(value: &Value, out: &mut Vec<SchemaNode>) {
    match value {
        Value::Array(values) => values.iter().for_each(|value| flatten_nodes(value, out)),
        Value::Object(map) => {
            if map.contains_key("@type") {
                out.push(map.clone());
            }
            for (key, child) in map {
                if key == "@context" {
                    continue;
                }
                flatten_nodes(child, out);
            }
        }
        _ => {}
    }
}

fn microdata_item(item: ElementRef<'_>) -> SchemaNode {
    let mut map = Map::new();
    if let Some(kind) = item
        .value()
        .attr("itemtype")
        .and_then(|value| value.split_whitespace().next())
    {
        let name = kind.rsplit('/').next().unwrap_or(kind);
        map.insert("@type".into(), Value::String(name.to_owned()));
    }
    walk_microdata(item, &mut map);
    map
}

fn walk_microdata(parent: ElementRef<'_>, map: &mut SchemaNode) {
    for child in parent.children().filter_map(ElementRef::wrap) {
        let element = child.value();
        let props: Vec<&str> = element
            .attr("itemprop")
            .map(|value| value.split_whitespace().collect())
            .unwrap_or_default();
        let is_scope = element.attr("itemscope").is_some();
        if !props.is_empty() {
            let value = if is_scope {
                Value::Object(microdata_item(child))
            } else {
                Value::String(microdata_value(child))
            };
            for prop in props {
                match map.get_mut(prop) {
                    Some(Value::Array(values)) => values.push(value.clone()),
                    Some(existing) => {
                        let first = existing.take();
                        *existing = Value::Array(vec![first, value.clone()]);
                    }
                    None => {
                        map.insert(prop.to_owned(), value.clone());
                    }
                }
            }
            if !is_scope {
                walk_microdata(child, map);
            }
        } else if !is_scope {
            walk_microdata(child, map);
        }
    }
}

fn microdata_value(element: ElementRef<'_>) -> String {
    let value = element.value();
    if let Some(content) = value.attr("content") {
        return content.trim().to_owned();
    }
    let attribute = match value.name() {
        "a" | "link" | "area" => value.attr("href"),
        "img" | "source" | "audio" | "video" | "embed" | "iframe" => value.attr("src"),
        "meta" => value.attr("content"),
        "time" => value.attr("datetime"),
        "data" | "meter" => value.attr("value"),
        _ => None,
    };
    attribute.map_or_else(
        || collapse(&element.text().collect::<String>()),
        |v| v.trim().to_owned(),
    )
}

// ---------------------------------------------------------------- images

pub(super) const IMAGE_REJECT: &[&str] = &[
    "logo",
    "icon",
    "sprite",
    "avatar",
    "badge",
    "payment",
    "paypal",
    "flag",
    "placeholder",
    "pixel",
    "tracking",
    "spacer",
    "blank",
    "loading",
    "lazy.",
    "arrow",
    "star",
    "rating",
    "social",
    "facebook",
    "instagram",
    "pinterest",
    "banner",
    "button",
    "captcha",
    "gravatar",
    "emoji",
    "favicon",
    "trustpilot",
    "klarna",
    "visa",
    "mastercard",
    "shademap",
    "pricelist",
];
const IMAGE_BOOST: &[&str] = &[
    "product",
    "gallery",
    "hero",
    "main",
    "pattern",
    "anleitung",
    "zoom",
    "large",
    "detail",
    "article",
    "featured",
    "primary",
    "slide",
];

fn image_candidates(
    document: &Html,
    meta: &BTreeMap<String, String>,
    base: Option<&Url>,
) -> Vec<ImageCandidate> {
    let mut found: Vec<ImageCandidate> = Vec::new();
    for key in [
        "og:image",
        "og:image:secure_url",
        "twitter:image",
        "twitter:image:src",
    ] {
        if let Some(url) = meta.get(key).and_then(|value| resolve_url(base, value)) {
            found.push(ImageCandidate { url, score: 100 });
        }
    }
    for (index, img) in document
        .select(&sel("img"))
        .take(MAX_IMAGES_SCANNED)
        .enumerate()
    {
        let element = img.value();
        let raw = element
            .attr("data-zoom-image")
            .or_else(|| element.attr("data-large_image"))
            .or_else(|| element.attr("data-src"))
            .or_else(|| element.attr("data-lazy-src"))
            .or_else(|| element.attr("data-original"))
            .map(str::to_owned)
            .or_else(|| {
                element
                    .attr("srcset")
                    .or_else(|| element.attr("data-srcset"))
                    .and_then(largest_from_srcset)
            })
            .or_else(|| element.attr("src").map(str::to_owned));
        let Some(raw) = raw else { continue };
        let Some(url) = resolve_url(base, &raw) else {
            continue;
        };
        let lower = url.to_ascii_lowercase();
        let hint = format!(
            "{} {} {}",
            lower,
            element
                .attr("class")
                .unwrap_or_default()
                .to_ascii_lowercase(),
            element.attr("id").unwrap_or_default().to_ascii_lowercase()
        );
        let path = lower.split(['?', '#']).next().unwrap_or(&lower);
        if path.ends_with(".svg") || path.ends_with(".ico") || path.starts_with("data:") {
            continue;
        }
        if IMAGE_REJECT.iter().any(|word| hint.contains(word)) {
            continue;
        }
        let dimension = |name: &str| {
            element
                .attr(name)
                .and_then(|v| v.trim_end_matches("px").parse::<u32>().ok())
        };
        let (width, height) = (dimension("width"), dimension("height"));
        if width.is_some_and(|w| w < 120) || height.is_some_and(|h| h < 120) {
            continue;
        }
        let mut score = 40 - (index as i32).min(30);
        if width.is_some_and(|w| w >= 400) || height.is_some_and(|h| h >= 400) {
            score += 15;
        }
        if IMAGE_BOOST.iter().any(|word| hint.contains(word)) {
            score += 15;
        }
        if img
            .ancestors()
            .filter_map(ElementRef::wrap)
            .any(|ancestor| {
                matches!(ancestor.value().name(), "main" | "article")
                    || ancestor.value().attr("role") == Some("main")
            })
        {
            score += 10;
        }
        if img
            .ancestors()
            .filter_map(ElementRef::wrap)
            .any(|ancestor| {
                matches!(
                    ancestor.value().name(),
                    "header" | "footer" | "nav" | "aside"
                )
            })
        {
            score -= 40;
        }
        found.push(ImageCandidate { url, score });
    }
    found
}

fn largest_from_srcset(srcset: &str) -> Option<String> {
    srcset
        .split(',')
        .filter_map(|entry| {
            let mut parts = entry.split_whitespace();
            let url = parts.next()?;
            let size = parts
                .next()
                .map(|d| d.trim_end_matches(['w', 'x']).parse::<f32>().unwrap_or(1.0))
                .unwrap_or(1.0);
            Some((size, url.to_owned()))
        })
        .max_by(|a, b| a.0.total_cmp(&b.0))
        .map(|(_, url)| url)
}

/// A key for deduplicating the same picture served in several sizes.
pub fn image_identity(url: &str) -> String {
    let without_query = url
        .split(['?', '#'])
        .next()
        .unwrap_or(url)
        .to_ascii_lowercase();
    let (dir, file) = without_query
        .rsplit_once('/')
        .unwrap_or(("", &without_query));
    let (stem, extension) = file.rsplit_once('.').unwrap_or((file, ""));
    let mut stem = stem.to_owned();
    // WordPress/Shopify style size suffixes: name-300x300, name_600x, name-scaled, name@2x.
    for _ in 0..2 {
        if let Some((head, tail)) = stem.rsplit_once(['-', '_'])
            && (tail == "scaled"
                || tail == "large"
                || tail == "medium"
                || tail == "small"
                || tail == "thumb"
                || tail == "grande"
                || tail
                    .trim_end_matches("x")
                    .chars()
                    .all(|c| c.is_ascii_digit() || c == 'x')
                    && tail.chars().any(|c| c.is_ascii_digit())
                    && tail.contains('x'))
        {
            stem = head.to_owned();
        }
    }
    format!("{dir}/{stem}.{extension}")
}

// ------------------------------------------------------------------ text

const SKIP_TAGS: &[&str] = &[
    "script", "style", "noscript", "svg", "template", "nav", "footer", "form", "iframe", "select",
    "button", "head", "dialog",
];
const SKIP_HINTS: &[&str] = &[
    "cookie",
    "consent",
    "gdpr",
    "newsletter",
    "popup",
    "minicart",
    "mini-cart",
    "breadcrumb",
    "site-header",
    "main-nav",
    "megamenu",
    "mega-menu",
    "footer",
];
const BLOCK_TAGS: &[&str] = &[
    "p",
    "div",
    "section",
    "article",
    "main",
    "ul",
    "ol",
    "li",
    "h1",
    "h2",
    "h3",
    "h4",
    "h5",
    "h6",
    "br",
    "tr",
    "table",
    "dl",
    "dd",
    "dt",
    "blockquote",
    "figure",
    "figcaption",
    "pre",
    "aside",
    "header",
];

fn visible_text(document: &Html) -> String {
    let mut out = String::new();
    if let Some(body) = document.select(&sel("body")).next() {
        walk_text(body, &mut out);
    }
    let mut lines: Vec<String> = Vec::new();
    for line in out.lines() {
        let line = collapse(line);
        if line.is_empty() || lines.last() == Some(&line) {
            continue;
        }
        lines.push(line);
    }
    let mut text = lines.join("\n");
    if text.chars().count() > MAX_TEXT_CHARS {
        text = text.chars().take(MAX_TEXT_CHARS).collect();
    }
    text
}

fn hidden(element: ElementRef<'_>) -> bool {
    let value = element.value();
    if SKIP_TAGS.contains(&value.name()) {
        return true;
    }
    // Class names on the page root often carry state such as `cookie-bar-active`.
    if matches!(value.name(), "html" | "body" | "main" | "article") {
        return false;
    }
    if value.attr("hidden").is_some() || value.attr("aria-hidden") == Some("true") {
        return true;
    }
    if value
        .attr("style")
        .is_some_and(|style| style.replace(' ', "").contains("display:none"))
    {
        return true;
    }
    let hint = format!(
        "{} {}",
        value.attr("class").unwrap_or_default(),
        value.attr("id").unwrap_or_default()
    )
    .to_ascii_lowercase();
    SKIP_HINTS.iter().any(|word| hint.contains(word)) && !contains_heading(element)
}

fn contains_heading(element: ElementRef<'_>) -> bool {
    element
        .descendants()
        .filter_map(ElementRef::wrap)
        .any(|child| child.value().name() == "h1")
}

fn walk_text(element: ElementRef<'_>, out: &mut String) {
    if hidden(element) {
        return;
    }
    let name = element.value().name();
    if name == "tr" {
        let cells: Vec<String> = element
            .children()
            .filter_map(ElementRef::wrap)
            .filter(|cell| matches!(cell.value().name(), "td" | "th"))
            .map(|cell| collapse(&inline_text(cell)))
            .filter(|cell| !cell.is_empty())
            .collect();
        out.push('\n');
        out.push_str(&if cells.len() == 2 {
            format!("{}: {}", cells[0].trim_end_matches(':'), cells[1])
        } else {
            cells.join(" | ")
        });
        out.push('\n');
        return;
    }
    let block = BLOCK_TAGS.contains(&name);
    if block && name != "dd" {
        out.push('\n');
    }
    for child in element.children() {
        match child.value() {
            Node::Text(text) => out.push_str(text),
            Node::Element(_) => {
                if let Some(child) = ElementRef::wrap(child) {
                    walk_text(child, out);
                }
            }
            _ => {}
        }
    }
    if name == "dt" {
        let trimmed = out.trim_end().trim_end_matches(':').len();
        out.truncate(trimmed);
        out.push_str(": ");
    } else if block {
        out.push('\n');
    }
}

fn inline_text(element: ElementRef<'_>) -> String {
    let mut out = String::new();
    for node in element.descendants() {
        if let Node::Text(text) = node.value() {
            let hidden = node
                .ancestors()
                .filter_map(ElementRef::wrap)
                .any(|ancestor| matches!(ancestor.value().name(), "script" | "style"));
            if !hidden {
                out.push_str(text);
            }
        }
    }
    out
}

pub fn collapse(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    let mut space = false;
    for c in value.chars() {
        if c.is_whitespace() || c == '\u{a0}' || c == '\u{200b}' {
            space = true;
        } else {
            if space && !out.is_empty() {
                out.push(' ');
            }
            space = false;
            out.push(c);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn size_variants_share_an_identity() {
        assert_eq!(
            image_identity("https://x.test/wp-content/a/air-300x300.jpg?v=1"),
            image_identity("https://x.test/wp-content/a/air-1024x768.jpg")
        );
        assert_ne!(
            image_identity("https://x.test/a/one.jpg"),
            image_identity("https://x.test/a/two.jpg")
        );
    }

    #[test]
    fn microdata_and_json_ld_become_nodes() {
        let facts = collect(
            r#"<html><body>
            <div itemscope itemtype="https://schema.org/Product">
              <h1 itemprop="name">Air</h1>
              <span itemprop="brand" itemscope itemtype="https://schema.org/Brand"><meta itemprop="name" content="Drops"></span>
            </div>
            <script type="application/ld+json">{"@type":"HowTo","name":"Hat
            pattern"}</script></body></html>"#,
            None,
        );
        let product = facts.nodes_of(&["Product"]).next().expect("product");
        assert_eq!(product["name"], "Air");
        assert_eq!(product["brand"]["name"], "Drops");
        assert!(facts.nodes_of(&["HowTo"]).next().is_some());
    }

    #[test]
    fn table_and_definition_rows_become_label_lines() {
        let facts = collect(
            "<body><table><tr><th>Lauflänge</th><td>125 m / 50 g</td></tr></table>\
             <dl><dt>Nadelstärke</dt><dd>4 mm</dd></dl><nav>Menu</nav></body>",
            None,
        );
        assert!(facts.text.contains("Lauflänge: 125 m / 50 g"));
        assert!(facts.text.contains("Nadelstärke: 4 mm"));
        assert!(!facts.text.contains("Menu"));
    }

    #[test]
    fn logos_and_tiny_images_are_skipped() {
        let base = Url::parse("https://shop.test/p/1").unwrap();
        let facts = collect(
            r#"<body><main><img src="/img/logo.png"><img src="/i/tiny.jpg" width="40" height="40">
            <img src="/i/yarn-front.jpg" width="800" height="800"></main></body>"#,
            Some(&base),
        );
        let urls: Vec<_> = facts.images.iter().map(|i| i.url.as_str()).collect();
        assert_eq!(urls, ["https://shop.test/i/yarn-front.jpg"]);
    }
}
