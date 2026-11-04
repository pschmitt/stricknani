//! Decode fetched HTML using the declared or sniffed character set.
//!
//! Older German and Dutch shops still serve ISO-8859-1 or Windows-1252 pages; decoding those as
//! UTF-8 turns every umlaut into a replacement character and breaks fiber and size parsing.

use encoding_rs::{Encoding, UTF_8};

/// Decode `bytes` to text. Order: byte-order mark, `Content-Type` charset, a `<meta>` charset in
/// the first 4 KiB, then UTF-8 if the bytes are valid UTF-8, then Windows-1252.
pub fn decode_html(bytes: &[u8], content_type: Option<&str>) -> String {
    if let Some((encoding, _)) = Encoding::for_bom(bytes) {
        let (text, _) = encoding.decode_with_bom_removal(bytes);
        return text.into_owned();
    }
    let declared = content_type
        .and_then(charset_from_content_type)
        .or_else(|| charset_from_meta(bytes))
        .and_then(|label| Encoding::for_label(label.as_bytes()));
    let encoding = match declared {
        Some(encoding) => encoding,
        None if std::str::from_utf8(bytes).is_ok() => UTF_8,
        None => encoding_rs::WINDOWS_1252,
    };
    let (text, _, _) = encoding.decode(bytes);
    text.into_owned()
}

fn charset_from_content_type(value: &str) -> Option<String> {
    let lower = value.to_ascii_lowercase();
    let index = lower.find("charset=")?;
    let rest = &lower[index + "charset=".len()..];
    let label: String = rest
        .trim_start_matches(['"', '\''])
        .chars()
        .take_while(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | ':' | '.'))
        .collect();
    (!label.is_empty()).then_some(label)
}

fn charset_from_meta(bytes: &[u8]) -> Option<String> {
    let head = &bytes[..bytes.len().min(4096)];
    let lower = String::from_utf8_lossy(head).to_ascii_lowercase();
    let index = lower.find("charset=")?;
    let rest = &lower[index + "charset=".len()..];
    let label: String = rest
        .trim_start_matches(['"', '\''])
        .chars()
        .take_while(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | ':' | '.'))
        .collect();
    (!label.is_empty()).then_some(label)
}

#[cfg(test)]
mod tests {
    use super::decode_html;

    #[test]
    fn declared_latin1_is_decoded() {
        let bytes = b"<p>Gr\xfc\xdfe und Gr\xf6\xdfen</p>";
        assert_eq!(
            decode_html(bytes, Some("text/html; charset=ISO-8859-1")),
            "<p>Gr\u{fc}\u{df}e und Gr\u{f6}\u{df}en</p>"
        );
    }

    #[test]
    fn meta_charset_is_honoured() {
        let bytes = b"<meta charset=\"windows-1252\"><p>Garnst\xe4rke</p>";
        assert!(decode_html(bytes, Some("text/html")).contains("Garnst\u{e4}rke"));
    }

    #[test]
    fn undeclared_invalid_utf8_falls_back_to_windows_1252() {
        assert!(decode_html(b"Schurwolle 50\x80", None).contains('\u{20ac}'));
    }

    #[test]
    fn undeclared_utf8_stays_utf8() {
        assert_eq!(
            decode_html("Wolle \u{2013} 100%".as_bytes(), None),
            "Wolle \u{2013} 100%"
        );
    }
}
