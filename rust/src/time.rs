//! The v1 protocol accepts offsets and legacy naive UTC timestamps.
use chrono::{DateTime, NaiveDate, NaiveDateTime, Utc};

pub(crate) fn parse_utc(value: &str) -> Option<DateTime<Utc>> {
    let value = value.replace(' ', "T");
    if let Ok(value) = DateTime::parse_from_rfc3339(&value) {
        return Some(value.with_timezone(&Utc));
    }
    if let Ok(value) = NaiveDateTime::parse_from_str(&value, "%Y-%m-%dT%H:%M:%S%.f") {
        return Some(value.and_utc());
    }
    NaiveDate::parse_from_str(&value, "%Y-%m-%d")
        .ok()?
        .and_hms_opt(0, 0, 0)
        .map(|value| value.and_utc())
}

#[cfg(test)]
mod tests {
    #[test]
    fn legacy_naive_utc_and_offset_values_identify_the_same_instant() {
        let expected = super::parse_utc("2026-10-31T12:30:00.123456Z").unwrap();
        for value in [
            "2026-10-31T12:30:00.123456",
            "2026-10-31 12:30:00.123456",
            "2026-10-31T14:30:00.123456+02:00",
        ] {
            assert_eq!(super::parse_utc(value), Some(expected), "value: {value}");
        }
        assert!(super::parse_utc("invalid").is_none());
    }
}
