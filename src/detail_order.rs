//! The per-user order of the reorderable detail-page sections.
//!
//! The ids and default orders mirror Android's `ProjectDetailCard` / `YarnDetailCard`
//! and the web's `detail-order.ts`. Attachments sit fixed under the gallery and the
//! source links are rows of the details section, so they are not reorderable.

/// The two detail-page families.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum DetailDomain {
    Project,
    Yarn,
}

const PROJECT_DEFAULT: [&str; 4] = ["details", "materials", "description", "steps"];
const YARN_DEFAULT: [&str; 4] = ["details", "description", "notes", "used_in"];

impl DetailDomain {
    pub(crate) fn defaults(self) -> &'static [&'static str] {
        match self {
            Self::Project => &PROJECT_DEFAULT,
            Self::Yarn => &YARN_DEFAULT,
        }
    }
}

/// Whether every id is known and none repeats.
pub(crate) fn is_valid(domain: DetailDomain, ids: &[String]) -> bool {
    let defaults = domain.defaults();
    ids.iter()
        .enumerate()
        .all(|(index, id)| defaults.contains(&id.as_str()) && !ids[..index].contains(id))
}

/// Drops unknown and duplicate ids and appends missing sections in their default order.
pub(crate) fn sanitize<S: AsRef<str>>(domain: DetailDomain, saved: &[S]) -> Vec<String> {
    let defaults = domain.defaults();
    let mut order: Vec<String> = Vec::with_capacity(defaults.len());
    for id in saved {
        let id = id.as_ref();
        if defaults.contains(&id) && !order.iter().any(|existing| existing == id) {
            order.push(id.to_owned());
        }
    }
    for id in defaults {
        if !order.iter().any(|existing| existing == id) {
            order.push((*id).to_owned());
        }
    }
    order
}

/// The effective order for a stored column value (NULL or blank is the default).
pub(crate) fn from_stored(domain: DetailDomain, stored: Option<&str>) -> Vec<String> {
    let ids: Vec<&str> = stored
        .unwrap_or_default()
        .split(',')
        .filter(|id| !id.is_empty())
        .collect();
    sanitize(domain, &ids)
}

/// The column value for a requested order; an empty request resets to the default (NULL).
pub(crate) fn to_stored(domain: DetailDomain, requested: &[String]) -> Option<String> {
    if requested.is_empty() {
        return None;
    }
    Some(sanitize(domain, requested).join(","))
}

#[derive(Clone, Debug, serde::Serialize)]
pub(crate) struct DetailCardOrders {
    pub(crate) project: Vec<String>,
    pub(crate) yarn: Vec<String>,
}

impl DetailCardOrders {
    pub(crate) fn from_stored(project: Option<&str>, yarn: Option<&str>) -> Self {
        Self {
            project: from_stored(DetailDomain::Project, project),
            yarn: from_stored(DetailDomain::Yarn, yarn),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sanitize_drops_unknown_and_duplicates_and_appends_missing() {
        assert_eq!(
            sanitize(DetailDomain::Yarn, &["notes", "bogus", "notes", "details"]),
            ["notes", "details", "description", "used_in"]
        );
        assert_eq!(
            sanitize::<&str>(DetailDomain::Project, &[]),
            PROJECT_DEFAULT
        );
    }

    #[test]
    fn validation_rejects_unknown_and_repeated_ids() {
        let ids = |list: &[&str]| list.iter().map(|id| (*id).to_owned()).collect::<Vec<_>>();
        assert!(is_valid(DetailDomain::Project, &ids(&["steps", "details"])));
        assert!(!is_valid(DetailDomain::Project, &ids(&["notes"])));
        assert!(!is_valid(DetailDomain::Project, &ids(&["steps", "steps"])));
    }
}
