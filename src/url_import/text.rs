//! Pattern matching on visible page text for German and English yarn and pattern pages.
//!
//! Everything here is a best-effort guess and is marked `FieldSource::Text` by the callers.

use std::sync::LazyLock;

use regex::Regex;

macro_rules! re {
    ($name:ident, $pattern:expr) => {
        static $name: LazyLock<Regex> =
            LazyLock::new(|| Regex::new($pattern).expect("static regex is valid"));
    };
}

re!(
    PERCENT_FIRST,
    r"(?i)(\d{1,3}(?:[.,]\d+)?)\s*%\s*([\p{L}][\p{L}\-]*(?:\s[\p{L}][\p{L}\-]*){0,2})"
);
re!(
    PERCENT_LAST,
    r"(?i)([\p{L}][\p{L}\-]*(?:\s[\p{L}][\p{L}\-]*){0,2})\s*:?\s+(\d{1,3}(?:[.,]\d+)?)\s*%"
);
re!(
    M_PER_G,
    r"(?i)(\d{2,5})\s*(?:m|meter|metres|meters|mtr)\b\.?\s*(?:/|pro|per|auf|je|=|bei|on)\s*(\d{2,4})\s*(?:g|gr|gramm|grams)\b"
);
re!(
    G_PER_M,
    r"(?i)(\d{2,4})\s*(?:g|gr|gramm|grams)\b\.?\s*(?:/|=|pro|per|ca\.?|approx\.?)?\s*(\d{2,5})\s*(?:m|meter|metres|meters|mtr)\b"
);
re!(
    YARDS_METERS,
    r"(?i)(\d{2,5})\s*(?:yds?|yards)\b[^\d\n]{0,6}\(?\s*(\d{2,5})\s*m\b"
);
re!(
    LENGTH_LABELLED,
    r"(?i)(?:lauflänge|lauflaenge|lauflange|länge|yardage|length|meterage|lauflänge ca\.?)[^\d\n]{0,16}(\d{2,5})\s*(?:m|meter|metres|meters)\b"
);
re!(
    GRAMS_LABELLED,
    r"(?i)(?:gewicht|weight|knäuel|knaeuel|skein|ball|inhalt|net weight|nettogewicht|menge)[^\d\n]{0,20}(\d{2,4})\s*(?:g|gr|gramm|grams)\b"
);
re!(
    NEEDLE_LABELLED,
    r"(?i)(?:nadelstärke|nadelstaerke|nadelstarke|nadeln|nadel|empfohlene[nr]?\s+nadel\w*|needles?|needle size|recommended needles?|stricknadeln?|rundstricknadeln?|nadelspiel|häkelnadel|haekelnadel)[^\d\n]{0,25}(\d{1,2}(?:[.,]\d)?(?:\s*(?:-|–|—|bis|to)\s*\d{1,2}(?:[.,]\d)?)?)\s*mm"
);
re!(
    GAUGE_LINE,
    r"(?i)^(?:maschenprobe|gauge|tension|stitch gauge|strickprobe)\b[^\n:]{0,20}[:\s]+(.{4,200})$"
);
re!(
    SLASH_GRAMS,
    r"(?i)(?:/|\bper\b|\bpro\b)\s*(\d{2,4})\s*(?:g|gr|gramm|grams)\b"
);
re!(NUMBER, r"\d+(?:[.,]\d+)?");

const FIBER_STOP_WORDS: &[&str] = &[
    "pflege",
    "waschen",
    "care",
    "lauflänge",
    "gewicht",
    "länge",
    "nadel",
    "nadeln",
    "farbe",
    "color",
    "colour",
    "knäuel",
    "wash",
    "weight",
    "yardage",
    "needle",
    "maschenprobe",
    "gauge",
    "und",
    "and",
    "mit",
    "with",
    "or",
    "oder",
    "für",
    "for",
    "preis",
    "price",
    "free",
    "download",
    "off",
    "rabatt",
    "gratis",
    "sale",
    "discount",
    "pdf",
    "new",
    "neu",
    "kostenlos",
];

pub fn lines(text: &str) -> impl Iterator<Item = &str> {
    text.lines().map(str::trim).filter(|line| !line.is_empty())
}

/// Find `label: value` on one line for any synonym.
pub fn labelled(text: &str, labels: &[&str]) -> Option<String> {
    labelled_inner(text, labels, false)
}

/// Like [`labelled`] but also accepts the label alone on a line with the value on the next one.
/// Only use this where the value is validated afterwards.
pub fn labelled_loose(text: &str, labels: &[&str]) -> Option<String> {
    labelled_inner(text, labels, true)
}

fn labelled_inner(text: &str, labels: &[&str], next_line: bool) -> Option<String> {
    let mut iterator = lines(text).peekable();
    while let Some(line) = iterator.next() {
        let (label, value) = match line.split_once(':') {
            Some((label, value)) => (label, value.trim()),
            None => (line, ""),
        };
        let label = label.trim().trim_end_matches(':').to_lowercase();
        if label.len() > 40 || !labels.iter().any(|wanted| label == *wanted) {
            continue;
        }
        let value = if value.is_empty() {
            match iterator.peek() {
                Some(next) if next_line && plausible_next_line(next) => (*next).to_owned(),
                _ => continue,
            }
        } else {
            value.to_owned()
        };
        if value.len() <= 240 && !value.is_empty() {
            return Some(value);
        }
    }
    None
}

fn plausible_next_line(line: &str) -> bool {
    line.len() <= 120 && !line.ends_with('?') && !line.ends_with(':') && !line.contains("guest")
}

fn number(value: &str) -> Option<f64> {
    value.replace(',', ".").parse().ok()
}

/// Blocks introduced by a header line (`SIZES:`, `Materials:`, `NEEDLES`), the layout used by DROPS
/// and many blog patterns. Returns lowercase header and the lines below it, up to the next header,
/// a `-----` rule or twelve lines.
pub fn sections(text: &str) -> Vec<(String, Vec<String>)> {
    fn header(line: &str) -> Option<String> {
        let core = line.trim().trim_end_matches(':').trim();
        let chars = core.chars().count();
        if !(3..=32).contains(&chars)
            || core.contains(':')
            || core.chars().any(|c| c.is_ascii_digit())
        {
            return None;
        }
        let longest_word = core
            .split_whitespace()
            .map(|word| word.chars().count())
            .max()
            .unwrap_or(0);
        let all_caps = longest_word >= 4
            && core.chars().all(|c| {
                c.is_whitespace()
                    || c == '&'
                    || c == '/'
                    || (c.is_alphabetic() && !c.is_lowercase())
            });
        let has_colon = line.trim().ends_with(':');
        (all_caps || has_colon).then(|| core.to_lowercase())
    }
    let mut out: Vec<(String, Vec<String>)> = Vec::new();
    let mut current: Option<(String, Vec<String>)> = None;
    for line in lines(text) {
        if line.starts_with("-----") {
            if let Some(done) = current.take() {
                out.push(done);
            }
            continue;
        }
        if let Some(name) = header(line) {
            if let Some(done) = current.take() {
                out.push(done);
            }
            current = Some((name, Vec::new()));
        } else if let Some((_, body)) = current.as_mut()
            && body.len() < 12
        {
            body.push(line.to_owned());
        }
    }
    if let Some(done) = current {
        out.push(done);
    }
    out
}

/// First section whose header is one of `names`, with a non-empty body.
pub fn section(text: &str, names: &[&str]) -> Option<Vec<String>> {
    sections(text)
        .into_iter()
        .find(|(name, body)| !body.is_empty() && names.contains(&name.as_str()))
        .map(|(_, body)| body)
}

/// All distinct millimetre sizes mentioned in `lines`, like "9 mm, 6 mm".
pub fn mm_sizes(lines: &[String]) -> Option<String> {
    static MM: LazyLock<Regex> = LazyLock::new(|| {
        Regex::new(r"(?i)(\d{1,2}(?:[.,]\d)?)\s*mm\b").expect("static regex is valid")
    });
    let mut found: Vec<String> = Vec::new();
    for line in lines {
        for capture in MM.captures_iter(line) {
            let size = format!("{} mm", capture[1].replace(',', "."));
            if !found.contains(&size) {
                found.push(size);
            }
        }
    }
    (!found.is_empty()).then(|| found.join(", "))
}

/// "50% Wolle, 50% Baumwolle" from a line whose percentages add up to roughly 100.
pub fn fiber_content(text: &str) -> Option<String> {
    for line in lines(text).filter(|line| line.len() < 400) {
        let mut parts: Vec<(f64, String)> = PERCENT_FIRST
            .captures_iter(line)
            .filter_map(|c| Some((number(&c[1])?, clean_fiber(&c[2])?)))
            .collect();
        let mut total: f64 = parts.iter().map(|part| part.0).sum();
        if !(97.0..=103.0).contains(&total) {
            parts = PERCENT_LAST
                .captures_iter(line)
                .filter_map(|c| Some((number(&c[2])?, clean_fiber(&c[1])?)))
                .collect();
            total = parts.iter().map(|part| part.0).sum();
        }
        if parts.is_empty() || !(97.0..=103.0).contains(&total) {
            continue;
        }
        return Some(
            parts
                .iter()
                .map(|(percent, name)| format!("{}% {}", format_percent(*percent), name))
                .collect::<Vec<_>>()
                .join(", "),
        );
    }
    None
}

fn format_percent(value: f64) -> String {
    if (value - value.round()).abs() < 0.05 {
        format!("{}", value.round() as i64)
    } else {
        format!("{value:.1}")
    }
}

fn clean_fiber(raw: &str) -> Option<String> {
    let mut words = Vec::new();
    for word in raw.split_whitespace() {
        if FIBER_STOP_WORDS.contains(&word.to_lowercase().as_str()) {
            break;
        }
        words.push(word);
    }
    let name = words.join(" ");
    (name.chars().count() >= 3).then_some(name)
}

/// `(grams, meters)` of one skein.
pub fn skein_size(text: &str) -> (Option<i64>, Option<i64>) {
    let mut grams = None;
    let mut meters = None;
    if let Some(c) = M_PER_G.captures(text) {
        meters = c[1].parse().ok();
        grams = c[2].parse().ok();
    } else if let Some(c) = G_PER_M.captures(text) {
        grams = c[1].parse().ok();
        meters = c[2].parse().ok();
    } else if let Some(c) = YARDS_METERS.captures(text) {
        meters = c[2].parse().ok();
    }
    if meters.is_none() {
        meters = LENGTH_LABELLED
            .captures(text)
            .and_then(|c| c[1].parse().ok());
    }
    if grams.is_none() && meters.is_some() {
        grams = SLASH_GRAMS.captures(text).and_then(|c| c[1].parse().ok());
    }
    if grams.is_none() {
        grams = GRAMS_LABELLED
            .captures(text)
            .and_then(|c| c[1].parse().ok());
    }
    (
        grams.filter(|g| (5..=2000).contains(g)),
        meters.filter(|m| (10..=10_000).contains(m)),
    )
}

/// "3.5-4 mm" style needle size.
pub fn needle_size(text: &str) -> Option<String> {
    let captures = NEEDLE_LABELLED.captures(text)?;
    let raw = captures[1].replace(',', ".");
    let normalised = raw
        .replace(" bis ", "-")
        .replace(" to ", "-")
        .replace(['–', '—'], "-")
        .split_whitespace()
        .collect::<String>();
    Some(format!("{normalised} mm"))
}

pub fn gauge(text: &str) -> Option<String> {
    lines(text).find_map(|line| {
        let value = GAUGE_LINE
            .captures(line)?
            .get(1)?
            .as_str()
            .trim()
            .to_owned();
        value.chars().any(|c| c.is_ascii_digit()).then_some(value)
    })
}

pub fn sizes(text: &str) -> Option<String> {
    labelled_loose(
        text,
        &[
            "größen",
            "grössen",
            "groessen",
            "größe",
            "grösse",
            "sizes",
            "size",
            "size range",
            "sizes available",
            "available sizes",
        ],
    )
    .filter(|value| value.len() <= 120 && !value.to_lowercase().starts_with("available"))
}

pub fn difficulty(text: &str) -> Option<String> {
    labelled_loose(
        text,
        &[
            "schwierigkeitsgrad",
            "schwierigkeit",
            "difficulty",
            "skill level",
            "level",
            "experience level",
            "erfahrung",
        ],
    )
    .filter(|value| value.len() <= 60)
}

/// Canonical yarn weight category from free text.
pub fn weight_category(text: &str) -> Option<String> {
    let lower = format!(
        " {} ",
        text.to_lowercase().replace(['-', '/', '(', ')', ','], " ")
    );
    let has = |needle: &str| lower.contains(&format!(" {needle} "));
    let table: &[(&[&str], &str)] = &[
        (
            &["super bulky", "super chunky", "jumbo", "superbulky"],
            "Super Bulky",
        ),
        (
            &[
                "lace",
                "cobweb",
                "laceweight",
                "lace weight",
                "2 ply",
                "2fach",
                "2 fach",
            ],
            "Lace",
        ),
        (
            &[
                "fingering",
                "sock",
                "sockenwolle",
                "4 ply",
                "4fach",
                "4 fach",
                "sockwool",
                "sock yarn",
            ],
            "Fingering",
        ),
        (&["sport", "5 ply", "5fach"], "Sport"),
        (
            &[
                "dk",
                "double knit",
                "light worsted",
                "8 ply",
                "8fach",
                "8 fach",
            ],
            "DK",
        ),
        (&["aran", "10 ply", "10fach"], "Aran"),
        (&["worsted"], "Worsted"),
        (&["chunky", "bulky", "12 ply", "14 ply"], "Bulky"),
    ];
    table
        .iter()
        .find(|(keys, _)| keys.iter().any(|key| has(key)))
        .map(|(_, name)| (*name).to_owned())
}

/// Every numeric token of `value`, normalised (`3,5` becomes `3.5`).
pub fn numbers(value: &str) -> Vec<String> {
    NUMBER
        .find_iter(value)
        .map(|m| {
            let token = m.as_str().replace(',', ".");
            if token.contains('.') {
                token.trim_end_matches('0').trim_end_matches('.').to_owned()
            } else {
                token.trim_start_matches('0').to_owned()
            }
        })
        .map(|token| if token.is_empty() { "0".into() } else { token })
        .collect()
}

/// True when every number in `value` also appears in `source`. Used to reject model output that
/// invents quantities.
pub fn numbers_grounded(value: &str, source: &str) -> bool {
    let haystack: std::collections::HashSet<String> = numbers(source).into_iter().collect();
    numbers(value).iter().all(|token| haystack.contains(token))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fiber_in_german_and_english() {
        assert_eq!(
            fiber_content("Zusammensetzung: 75% Schurwolle (Merino), 25% Polyamid").as_deref(),
            Some("75% Schurwolle, 25% Polyamid")
        );
        assert_eq!(
            fiber_content("Fiber content\n50 % Wool 50 % Cotton Pflege: 30 Grad").as_deref(),
            Some("50% Wool, 50% Cotton")
        );
        assert_eq!(
            fiber_content("Baumwolle 60%, Viskose 40%").as_deref(),
            Some("60% Baumwolle, 40% Viskose")
        );
        assert_eq!(fiber_content("Rabatt 20% auf alles"), None);
    }

    #[test]
    fn skein_size_variants() {
        assert_eq!(skein_size("Lauflänge: 125 m / 50 g"), (Some(50), Some(125)));
        assert_eq!(skein_size("50 g = 200 m"), (Some(50), Some(200)));
        assert_eq!(skein_size("Yardage 220 yds (200 m)"), (None, Some(200)));
        assert_eq!(
            skein_size("210 yds (192 m) / 100 g"),
            (Some(100), Some(192))
        );
        assert_eq!(
            skein_size("Gewicht: 100 g\nLauflänge: 400 m"),
            (Some(100), Some(400))
        );
        assert_eq!(skein_size("nothing here"), (None, None));
    }

    #[test]
    fn needle_gauge_and_friends() {
        assert_eq!(
            needle_size("Empfohlene Nadelstärke: 3,5 - 4 mm").as_deref(),
            Some("3.5-4 mm")
        );
        assert_eq!(needle_size("Needles: 4mm").as_deref(), Some("4 mm"));
        assert_eq!(
            gauge("Material\nMaschenprobe: 22 Maschen x 30 Reihen = 10 x 10 cm").as_deref(),
            Some("22 Maschen x 30 Reihen = 10 x 10 cm")
        );
        assert_eq!(
            sizes("Größen: S - M - L - XL").as_deref(),
            Some("S - M - L - XL")
        );
        assert_eq!(difficulty("Difficulty: Easy").as_deref(), Some("Easy"));
    }

    #[test]
    fn sections_follow_header_lines() {
        let text = "DROPS 244-25\nSIZES:\nS - M - L\nNEEDLES:\nDROPS CIRCULAR NEEDLE SIZE 9 MM: Length 40 cm\nDOUBLE POINTED NEEDLES SIZE 6 MM.\n-----\nMaterials:\n100 g wool";
        assert_eq!(section(text, &["sizes"]).unwrap(), ["S - M - L"]);
        let needles = section(text, &["needles"]).unwrap();
        assert_eq!(mm_sizes(&needles).as_deref(), Some("9 mm, 6 mm"));
        assert_eq!(section(text, &["materials"]).unwrap(), ["100 g wool"]);
    }

    #[test]
    fn weight_categories() {
        assert_eq!(
            weight_category("Garnstärke: Sockenwolle (4-fach)").as_deref(),
            Some("Fingering")
        );
        assert_eq!(weight_category("DK / light worsted").as_deref(), Some("DK"));
        assert_eq!(
            weight_category("super bulky").as_deref(),
            Some("Super Bulky")
        );
        assert_eq!(weight_category("blue"), None);
    }

    #[test]
    fn grounding_checks_numbers() {
        assert!(numbers_grounded(
            "75% wool, 25% nylon",
            "75 % Wolle und 25 % Nylon"
        ));
        assert!(!numbers_grounded("80% wool", "75 % Wolle"));
        assert!(numbers_grounded("3.5-4 mm", "Nadeln 3,5 bis 4 mm"));
    }

    #[test]
    fn labelled_lookup_handles_two_line_form() {
        let text = "Farbe\nSchneeweiß\nPflege: Handwäsche";
        assert_eq!(labelled(text, &["farbe"]), None);
        assert_eq!(
            labelled_loose(text, &["farbe"]).as_deref(),
            Some("Schneeweiß")
        );
        assert_eq!(labelled(text, &["pflege"]).as_deref(), Some("Handwäsche"));
    }
}
