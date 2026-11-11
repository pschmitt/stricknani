//! Typeahead filter suggestions of the project and yarn search bars.
//!
//! The server answers only for the values that come from data the caller may see (tags,
//! categories and, for administrators alone, owners). The clients add the fixed filters
//! (status, favorites, Mine/Shared, Trash) themselves with the same matching rules:
//! case and diacritics are ignored, and a value matches when the typed text is a prefix of
//! the whole value or of one of its words ("mu" and "mü" both find "Mütze", "bl" finds "Baby
//! blanket").

use serde::Serialize;
use sqlx::SqlitePool;
use unicode_normalization::{UnicodeNormalization, char::is_combining_mark};

use crate::resource_access::{self, ResourceKind, Scope};

/// Longest typed fragment that is looked at; the rest is ignored.
const MAX_QUERY_CHARS: usize = 64;
/// Most suggestions per group.
pub(crate) const GROUP_LIMIT: usize = 5;

#[derive(Debug)]
pub(crate) enum SuggestionError {
    Database,
}

impl From<sqlx::Error> for SuggestionError {
    fn from(_: sqlx::Error) -> Self {
        Self::Database
    }
}

/// Lowercase, without diacritics: "Mütze" and "mutze" fold to the same text. The sharp s is
/// "s", like in the web and Android command palettes (`palette/match.ts`, `PaletteMatcher.kt`).
pub(crate) fn fold(text: &str) -> String {
    text.nfd()
        .filter(|c| !is_combining_mark(*c))
        .flat_map(char::to_lowercase)
        .map(|c| if c == 'ß' { 's' } else { c })
        .collect()
}

/// How well `value` matches the folded query: `Some(0)` for a prefix of the whole value,
/// `Some(1)` for a prefix of one of its words, `None` for no match.
pub(crate) fn match_rank(folded_query: &str, value: &str) -> Option<u8> {
    if folded_query.is_empty() {
        return None;
    }
    let folded = fold(value);
    if folded.starts_with(folded_query) {
        return Some(0);
    }
    folded
        .split(|c: char| !c.is_alphanumeric())
        .any(|word| !word.is_empty() && word.starts_with(folded_query))
        .then_some(1)
}

/// The folded, trimmed query, or `None` when there is nothing to look for.
pub(crate) fn normalized_query(raw: &str) -> Option<String> {
    let trimmed: String = raw.trim().chars().take(MAX_QUERY_CHARS).collect();
    let folded = fold(trimmed.trim());
    (!folded.is_empty()).then_some(folded)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum SuggestionKind {
    Projects,
    Yarns,
}

impl SuggestionKind {
    pub(crate) fn parse(value: &str) -> Option<Self> {
        match value {
            "projects" => Some(Self::Projects),
            "yarns" => Some(Self::Yarns),
            _ => None,
        }
    }
}

#[derive(Debug, Serialize, PartialEq, Eq)]
pub(crate) struct TagSuggestion {
    pub(crate) tag: String,
    pub(crate) count: i64,
}

#[derive(Debug, Serialize, PartialEq, Eq)]
pub(crate) struct CategorySuggestion {
    pub(crate) name: String,
}

/// An owner, with the name and avatar only: never an email address.
#[derive(Debug, Serialize, PartialEq, Eq)]
pub(crate) struct OwnerSuggestion {
    pub(crate) id: i64,
    pub(crate) name: String,
    pub(crate) avatar_url: Option<String>,
}

#[derive(Debug, Default, Serialize, PartialEq, Eq)]
pub(crate) struct Suggestions {
    pub(crate) tags: Vec<TagSuggestion>,
    pub(crate) categories: Vec<CategorySuggestion>,
    pub(crate) owners: Vec<OwnerSuggestion>,
}

/// Suggestions for the text typed into the search bar of the project or yarn list.
///
/// Administrators search everybody's items (as their lists do); everyone else only what they
/// own or were given a share of. Owners are suggested to administrators only.
pub(crate) async fn suggest(
    state: &crate::db::AppState,
    user_id: i64,
    kind: SuggestionKind,
    raw_query: &str,
) -> Result<Suggestions, SuggestionError> {
    let Some(query) = normalized_query(raw_query) else {
        return Ok(Suggestions::default());
    };
    let admin = resource_access::is_active_admin(&state.pool, user_id).await?;
    let scope = if admin { Scope::All } else { Scope::Mine };
    let mut suggestions = Suggestions::default();

    if kind == SuggestionKind::Projects {
        let counts = crate::projects::tag_counts_for_scope(
            state,
            user_id,
            Some(if admin { "all" } else { "mine" }),
        )
        .await
        .map_err(|_| SuggestionError::Database)?;
        let mut ranked: Vec<(u8, TagSuggestion)> = counts
            .into_iter()
            .filter_map(|tag| {
                match_rank(&query, &tag.tag).map(|rank| {
                    (
                        rank,
                        TagSuggestion {
                            tag: tag.tag,
                            count: tag.count,
                        },
                    )
                })
            })
            .collect();
        // Better match first, then the most used tag; `counts` is already in that order.
        ranked.sort_by_key(|(rank, _)| *rank);
        suggestions.tags = ranked
            .into_iter()
            .take(GROUP_LIMIT)
            .map(|(_, tag)| tag)
            .collect();

        let mut names = category_names(&state.pool, user_id, scope).await?;
        names.sort_by_key(|name| (fold(name), name.clone()));
        let mut ranked: Vec<(u8, String)> = names
            .into_iter()
            .filter_map(|name| match_rank(&query, &name).map(|rank| (rank, name)))
            .collect();
        ranked.sort_by_key(|(rank, _)| *rank);
        suggestions.categories = ranked
            .into_iter()
            .take(GROUP_LIMIT)
            .map(|(_, name)| CategorySuggestion { name })
            .collect();
    }

    if admin {
        let owners = resource_access::visible_owners(
            &state.pool,
            user_id,
            match kind {
                SuggestionKind::Projects => ResourceKind::Project,
                SuggestionKind::Yarns => ResourceKind::Yarn,
            },
            Scope::All,
            state.gravatar.enabled,
        )
        .await?;
        let mut ranked: Vec<(u8, OwnerSuggestion)> = owners
            .into_iter()
            .filter_map(|owner| {
                match_rank(&query, &owner.name).map(|rank| {
                    (
                        rank,
                        OwnerSuggestion {
                            id: owner.id,
                            name: owner.name,
                            avatar_url: owner.avatar_url,
                        },
                    )
                })
            })
            .collect();
        ranked.sort_by_key(|(rank, _)| *rank);
        suggestions.owners = ranked
            .into_iter()
            .take(GROUP_LIMIT)
            .map(|(_, owner)| owner)
            .collect();
    }
    Ok(suggestions)
}

/// The category names the caller's lists can show: their own categories and those of the
/// projects they see, each spelling once (case-insensitively).
async fn category_names(
    pool: &SqlitePool,
    user_id: i64,
    scope: Scope,
) -> Result<Vec<String>, sqlx::Error> {
    let used = match scope {
        Scope::All => {
            "SELECT DISTINCT c.name FROM projects p JOIN categories c ON c.id = p.category_id"
                .to_owned()
        }
        Scope::Mine => format!(
            "SELECT DISTINCT c.name FROM projects p JOIN categories c ON c.id = p.category_id \
             WHERE {}",
            resource_access::visible_sql(ResourceKind::Project, "p", 1)
        ),
    };
    let sql = format!("SELECT name FROM categories WHERE user_id = ?1 UNION {used}");
    let rows: Vec<String> = sqlx::query_scalar(sqlx::AssertSqlSafe(sql.as_str()))
        .bind(user_id)
        .fetch_all(pool)
        .await?;
    let mut seen = std::collections::HashSet::new();
    Ok(rows
        .into_iter()
        .filter(|name| seen.insert(name.to_lowercase()))
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn folding_ignores_case_and_diacritics() {
        assert_eq!(fold("Mütze"), "mutze");
        assert_eq!(fold("MÜTZE"), "mutze");
        assert_eq!(fold("Straße"), "strase");
        assert_eq!(fold("Crème brûlée"), "creme brulee");
    }

    #[test]
    fn prefix_of_the_value_or_of_a_word_matches() {
        let q = normalized_query("mü").unwrap();
        assert_eq!(match_rank(&q, "Mütze"), Some(0));
        assert_eq!(match_rank(&q, "mutze"), Some(0));
        assert_eq!(match_rank(&q, "Wintermütze"), None);
        assert_eq!(match_rank(&q, "Kinder-Mütze"), Some(1));
        assert_eq!(match_rank(&q, "Schal"), None);
        assert_eq!(match_rank("", "Mütze"), None);
        assert_eq!(normalized_query("   "), None);
    }
}
