//! Map page facts onto yarn and pattern drafts, with per-field provenance.
//!
//! Order of trust: site extractors > schema.org/microdata/OpenGraph > text heuristics. Nothing
//! here calls the network or an AI model.

use std::collections::{BTreeMap, HashSet};

use scraper::Html;
use serde_json::Value;
use url::Url;

use super::facts::{self, ImageCandidate, PageFacts, SchemaNode, collapse, image_identity};
use super::text;
use crate::import_document::{
    FieldSource, PageKind, ProjectImportDraft, StepImportDraft, YarnDetails, nonempty, resolve_url,
    safe_web_url,
};

const MAX_PROJECT_IMAGES: usize = 12;
const MAX_YARN_IMAGES: usize = 8;
const MAX_STEPS: usize = 80;
pub const MAX_PAGE_TEXT: usize = 40_000;

pub struct Extraction {
    pub project: ProjectImportDraft,
    pub yarn: Option<YarnDetails>,
    pub kind: PageKind,
    pub provenance: BTreeMap<String, FieldSource>,
    pub warnings: Vec<String>,
}

pub fn extract_html(
    html: &str,
    source_url: Option<&Url>,
    fetched_title: Option<&str>,
) -> Extraction {
    let facts = facts::collect(html, source_url);
    let mut prov = Provenance::default();
    let site = SiteContext::new(source_url, &facts);

    let props_text = property_values(&facts);
    let haystack = format!("{props_text}\n{}", facts.text);
    let page_title = clean_title(
        &facts
            .meta(&["og:title", "twitter:title"])
            .or_else(|| facts.h1.clone())
            .or_else(|| fetched_title.map(str::to_owned))
            .or_else(|| facts.title.clone())
            .unwrap_or_default(),
        facts.meta(&["og:site_name"]).as_deref(),
        source_url,
    );

    let product = pick_product(&facts, &page_title);
    let mut yarn = build_yarn(
        &facts,
        product,
        &props_text,
        &haystack,
        &page_title,
        source_url,
        &mut prov,
    );
    let mut project = build_project(&facts, &haystack, &page_title, source_url, &site, &mut prov);

    let kind = classify(
        &facts,
        &project,
        &yarn,
        product.is_some(),
        &page_title,
        source_url,
    );
    site.apply(&mut project, &mut yarn, &mut prov);

    project.extras.insert(
        "page_text".into(),
        Value::String(facts.text.chars().take(MAX_PAGE_TEXT).collect()),
    );
    let mut warnings = Vec::new();
    if facts.nodes.is_empty() {
        warnings.push("no_structured_data".to_owned());
    }
    if facts.text.chars().count() < 200 {
        warnings.push("little_page_text".to_owned());
    }
    Extraction {
        project,
        yarn: Some(yarn),
        kind,
        provenance: prov.0,
        warnings,
    }
}

// ----------------------------------------------------------------- provenance

#[derive(Default)]
struct Provenance(BTreeMap<String, FieldSource>);

impl Provenance {
    fn fill(
        &mut self,
        key: &str,
        target: &mut Option<String>,
        value: Option<String>,
        source: FieldSource,
    ) {
        if target.is_some() {
            return;
        }
        if let Some(value) = value.map(|v| collapse(&v)).filter(|v| !v.is_empty()) {
            *target = Some(value);
            self.0.insert(key.to_owned(), source);
        }
    }

    fn fill_number(
        &mut self,
        key: &str,
        target: &mut Option<i64>,
        value: Option<i64>,
        source: FieldSource,
    ) {
        if target.is_none()
            && let Some(value) = value
        {
            *target = Some(value);
            self.0.insert(key.to_owned(), source);
        }
    }

    fn mark(&mut self, key: &str, source: FieldSource) {
        self.0.insert(key.to_owned(), source);
    }
}

// ------------------------------------------------------------- schema helpers

fn string_of(value: &Value) -> Option<String> {
    match value {
        Value::String(text) => nonempty(&strip_html(text)).map(str::to_owned),
        Value::Number(number) => Some(number.to_string()),
        Value::Array(values) => values.iter().find_map(string_of),
        Value::Object(map) => map
            .get("name")
            .or_else(|| map.get("@value"))
            .or_else(|| map.get("value"))
            .or_else(|| map.get("url"))
            .and_then(string_of),
        _ => None,
    }
}

fn prop(node: &SchemaNode, keys: &[&str]) -> Option<String> {
    keys.iter()
        .find_map(|key| node.get(*key).and_then(string_of))
}

fn strings_of(value: &Value) -> Vec<String> {
    match value {
        Value::Array(values) => values.iter().flat_map(strings_of).collect(),
        other => string_of(other).into_iter().collect(),
    }
}

fn image_urls_of(value: &Value, base: Option<&Url>) -> Vec<String> {
    match value {
        Value::Array(values) => values.iter().flat_map(|v| image_urls_of(v, base)).collect(),
        Value::String(url) => resolve_url(base, url).into_iter().collect(),
        Value::Object(map) => map
            .get("url")
            .or_else(|| map.get("contentUrl"))
            .map(|v| image_urls_of(v, base))
            .unwrap_or_default(),
        _ => Vec::new(),
    }
}

pub(crate) fn strip_html(value: &str) -> String {
    if !value.contains('<') && !value.contains('&') {
        return value.trim().to_owned();
    }
    let fragment = Html::parse_fragment(value);
    let mut out = String::new();
    for text in fragment.root_element().text() {
        out.push_str(text);
        out.push(' ');
    }
    collapse(&out)
}

/// `Name: value` lines from schema.org `PropertyValue` nodes plus the raw pairs.
fn property_values(facts: &PageFacts) -> String {
    let mut pairs = Vec::new();
    for node in facts.nodes_of(&["PropertyValue"]) {
        if let (Some(name), Some(value)) = (prop(node, &["name"]), prop(node, &["value"])) {
            pairs.push((name, value));
        }
    }
    pairs
        .iter()
        .map(|(name, value)| format!("{name}: {value}"))
        .collect::<Vec<_>>()
        .join("\n")
}

fn pick_product<'a>(facts: &'a PageFacts, title: &str) -> Option<&'a SchemaNode> {
    let candidates: Vec<&SchemaNode> = facts
        .nodes_of(&[
            "Product",
            "IndividualProduct",
            "ProductGroup",
            "ProductModel",
        ])
        .collect();
    let title_lower = title.to_lowercase();
    candidates
        .iter()
        .copied()
        .find(|node| {
            prop(node, &["name"]).is_some_and(|name| {
                let name = name.to_lowercase();
                !title_lower.is_empty()
                    && (title_lower.contains(&name) || name.contains(&title_lower))
            })
        })
        .or_else(|| {
            candidates
                .iter()
                .copied()
                .find(|node| node.contains_key("offers"))
        })
        .or_else(|| candidates.first().copied())
}

fn pick_pattern_node(facts: &PageFacts) -> Option<&SchemaNode> {
    facts
        .nodes_of(&["HowTo", "Recipe"])
        .next()
        .or_else(|| {
            facts
                .nodes_of(&[
                    "CreativeWork",
                    "TechArticle",
                    "BlogPosting",
                    "Article",
                    "NewsArticle",
                ])
                .next()
        })
        .or_else(|| facts.nodes_of(&["Product"]).next())
}

/// Remove a trailing site name from a page title.
pub fn clean_title(title: &str, site_name: Option<&str>, url: Option<&Url>) -> String {
    let title = collapse(title);
    let host_stem = url
        .and_then(|url| url.host_str())
        .map(|host| {
            let host = host.trim_start_matches("www.");
            host.split('.').next().unwrap_or(host).to_lowercase()
        })
        .unwrap_or_default();
    // "Ravelry: Garnstudio DROPS Air" - a site name prefix.
    if let Some((prefix, rest)) = title.split_once(": ")
        && !rest.trim().is_empty()
        && (site_name.is_some_and(|name| prefix.eq_ignore_ascii_case(name))
            || (!host_stem.is_empty() && prefix.to_lowercase().replace(' ', "") == host_stem))
    {
        return rest.trim().to_owned();
    }
    let is_site = |part: &str| {
        let part = part.trim().to_lowercase();
        part.is_empty()
            || site_name.is_some_and(|name| part == name.to_lowercase())
            || (!host_stem.is_empty() && part.replace([' ', '-', '.'], "").contains(&host_stem))
    };
    for separator in [" | ", " – ", " — ", " - ", " :: ", " : ", " · ", " • "] {
        if title.contains(separator) {
            let parts: Vec<&str> = title.split(separator).collect();
            let kept: Vec<&str> = parts
                .iter()
                .copied()
                .filter(|part| !is_site(part))
                .collect();
            if !kept.is_empty() && kept.len() < parts.len() {
                return kept.join(separator);
            }
            if separator == " | " && parts.len() >= 2 {
                return parts[0].trim().to_owned();
            }
        }
    }
    title
}

// ----------------------------------------------------------------- yarn

fn offer_price(product: &SchemaNode) -> Option<String> {
    let offers = product.get("offers")?;
    let offer = match offers {
        Value::Array(values) => values.first()?,
        other => other,
    };
    let map = offer.as_object()?;
    let price = map
        .get("price")
        .or_else(|| map.get("lowPrice"))
        .and_then(string_of)
        .or_else(|| {
            map.get("priceSpecification")
                .and_then(Value::as_object)
                .and_then(|spec| spec.get("price"))
                .and_then(string_of)
        })?;
    let currency = map
        .get("priceCurrency")
        .and_then(string_of)
        .or_else(|| {
            map.get("priceSpecification")
                .and_then(Value::as_object)
                .and_then(|spec| spec.get("priceCurrency"))
                .and_then(string_of)
        })
        .unwrap_or_default();
    if price
        .replace(',', ".")
        .parse::<f64>()
        .is_ok_and(|value| value <= 0.0)
    {
        return None;
    }
    Some(format!("{price} {currency}").trim().to_owned())
}

fn build_yarn(
    facts: &PageFacts,
    product: Option<&SchemaNode>,
    props_text: &str,
    haystack: &str,
    page_title: &str,
    source_url: Option<&Url>,
    prov: &mut Provenance,
) -> YarnDetails {
    let mut yarn = YarnDetails::default();
    let structured = FieldSource::Structured;
    let guess = FieldSource::Text;

    prov.fill(
        "yarn.name",
        &mut yarn.name,
        product
            .and_then(|p| prop(p, &["name"]))
            .map(|n| clean_title(&n, facts.meta(&["og:site_name"]).as_deref(), source_url)),
        structured,
    );
    prov.fill(
        "yarn.name",
        &mut yarn.name,
        Some(page_title.to_owned()),
        structured,
    );

    prov.fill(
        "yarn.brand",
        &mut yarn.brand,
        product.and_then(|p| prop(p, &["brand", "manufacturer"])),
        structured,
    );
    prov.fill(
        "yarn.brand",
        &mut yarn.brand,
        facts.meta(&["product:brand", "og:brand"]),
        structured,
    );
    prov.fill(
        "yarn.brand",
        &mut yarn.brand,
        text::labelled(
            props_text,
            &["marke", "hersteller", "brand", "manufacturer"],
        ),
        structured,
    );
    prov.fill(
        "yarn.brand",
        &mut yarn.brand,
        text::labelled(haystack, &["marke", "hersteller", "brand", "manufacturer"]),
        guess,
    );

    prov.fill(
        "yarn.colorway",
        &mut yarn.colorway,
        product.and_then(|p| prop(p, &["color", "colour"])),
        structured,
    );
    prov.fill(
        "yarn.colorway",
        &mut yarn.colorway,
        text::labelled(
            haystack,
            &[
                "farbe",
                "farbname",
                "farbnummer",
                "colorway",
                "colour",
                "color",
                "farbton",
                "shade",
            ],
        ),
        guess,
    );

    prov.fill(
        "yarn.description",
        &mut yarn.description,
        product.and_then(|p| prop(p, &["description"])),
        structured,
    );
    prov.fill(
        "yarn.description",
        &mut yarn.description,
        facts.meta(&["og:description", "description", "twitter:description"]),
        structured,
    );
    if let Some(description) = yarn.description.as_mut() {
        *description = description.chars().take(1500).collect();
    }

    let material_text = product.and_then(|p| prop(p, &["material"]));
    let fiber_sources = [
        (material_text.clone(), structured),
        (
            text::labelled(
                props_text,
                &[
                    "zusammensetzung",
                    "material",
                    "materialzusammensetzung",
                    "fasern",
                    "faser",
                    "fiber content",
                    "fibre content",
                    "composition",
                    "fiber",
                    "fibre",
                ],
            ),
            structured,
        ),
        (
            text::labelled_loose(
                haystack,
                &[
                    "zusammensetzung",
                    "material",
                    "materialzusammensetzung",
                    "fasern",
                    "faser",
                    "fiber content",
                    "fibre content",
                    "composition",
                    "fiber",
                    "fibre",
                    "inhalt",
                ],
            ),
            guess,
        ),
    ];
    for (candidate, source) in fiber_sources {
        if let Some(candidate) = candidate {
            let parsed = text::fiber_content(&candidate)
                .or_else(|| (source == structured).then(|| candidate.clone()));
            prov.fill("yarn.fiberContent", &mut yarn.fiber_content, parsed, source);
        }
    }
    prov.fill(
        "yarn.fiberContent",
        &mut yarn.fiber_content,
        text::fiber_content(haystack),
        guess,
    );

    let weight_label = text::labelled_loose(
        haystack,
        &[
            "garnstärke",
            "garnstaerke",
            "garnkategorie",
            "yarn weight",
            "weight category",
            "yarn type",
            "garnart",
            "stärke",
            "thickness",
            "wollart",
            "weight",
        ],
    );
    prov.fill(
        "yarn.weightCategory",
        &mut yarn.weight_category,
        weight_label.as_deref().and_then(text::weight_category),
        guess,
    );
    prov.fill(
        "yarn.weightCategory",
        &mut yarn.weight_category,
        text::weight_category(&format!(
            "{} {}",
            yarn.name.clone().unwrap_or_default(),
            facts.keywords.join(" ")
        )),
        guess,
    );

    // Structured weight (QuantitativeValue) first.
    if let Some(weight) = product.and_then(|p| p.get("weight")) {
        let value = weight
            .get("value")
            .and_then(string_of)
            .and_then(|v| v.replace(',', ".").parse::<f64>().ok());
        let unit = weight
            .get("unitCode")
            .or_else(|| weight.get("unitText"))
            .and_then(string_of)
            .unwrap_or_default()
            .to_lowercase();
        if let Some(value) = value {
            let grams = match unit.as_str() {
                "grm" | "g" | "gram" | "grams" | "gramm" => Some(value),
                "kgm" | "kg" => Some(value * 1000.0),
                _ => None,
            };
            prov.fill_number(
                "yarn.weightGrams",
                &mut yarn.weight_grams,
                grams
                    .map(|g| g.round() as i64)
                    .filter(|g| (5..=2000).contains(g)),
                structured,
            );
        }
    }
    let (grams, meters) = text::skein_size(props_text);
    prov.fill_number(
        "yarn.weightGrams",
        &mut yarn.weight_grams,
        grams,
        structured,
    );
    prov.fill_number(
        "yarn.lengthMeters",
        &mut yarn.length_meters,
        meters,
        structured,
    );
    let (grams, meters) = text::skein_size(haystack);
    prov.fill_number("yarn.weightGrams", &mut yarn.weight_grams, grams, guess);
    prov.fill_number("yarn.lengthMeters", &mut yarn.length_meters, meters, guess);

    prov.fill(
        "yarn.recommendedNeedles",
        &mut yarn.recommended_needles,
        text::needle_size(haystack),
        guess,
    );
    prov.fill("yarn.gauge", &mut yarn.gauge, text::gauge(haystack), guess);
    prov.fill(
        "yarn.care",
        &mut yarn.care,
        text::labelled(
            haystack,
            &[
                "pflege",
                "pflegehinweise",
                "pflegehinweis",
                "waschen",
                "care",
                "care instructions",
                "washing instructions",
                "waschanleitung",
            ],
        ),
        guess,
    );

    prov.fill(
        "yarn.price",
        &mut yarn.price,
        product.and_then(offer_price),
        structured,
    );
    if yarn.price.is_none()
        && let Some(amount) = facts.meta(&["product:price:amount", "og:price:amount"])
    {
        let currency = facts
            .meta(&["product:price:currency", "og:price:currency"])
            .unwrap_or_default();
        prov.fill(
            "yarn.price",
            &mut yarn.price,
            Some(format!("{amount} {currency}")),
            structured,
        );
    }

    yarn.link = source_url
        .and_then(safe_web_url)
        .map(Url::to_string)
        .or_else(|| facts.canonical.clone());
    if yarn.link.is_some() {
        prov.mark("yarn.link", structured);
    }

    let structured_images = product
        .and_then(|p| p.get("image"))
        .map(|value| image_urls_of(value, source_url))
        .unwrap_or_default();
    yarn.image_urls = select_images(facts, structured_images, MAX_YARN_IMAGES);
    if !yarn.image_urls.is_empty() {
        prov.mark("yarn.imageUrls", structured);
    }
    yarn
}

// -------------------------------------------------------------- pattern

struct SiteContext {
    designer: Option<String>,
    brand: Option<String>,
}

impl SiteContext {
    fn new(url: Option<&Url>, _facts: &PageFacts) -> Self {
        let host = url
            .and_then(|u| u.host_str())
            .unwrap_or_default()
            .to_lowercase();
        let host = host.trim_start_matches("www.");
        if host == "garnstudio.com"
            || host.ends_with(".garnstudio.com")
            || host == "dropsdesign.com"
        {
            return Self {
                designer: Some("DROPS Design".to_owned()),
                brand: Some("DROPS Design".to_owned()),
            };
        }
        Self {
            designer: None,
            brand: None,
        }
    }

    /// Site knowledge that overrides generic guesses.
    fn apply(
        &self,
        project: &mut ProjectImportDraft,
        _yarn: &mut YarnDetails,
        prov: &mut Provenance,
    ) {
        if let Some(designer) = &self.designer
            && project.designer.as_deref() != Some(designer)
        {
            project.designer = Some(designer.clone());
            prov.mark("project.designer", FieldSource::Site);
        }
        if let Some(brand) = &self.brand {
            project.brand = Some(brand.clone());
            // "Name / DROPS 244-25 - Free knitting patterns by DROPS Design"
            if let Some(name) = project.name.as_mut()
                && let Some((head, _)) = name.split_once(" - ")
            {
                *name = head.trim().to_owned();
            }
            // Keep the magazine photos, drop shade-card and shop thumbnails.
            if project
                .image_urls
                .iter()
                .any(|url| url.contains("/drops/mag/"))
            {
                project.image_urls.retain(|url| url.contains("/drops/mag/"));
            }
        }
    }
}

fn build_project(
    facts: &PageFacts,
    haystack: &str,
    page_title: &str,
    source_url: Option<&Url>,
    _site: &SiteContext,
    prov: &mut Provenance,
) -> ProjectImportDraft {
    let mut project = ProjectImportDraft::default();
    let structured = FieldSource::Structured;
    let guess = FieldSource::Text;
    let node = pick_pattern_node(facts);

    prov.fill(
        "project.name",
        &mut project.name,
        node.and_then(|n| prop(n, &["name", "headline"]))
            .map(|n| clean_title(&n, facts.meta(&["og:site_name"]).as_deref(), source_url))
            .filter(|n| n.len() > 2),
        structured,
    );
    prov.fill(
        "project.name",
        &mut project.name,
        Some(page_title.to_owned()),
        structured,
    );

    let any_node = |keys: &[&str]| {
        facts
            .nodes_of(&[
                "HowTo",
                "Recipe",
                "Article",
                "BlogPosting",
                "TechArticle",
                "NewsArticle",
                "CreativeWork",
            ])
            .find_map(|n| prop(n, keys))
    };
    prov.fill(
        "project.designer",
        &mut project.designer,
        any_node(&["author", "creator"]),
        structured,
    );
    prov.fill(
        "project.designer",
        &mut project.designer,
        node.and_then(|n| prop(n, &["brand", "publisher"])),
        structured,
    );
    prov.fill(
        "project.designer",
        &mut project.designer,
        facts.meta(&["author", "article:author", "twitter:creator"]),
        structured,
    );
    prov.fill(
        "project.designer",
        &mut project.designer,
        text::labelled(
            haystack,
            &[
                "designer",
                "design",
                "entwurf",
                "autor",
                "author",
                "von",
                "by",
                "anleitung von",
                "pattern by",
                "designerin",
            ],
        ),
        guess,
    );

    prov.fill(
        "project.description",
        &mut project.description,
        node.and_then(|n| prop(n, &["description", "abstract"])),
        structured,
    );
    prov.fill(
        "project.description",
        &mut project.description,
        facts.meta(&["og:description", "description", "twitter:description"]),
        structured,
    );
    if let Some(description) = project.description.as_mut() {
        *description = description.chars().take(2000).collect();
    }

    prov.fill(
        "project.category",
        &mut project.category,
        any_node(&["recipeCategory", "articleSection", "category"]),
        structured,
    );

    let steps = node
        .map(|n| instruction_steps(n, source_url))
        .unwrap_or_default();
    if !steps.is_empty() {
        project.steps = steps;
        prov.mark("project.steps", structured);
    }

    let supplies: Vec<String> = node
        .map(|n| {
            ["recipeIngredient", "supply", "material"]
                .iter()
                .filter_map(|key| n.get(*key))
                .flat_map(strings_of)
                .collect()
        })
        .unwrap_or_default();
    if !supplies.is_empty() {
        project.other_materials = Some(supplies.join("\n"));
        prov.mark("project.otherMaterials", structured);
    }
    prov.fill(
        "project.otherMaterials",
        &mut project.other_materials,
        text::labelled(
            haystack,
            &[
                "material",
                "materialien",
                "materials",
                "materialangaben",
                "zubehör",
                "you will need",
                "notions",
                "sonstiges material",
            ],
        ),
        guess,
    );

    // Header-and-body layouts (DROPS, many blogs).
    let sized = |names: &[&str]| text::section(haystack, names);
    if let Some(body) = sized(&["sizes", "size", "größen", "größe", "grössen", "grösse"]) {
        prov.fill(
            "project.sizes",
            &mut project.sizes,
            Some(body.join(", ")).filter(|v| v.len() <= 120),
            guess,
        );
    }
    if let Some(body) = sized(&[
        "needles",
        "nadeln",
        "stricknadeln",
        "needles & hooks",
        "hooks",
        "rundstricknadeln",
    ]) {
        prov.fill(
            "project.needles",
            &mut project.needles,
            text::mm_sizes(&body),
            guess,
        );
    }
    if let Some(body) = sized(&[
        "knitting tension",
        "tension",
        "gauge",
        "maschenprobe",
        "strickprobe",
        "stitch gauge",
        "knitting gauge",
    ]) {
        prov.fill(
            "project.stitchSample",
            &mut project.stitch_sample,
            body.first().cloned(),
            guess,
        );
    }
    if let Some(body) = sized(&[
        "yarn",
        "garn",
        "materials",
        "material",
        "materialien",
        "you will need",
    ]) {
        prov.fill(
            "project.yarn",
            &mut project.yarn,
            body.first().cloned().filter(|v| v.len() <= 160),
            guess,
        );
        if body.len() > 1 {
            prov.fill(
                "project.otherMaterials",
                &mut project.other_materials,
                Some(body.join("\n")).map(|v| v.chars().take(800).collect()),
                guess,
            );
        }
    }
    let tool_needles = node
        .and_then(|n| n.get("tool"))
        .map(strings_of)
        .unwrap_or_default()
        .into_iter()
        .find(|tool| {
            let lower = tool.to_lowercase();
            lower.contains("nadel") || lower.contains("needle")
        });
    prov.fill(
        "project.needles",
        &mut project.needles,
        tool_needles,
        structured,
    );
    prov.fill(
        "project.needles",
        &mut project.needles,
        text::needle_size(haystack),
        guess,
    );
    prov.fill(
        "project.stitchSample",
        &mut project.stitch_sample,
        text::gauge(haystack),
        guess,
    );
    prov.fill(
        "project.yarn",
        &mut project.yarn,
        text::labelled(
            haystack,
            &[
                "garn",
                "yarn",
                "wolle",
                "garnempfehlung",
                "empfohlenes garn",
                "recommended yarn",
                "suggested yarn",
                "yarn used",
                "material garn",
                "garnqualität",
            ],
        ),
        guess,
    );
    prov.fill(
        "project.sizes",
        &mut project.sizes,
        text::sizes(haystack),
        guess,
    );
    prov.fill(
        "project.difficulty",
        &mut project.difficulty,
        text::difficulty(haystack),
        guess,
    );
    prov.fill(
        "project.fiberContent",
        &mut project.fiber_content,
        text::fiber_content(haystack),
        guess,
    );
    prov.fill(
        "project.weightCategory",
        &mut project.weight_category,
        text::weight_category(&project.yarn.clone().unwrap_or_default()),
        guess,
    );

    // Pattern exports that name their yarns (`yarn` / `yarns` on the Recipe or HowTo node).
    if let Some(node) = node {
        for key in ["yarn", "yarns"] {
            let Some(value) = node.get(key) else { continue };
            let items: Vec<&Value> = match value {
                Value::Array(values) => values.iter().collect(),
                other => vec![other],
            };
            for item in items {
                let Some(fields) = item.as_object() else {
                    if let Some(name) = string_of(item) {
                        project.yarns.push(crate::import_document::YarnImportDraft {
                            name: Some(name),
                            ..Default::default()
                        });
                    }
                    continue;
                };
                let text = |key: &str| fields.get(key).and_then(string_of);
                let yarn = crate::import_document::YarnImportDraft {
                    name: text("name"),
                    brand: fields.get("brand").and_then(string_of),
                    colorway: text("colorway"),
                    weight: text("weight"),
                    length: text("length"),
                    weight_category: text("weightCategory").or_else(|| text("weight_category")),
                    fiber_content: text("fiberContent").or_else(|| text("fiber_content")),
                    link: text("url")
                        .or_else(|| text("link"))
                        .and_then(|url| resolve_url(source_url, &url)),
                    image_url: text("image").and_then(|url| resolve_url(source_url, &url)),
                };
                if yarn.name.is_some() || yarn.brand.is_some() {
                    project.yarns.push(yarn);
                }
            }
        }
    }
    project.tags = facts.keywords.clone();
    project.link = facts
        .canonical
        .clone()
        .or_else(|| source_url.and_then(safe_web_url).map(Url::to_string));
    project.brand = facts.meta(&["og:site_name", "application-name"]);

    let structured_images = node
        .and_then(|n| n.get("image"))
        .map(|value| image_urls_of(value, source_url))
        .unwrap_or_default();
    project.image_urls = select_images(facts, structured_images, MAX_PROJECT_IMAGES);
    if !project.image_urls.is_empty() {
        prov.mark("project.imageUrls", structured);
    }
    project
}

fn instruction_steps(node: &SchemaNode, base: Option<&Url>) -> Vec<StepImportDraft> {
    let Some(raw) = node.get("recipeInstructions").or_else(|| node.get("step")) else {
        return Vec::new();
    };
    let mut flat: Vec<(Option<String>, Option<String>, Vec<String>)> = Vec::new();
    flatten_steps(raw, base, &mut flat);
    flat.into_iter()
        .filter(|(title, description, _)| title.is_some() || description.is_some())
        .take(MAX_STEPS)
        .enumerate()
        .map(|(index, (title, description, images))| StepImportDraft {
            step_number: index + 1,
            title,
            description,
            images,
        })
        .collect()
}

fn flatten_steps(
    value: &Value,
    base: Option<&Url>,
    out: &mut Vec<(Option<String>, Option<String>, Vec<String>)>,
) {
    match value {
        Value::Array(values) => values.iter().for_each(|v| flatten_steps(v, base, out)),
        Value::String(text) => {
            let text = strip_html(text);
            let parts: Vec<&str> = text
                .split('\n')
                .map(str::trim)
                .filter(|p| !p.is_empty())
                .collect();
            if parts.is_empty() {
                return;
            }
            for part in parts {
                out.push((None, Some(part.to_owned()), Vec::new()));
            }
        }
        Value::Object(map) => {
            if let Some(items) = map.get("itemListElement") {
                flatten_steps(items, base, out);
                return;
            }
            let title = map.get("name").and_then(string_of);
            let description = map
                .get("text")
                .or_else(|| map.get("description"))
                .and_then(string_of)
                .filter(|d| Some(d) != title.as_ref());
            let images = map
                .get("image")
                .map(|v| image_urls_of(v, base))
                .unwrap_or_default();
            out.push((title, description, images));
        }
        _ => {}
    }
}

// --------------------------------------------------------------- images

fn select_images(facts: &PageFacts, structured: Vec<String>, limit: usize) -> Vec<String> {
    let mut all: Vec<ImageCandidate> = structured
        .into_iter()
        .map(|url| ImageCandidate { url, score: 90 })
        .chain(facts.images.iter().cloned())
        .collect();
    all.sort_by_key(|candidate| std::cmp::Reverse(candidate.score));
    let mut seen = HashSet::new();
    let mut out = Vec::new();
    for candidate in all {
        if candidate.score < 20 {
            continue;
        }
        if seen.insert(image_identity(&candidate.url)) {
            out.push(candidate.url);
        }
        if out.len() >= limit {
            break;
        }
    }
    out
}

// ------------------------------------------------------------ classify

fn classify(
    facts: &PageFacts,
    project: &ProjectImportDraft,
    yarn: &YarnDetails,
    has_product: bool,
    title: &str,
    url: Option<&Url>,
) -> PageKind {
    let hay = format!(
        "{} {} {} {}",
        title,
        url.map(|u| u.path().replace(['-', '_', '/'], " "))
            .unwrap_or_default(),
        facts.keywords.join(" "),
        facts.meta(&["og:type"]).unwrap_or_default()
    )
    .to_lowercase();
    let mut pattern: i32 = 0;
    let mut yarn_score: i32 = 0;
    if facts.nodes_of(&["HowTo", "Recipe"]).next().is_some() {
        pattern += 3;
    }
    if !project.steps.is_empty() {
        pattern += 2;
    }
    if project.stitch_sample.is_some() && project.needles.is_some() {
        pattern += 2;
    }
    if project.sizes.is_some() || project.difficulty.is_some() {
        pattern += 2;
    }
    for word in [
        "anleitung",
        "pattern",
        "muster",
        "tutorial",
        "strickanleitung",
        "häkelanleitung",
        "schnittmuster",
        "modell",
        "design",
    ] {
        if hay.contains(word) {
            pattern += 3;
            break;
        }
    }
    let path = url.map(|u| u.path().to_lowercase()).unwrap_or_default();
    let segments: Vec<&str> = path.split('/').filter(|s| !s.is_empty()).collect();
    if segments.iter().any(|s| {
        matches!(
            *s,
            "pattern"
                | "patterns"
                | "anleitung"
                | "anleitungen"
                | "muster"
                | "pattern.php"
                | "tutorials"
        )
    }) {
        pattern += 4;
    }
    if segments.iter().any(|s| {
        matches!(
            *s,
            "yarn" | "yarns" | "garn" | "garne" | "wolle" | "wool" | "yarn.php"
        )
    }) {
        yarn_score += 4;
    }
    if hay.contains("pattern by") || hay.contains("anleitung von") {
        pattern += 3;
    }
    let lower_text = facts.text.to_lowercase();
    for cue in [
        "free download",
        "free pattern",
        "pattern download",
        "instant download",
        "kostenlose anleitung",
        "gratis anleitung",
        "kostenloses muster",
    ] {
        if lower_text.contains(cue) {
            pattern += 3;
            break;
        }
    }
    if has_product {
        yarn_score += 1;
    }
    if yarn.length_meters.is_some() {
        yarn_score += 2;
    }
    if yarn.fiber_content.is_some() {
        yarn_score += 1;
    }
    if yarn.price.is_some() {
        yarn_score += 1;
    }
    for word in [
        "garn",
        "yarn",
        "wolle",
        "wool",
        "knäuel",
        "skein",
        "merino",
        "sockenwolle",
        "alpaka",
        "alpaca",
    ] {
        if hay.contains(word) {
            yarn_score += 2;
            break;
        }
    }
    match pattern.cmp(&yarn_score) {
        std::cmp::Ordering::Greater => PageKind::Pattern,
        std::cmp::Ordering::Less => PageKind::Yarn,
        std::cmp::Ordering::Equal if pattern == 0 => PageKind::Unknown,
        std::cmp::Ordering::Equal => {
            if has_product {
                PageKind::Yarn
            } else {
                PageKind::Pattern
            }
        }
    }
}

/// Cheap text heuristics for documents that are not HTML (PDF, plain text, Markdown).
pub fn enrich_from_text(draft: &mut crate::import_document::ImportDocumentDraft, text_input: &str) {
    let mut prov = Provenance(std::mem::take(&mut draft.provenance));
    let guess = FieldSource::Text;
    let project = &mut draft.project;
    prov.fill(
        "project.needles",
        &mut project.needles,
        text::needle_size(text_input),
        guess,
    );
    prov.fill(
        "project.stitchSample",
        &mut project.stitch_sample,
        text::gauge(text_input),
        guess,
    );
    prov.fill(
        "project.sizes",
        &mut project.sizes,
        text::sizes(text_input),
        guess,
    );
    prov.fill(
        "project.difficulty",
        &mut project.difficulty,
        text::difficulty(text_input),
        guess,
    );
    prov.fill(
        "project.yarn",
        &mut project.yarn,
        text::labelled(text_input, &["garn", "yarn", "wolle", "material"]),
        guess,
    );
    prov.fill(
        "project.otherMaterials",
        &mut project.other_materials,
        text::labelled(text_input, &["materialien", "materials", "zubehör"]),
        guess,
    );
    draft.provenance = prov.0;
    if draft.page_kind == PageKind::Unknown {
        draft.page_kind = PageKind::Pattern;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn titles_lose_the_site_name() {
        let url = Url::parse("https://www.wollplatz.de/x").unwrap();
        assert_eq!(
            clean_title("Drops Air | Wollplatz", None, Some(&url)),
            "Drops Air"
        );
        assert_eq!(
            clean_title("Hitchhiker pattern by Martina Behm", Some("Ravelry"), None),
            "Hitchhiker pattern by Martina Behm"
        );
        assert_eq!(
            clean_title("Merino Soft - Lana Grossa", Some("Lana Grossa"), None),
            "Merino Soft"
        );
    }
}
