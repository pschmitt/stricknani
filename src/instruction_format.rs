//! Readable step instructions for imports. Pattern pages often run the rounds or rows of a step
//! together in one paragraph ("1. RUNDE: … 2.-4. RUNDE: … 5. RUNDE: …"). When a description has
//! at least two such labels and no line structure of its own, each round or row becomes a Markdown
//! list item with its label in bold; the words and numbers stay exactly as they were.

use std::sync::LazyLock;

use regex::Regex;

/// "1. RUNDE:", "2.-4. Runde:", "10.–11. REIHE:", "Row 3:", "Rows 4-6:", "Rnd 2:", "Runde 5:".
static LABEL: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(
        r"(?ix)
        \b
        (?P<label>
            \d{1,3}\.(?:\s*[-–]\s*\d{1,3}\.)?\s*
                (?:runden|runde|reihen|reihe|rounds|round|rows|row|rnds|rnd)
          | (?:runden|runde|reihen|reihe|rounds|round|rows|row|rnds|rnd)\.?\s+
                \d{1,3}(?:\s*[-–]\s*\d{1,3})?
        )
        \s*:",
    )
    .expect("valid instruction label pattern")
});

/// The minimum number of labels before a paragraph is split into a list.
const MIN_LABELS: usize = 2;

/// [text] with run-together rounds or rows as a Markdown list, or unchanged.
pub fn format_instructions(text: &str) -> String {
    let trimmed = text.trim();
    // Text that already has its own lines or list structure is left alone.
    if trimmed.contains('\n') {
        return text.to_owned();
    }
    let labels: Vec<_> = LABEL.captures_iter(trimmed).collect();
    if labels.len() < MIN_LABELS {
        return text.to_owned();
    }
    let mut out = String::new();
    let first = labels[0].get(0).expect("whole match").start();
    let intro = trimmed[..first].trim();
    if !intro.is_empty() {
        out.push_str(intro);
        out.push_str("\n\n");
    }
    for (index, caps) in labels.iter().enumerate() {
        let whole = caps.get(0).expect("whole match");
        let label = caps.name("label").expect("label group").as_str().trim();
        let end = labels.get(index + 1).map_or(trimmed.len(), |next| {
            next.get(0).expect("whole match").start()
        });
        let body = trimmed[whole.end()..end].trim();
        out.push_str("- **");
        out.push_str(label);
        out.push_str(":**");
        if !body.is_empty() {
            out.push(' ');
            out.push_str(body);
        }
        out.push('\n');
    }
    out.trim_end().to_owned()
}

/// Formats the step descriptions of an import draft in place.
pub fn format_steps(steps: &mut [crate::import_document::StepImportDraft]) {
    for step in steps {
        if let Some(description) = step.description.as_mut() {
            *description = format_instructions(description);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn run_together_rounds_become_a_list() {
        let text = "Bei einer Länge von 16 cm wie folgt abnehmen: 1. RUNDE: In jeder Linksrippe \
                    1 Masche abnehmen = 66 Maschen. 2.-4. RUNDE: rechte Maschen rechts stricken. \
                    10.–11. RUNDE: rechts stricken. Den Faden abschneiden.";
        assert_eq!(
            format_instructions(text),
            "Bei einer Länge von 16 cm wie folgt abnehmen:\n\n\
             - **1. RUNDE:** In jeder Linksrippe 1 Masche abnehmen = 66 Maschen.\n\
             - **2.-4. RUNDE:** rechte Maschen rechts stricken.\n\
             - **10.–11. RUNDE:** rechts stricken. Den Faden abschneiden."
        );
    }

    #[test]
    fn english_rows_and_rounds_are_recognised() {
        assert_eq!(
            format_instructions("Row 1: k all. Rows 2-4: p all. Rnd 5: k2tog."),
            "- **Row 1:** k all.\n- **Rows 2-4:** p all.\n- **Rnd 5:** k2tog."
        );
    }

    #[test]
    fn single_labels_lines_and_plain_numbers_stay() {
        let one = "1. RUNDE: alles rechts stricken.";
        assert_eq!(format_instructions(one), one);
        let lines = "1. RUNDE: rechts.\n2. RUNDE: links.";
        assert_eq!(format_instructions(lines), lines);
        let sizes = "Größe 1. 2. 3: 66-75-78 Maschen anschlagen.";
        assert_eq!(format_instructions(sizes), sizes);
    }
}
