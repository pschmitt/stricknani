//! Read-only, administrator-scoped view of native and imported audit events.
use crate::{Language, auth, db::AppState, i18n, web};
use askama::Template;
use axum::{
    Router,
    extract::{RawQuery, State},
    http::{HeaderMap, HeaderValue, StatusCode, header},
    response::{Html, IntoResponse, Redirect, Response},
    routing::get,
};
use sqlx::{Row, SqlitePool};
use url::form_urlencoded;

const PAGE_SIZE: i64 = 50;
const MAX_PAGE: u32 = 10_000;
const MAX_QUERY_BYTES: usize = 512;

pub(crate) fn router(api: Router, app: AppState) -> Router {
    Router::new()
        .route("/admin/audit", get(audit_page))
        .with_state(web::WebState { api, app })
}

#[derive(Debug, Default)]
struct AuditQuery {
    page: Option<u32>,
    entity: Option<String>,
    action: Option<String>,
}

impl AuditQuery {
    fn parse(raw: Option<String>) -> Result<Self, ()> {
        let Some(raw) = raw else {
            return Ok(Self::default());
        };
        if raw.len() > MAX_QUERY_BYTES {
            return Err(());
        }
        let mut query = Self::default();
        let mut fields = 0;
        for (key, value) in form_urlencoded::parse(raw.as_bytes()) {
            fields += 1;
            if fields > 8 {
                return Err(());
            }
            match key.as_ref() {
                "page" if query.page.is_none() => {
                    query.page = Some(value.parse().map_err(|_| ())?);
                }
                "entity" if query.entity.is_none() => query.entity = Some(value.into_owned()),
                "action" if query.action.is_none() => query.action = Some(value.into_owned()),
                _ => return Err(()),
            }
        }
        Ok(query)
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum EntityFilter {
    All,
    Project,
    Yarn,
    Category,
}

impl EntityFilter {
    fn parse(value: Option<&str>) -> Option<Self> {
        match value.unwrap_or("all") {
            "all" => Some(Self::All),
            "project" => Some(Self::Project),
            "yarn" => Some(Self::Yarn),
            "category" => Some(Self::Category),
            _ => None,
        }
    }

    const fn value(self) -> &'static str {
        match self {
            Self::All => "all",
            Self::Project => "project",
            Self::Yarn => "yarn",
            Self::Category => "category",
        }
    }

    const fn sql_value(self) -> Option<&'static str> {
        match self {
            Self::All => None,
            Self::Project => Some("project"),
            Self::Yarn => Some("yarn"),
            Self::Category => Some("category"),
        }
    }
}

#[derive(Template)]
#[template(path = "audit.html")]
struct AuditTemplate {
    language: &'static str,
    title: &'static str,
    projects: &'static str,
    yarns: &'static str,
    account: &'static str,
    users: &'static str,
    audit: &'static str,
    signed_in_as: &'static str,
    current_email: String,
    entity_label: &'static str,
    action_label: &'static str,
    all_entities: &'static str,
    project: &'static str,
    yarn: &'static str,
    category: &'static str,
    apply: &'static str,
    actor: &'static str,
    target: &'static str,
    created: &'static str,
    empty: &'static str,
    previous: &'static str,
    next: &'static str,
    page_label: String,
    entity_filter: &'static str,
    action_filter: String,
    rows: Vec<AuditDisplayRow>,
    previous_url: Option<String>,
    next_url: Option<String>,
}

struct AuditDisplayRow {
    id: i64,
    actor: String,
    entity: String,
    target_id: i64,
    action: String,
    created_at: String,
}

async fn audit_page(
    State(state): State<web::WebState>,
    headers: HeaderMap,
    RawQuery(raw_query): RawQuery,
) -> Response {
    let session = match web::session(&state, &headers).await {
        Ok(session) => session,
        Err(auth::AuthError::Unauthorized) => return Redirect::to("/login").into_response(),
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    match is_active_admin(&state.app.pool, session.user_id).await {
        Ok(true) => {}
        Ok(false) => return StatusCode::FORBIDDEN.into_response(),
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }

    let Ok(query) = AuditQuery::parse(raw_query) else {
        return StatusCode::BAD_REQUEST.into_response();
    };
    let Some(entity_filter) = EntityFilter::parse(query.entity.as_deref()) else {
        return StatusCode::BAD_REQUEST.into_response();
    };
    let action_filter = query.action.unwrap_or_default();
    if action_filter.len() > 64 || action_filter.chars().any(char::is_control) {
        return StatusCode::BAD_REQUEST.into_response();
    }
    let action_filter = action_filter.trim().to_owned();
    let action_sql = (!action_filter.is_empty()).then_some(action_filter.as_str());
    let entity_sql = entity_filter.sql_value();

    let total: i64 = match sqlx::query_scalar(
        "SELECT count(*) FROM audit_events \
         WHERE (? IS NULL OR entity_type = ?) AND (? IS NULL OR action = ?)",
    )
    .bind(entity_sql)
    .bind(entity_sql)
    .bind(action_sql)
    .bind(action_sql)
    .fetch_one(&state.app.pool)
    .await
    {
        Ok(total) => total,
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    let page_count = total
        .saturating_add(PAGE_SIZE - 1)
        .checked_div(PAGE_SIZE)
        .unwrap_or(1)
        .max(1)
        .min(i64::from(MAX_PAGE)) as u32;
    let requested_page = query.page.unwrap_or(1);
    if requested_page == 0 || requested_page > MAX_PAGE {
        return StatusCode::BAD_REQUEST.into_response();
    }
    let page = requested_page.min(page_count);
    let offset = i64::from(page - 1) * PAGE_SIZE;
    let rows = match sqlx::query(
        "SELECT e.id, e.legacy_actor_user_id, u.email, \
                e.entity_type, e.entity_id, e.action, e.created_at \
         FROM audit_events AS e \
         LEFT JOIN users AS u ON u.id = e.actor_user_id \
         WHERE (? IS NULL OR e.entity_type = ?) AND (? IS NULL OR e.action = ?) \
         ORDER BY e.created_at DESC, e.id DESC LIMIT ? OFFSET ?",
    )
    .bind(entity_sql)
    .bind(entity_sql)
    .bind(action_sql)
    .bind(action_sql)
    .bind(PAGE_SIZE)
    .bind(offset)
    .fetch_all(&state.app.pool)
    .await
    {
        Ok(rows) => rows,
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };

    let language = language(&headers);
    let tr = |english, german| i18n::text(language, english, german);
    let unknown_actor = tr("Unavailable account", "Unbekanntes Konto");
    let rows = rows
        .into_iter()
        .map(|row| {
            let actor_email = row.get::<Option<String>, _>("email");
            let legacy_actor_id = row.get::<Option<i64>, _>("legacy_actor_user_id");
            let actor = actor_email.unwrap_or_else(|| {
                legacy_actor_id.map_or_else(
                    || unknown_actor.to_owned(),
                    |id| format!("Legacy account #{id}"),
                )
            });
            AuditDisplayRow {
                id: row.get("id"),
                actor,
                entity: row.get("entity_type"),
                target_id: row.get("entity_id"),
                action: row.get("action"),
                created_at: row.get("created_at"),
            }
        })
        .collect();

    let previous_url = (page > 1).then(|| page_url(page - 1, entity_filter, &action_filter));
    let next_url = (page < page_count).then(|| page_url(page + 1, entity_filter, &action_filter));
    let title = tr("Audit events", "Audit-Protokoll");
    let labels = AuditTemplate {
        language: language_code(language),
        title,
        projects: tr("Projects", "Projekte"),
        yarns: tr("Yarns", "Garne"),
        account: tr("Account", "Konto"),
        users: tr("User accounts", "Benutzerkonten"),
        audit: title,
        signed_in_as: tr("Signed in as", "Angemeldet als"),
        current_email: session.email,
        entity_label: tr("Entity", "Objekttyp"),
        action_label: tr("Action", "Aktion"),
        all_entities: tr("All entities", "Alle Objekttypen"),
        project: tr("Project", "Projekt"),
        yarn: tr("Yarn", "Garn"),
        category: tr("Category", "Kategorie"),
        apply: tr("Apply filters", "Filter anwenden"),
        actor: tr("Actor", "Akteur"),
        target: tr("Target", "Ziel"),
        created: tr("Created", "Erstellt"),
        empty: tr("No audit events found.", "Keine Audit-Einträge gefunden."),
        previous: tr("Previous", "Zurück"),
        next: tr("Next", "Weiter"),
        page_label: format!("{} / {}", page, page_count),
        entity_filter: entity_filter.value(),
        action_filter,
        rows,
        previous_url,
        next_url,
    };
    match labels.render() {
        Ok(body) => {
            let mut response = Html(body).into_response();
            response
                .headers_mut()
                .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
            response
        }
        Err(_) => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }
}

async fn is_active_admin(pool: &SqlitePool, user_id: i64) -> Result<bool, sqlx::Error> {
    let role =
        sqlx::query_scalar::<_, i64>("SELECT is_admin FROM users WHERE id = ? AND active = 1")
            .bind(user_id)
            .fetch_optional(pool)
            .await?;
    Ok(role == Some(1))
}

fn page_url(page: u32, entity: EntityFilter, action: &str) -> String {
    let mut query = form_urlencoded::Serializer::new(String::new());
    query.append_pair("page", &page.to_string());
    if entity != EntityFilter::All {
        query.append_pair("entity", entity.value());
    }
    if !action.is_empty() {
        query.append_pair("action", action);
    }
    format!("/admin/audit?{}", query.finish())
}

fn language(headers: &HeaderMap) -> Language {
    if let Some(cookie) = headers
        .get(header::COOKIE)
        .and_then(|value| value.to_str().ok())
    {
        for item in cookie.split(';') {
            if let Some(("language", value)) = item.trim().split_once('=') {
                match value {
                    "en" => return Language::English,
                    "de" => return Language::German,
                    _ => {}
                }
            }
        }
    }
    if let Some(value) = headers
        .get(header::ACCEPT_LANGUAGE)
        .and_then(|value| value.to_str().ok())
    {
        for item in value.split(',') {
            let item = item.trim_start();
            if item.starts_with("de") {
                return Language::German;
            }
            if item.starts_with("en") {
                return Language::English;
            }
        }
    }
    match std::env::var("DEFAULT_LANGUAGE").as_deref() {
        Ok("en") => Language::English,
        _ => Language::German,
    }
}

const fn language_code(language: Language) -> &'static str {
    match language {
        Language::English => "en",
        Language::German => "de",
    }
}
