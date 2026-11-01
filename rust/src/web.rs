//! Server-rendered presentation over the same versioned domain operations as Android.
use crate::{Language, auth, db::AppState};
use askama::Template;
use axum::{
    Form, Router,
    body::{Body, to_bytes},
    extract::{ConnectInfo, DefaultBodyLimit, Extension, Multipart, Path, Query, State},
    http::{HeaderMap, HeaderValue, Method, Request, StatusCode, header},
    response::{Html, IntoResponse, Redirect, Response},
    routing::{get, post},
};
use rand::{RngCore, rngs::OsRng};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::net::SocketAddr;
use subtle::ConstantTimeEq;
use tower::ServiceExt;

const LOGIN_CSRF_COOKIE: &str = "stricknani_login_csrf";
const HOST_LOGIN_CSRF_COOKIE: &str = "__Host-stricknani_login_csrf";
const SESSION_SECONDS: i64 = 14 * 24 * 60 * 60;

#[derive(Clone)]
pub(crate) struct WebState {
    pub(crate) api: Router,
    pub(crate) app: AppState,
}

pub(crate) fn router(api: Router, app: AppState) -> Router {
    Router::new()
        .route("/", get(home))
        .route(
            "/login",
            get(login_page)
                .post(login)
                .layer(DefaultBodyLimit::max(8 * 1024)),
        )
        .route("/logout", axum::routing::post(logout))
        .route("/projects", get(projects))
        .route("/projects/", get(projects))
        .route("/projects/new", get(new_project).post(create_project))
        .route("/projects/{id}/edit", get(edit_project))
        .route("/projects/{id}/save", post(save_project))
        .route(
            "/projects/{id}/delete",
            get(confirm_project_delete).post(delete_project),
        )
        .route("/projects/{id}/favorite", post(toggle_project_favorite))
        .route("/projects/{id}/media/title", post(upload_project_title))
        .route(
            "/projects/{id}/steps/{step_id}/media",
            post(upload_project_step),
        )
        .route(
            "/projects/{id}/media/attachments",
            post(upload_project_attachment),
        )
        .route(
            "/projects/{id}/media/images/{media_id}/delete",
            post(delete_project_image),
        )
        .route(
            "/projects/{id}/media/attachments/{media_id}/delete",
            post(delete_project_attachment),
        )
        .route("/projects/{id}", get(project))
        .route("/yarns", get(yarns))
        .route("/yarns/", get(yarns))
        .route("/yarns/new", get(new_yarn).post(create_yarn))
        .route("/yarns/{id}/edit", get(edit_yarn))
        .route("/yarns/{id}/save", post(save_yarn))
        .route(
            "/yarns/{id}/delete",
            get(confirm_yarn_delete).post(delete_yarn),
        )
        .route("/yarns/{id}/favorite", post(toggle_yarn_favorite))
        .route("/yarns/{id}/media/photos", post(upload_yarn_photo))
        .route(
            "/yarns/{id}/media/photos/{media_id}/delete",
            post(delete_yarn_photo),
        )
        .route("/yarns/{id}", get(yarn))
        .route("/categories", get(categories))
        .route("/categories/new", post(create_category))
        .route("/categories/{id}/save", post(save_category))
        .route(
            "/categories/{id}/delete",
            get(confirm_category_delete).post(delete_category),
        )
        .layer(DefaultBodyLimit::max(27 * 1024 * 1024))
        .route("/language/{language}", get(language))
        .with_state(WebState { api, app })
}

pub(crate) struct Session {
    pub(crate) token: String,
    pub(crate) email: String,
    pub(crate) user_id: i64,
    pub(crate) is_admin: bool,
}

pub(crate) async fn session(
    state: &WebState,
    headers: &HeaderMap,
) -> Result<Session, auth::AuthError> {
    let user_id = auth::browser_user(&state.app.pool, headers, state.app.secure_cookies).await?;
    let (email, is_admin) =
        sqlx::query_as::<_, (String, i64)>("SELECT email, is_admin FROM users WHERE id = ?")
            .bind(user_id)
            .fetch_one(&state.app.pool)
            .await?;
    let token = auth::cookie(headers, auth::session_cookie_name(state.app.secure_cookies))
        .ok_or(auth::AuthError::Unauthorized)?
        .to_owned();
    Ok(Session {
        token,
        email,
        user_id,
        is_admin: is_admin != 0,
    })
}

async fn home(State(state): State<WebState>, headers: HeaderMap) -> Redirect {
    Redirect::to(if session(&state, &headers).await.is_ok() {
        "/projects"
    } else {
        "/login"
    })
}

async fn login_page(State(state): State<WebState>, headers: HeaderMap) -> Response {
    if session(&state, &headers).await.is_ok() {
        return Redirect::to("/projects").into_response();
    }
    login_view(&state, &headers, false, StatusCode::OK)
}

fn login_view(state: &WebState, headers: &HeaderMap, failed: bool, status: StatusCode) -> Response {
    let mut bytes = [0u8; 32];
    if OsRng.try_fill_bytes(&mut bytes).is_err() {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    }
    let csrf = hex::encode(bytes);
    let labels = Labels::new(crate::language_from_headers(headers));
    let error = if failed {
        Some(labels.invalid_login)
    } else {
        None
    };
    let page = Page {
        title: labels.sign_in.to_owned(),
        labels,
        csrf: csrf.clone(),
        email: None,
        is_admin: false,
        body: PageBody::Login(error, state.app.signup_enabled),
    };
    let response = render(page, status);
    with_cookie(
        response,
        unauth_csrf_cookie_name(state.app.secure_cookies),
        &csrf,
        600,
        state.app.secure_cookies,
    )
}

#[derive(Deserialize)]
struct LoginForm {
    email: String,
    password: String,
    csrf: String,
}

#[derive(Serialize)]
struct LoginRequest<'a> {
    email: &'a str,
    password: &'a str,
    token_name: &'static str,
}

async fn login(
    State(state): State<WebState>,
    headers: HeaderMap,
    peer: Option<Extension<ConnectInfo<SocketAddr>>>,
    Form(mut form): Form<LoginForm>,
) -> Response {
    let password = zeroize::Zeroizing::new(std::mem::take(&mut form.password));
    if !valid_unauth_csrf(&headers, &form.csrf, state.app.secure_cookies) {
        return StatusCode::FORBIDDEN.into_response();
    }
    let body = match serde_json::to_vec(&LoginRequest {
        email: &form.email,
        password: &password,
        token_name: "Browser session",
    }) {
        Ok(body) => body,
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    let mut request = match Request::builder()
        .method(Method::POST)
        .uri("/api/v1/auth/token")
        .header(header::CONTENT_TYPE, "application/json")
        .body(Body::from(body))
    {
        Ok(request) => request,
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    if let Some(Extension(peer)) = peer {
        request.extensions_mut().insert(peer);
    }
    let response = match state.api.clone().oneshot(request).await {
        Ok(response) => response,
        Err(error) => match error {},
    };
    if response.status() != StatusCode::OK {
        let status = if response.status() == StatusCode::TOO_MANY_REQUESTS {
            StatusCode::TOO_MANY_REQUESTS
        } else {
            StatusCode::UNAUTHORIZED
        };
        return login_view(&state, &headers, true, status);
    }
    let bytes = match to_bytes(response.into_body(), 16 * 1024).await {
        Ok(bytes) => bytes,
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    let result: Value = match serde_json::from_slice(&bytes) {
        Ok(value) => value,
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    let Some(token) = result["token"].as_str() else {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    };
    let hash = hex::encode(Sha256::digest(token.as_bytes()));
    if sqlx::query(
        "UPDATE api_tokens SET purpose = 'browser', expires_at = unixepoch() + ? WHERE token_hash = ?",
    )
        .bind(SESSION_SECONDS)
        .bind(hash)
        .execute(&state.app.pool)
        .await
        .is_err()
    {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    }
    let response = with_cookie(
        Redirect::to("/projects").into_response(),
        auth::session_cookie_name(state.app.secure_cookies),
        token,
        SESSION_SECONDS,
        state.app.secure_cookies,
    );
    with_cookie(
        response,
        if state.app.secure_cookies {
            HOST_LOGIN_CSRF_COOKIE
        } else {
            LOGIN_CSRF_COOKIE
        },
        "",
        0,
        state.app.secure_cookies,
    )
}

#[derive(Deserialize)]
struct CsrfForm {
    csrf: String,
}

async fn logout(
    State(state): State<WebState>,
    headers: HeaderMap,
    Form(form): Form<CsrfForm>,
) -> Response {
    let Ok(session) = session(&state, &headers).await else {
        return Redirect::to("/login").into_response();
    };
    if !valid_csrf(&session.token, &form.csrf) {
        return StatusCode::FORBIDDEN.into_response();
    }
    let hash = hex::encode(Sha256::digest(session.token.as_bytes()));
    if sqlx::query("UPDATE api_tokens SET revoked_at = unixepoch() WHERE token_hash = ?")
        .bind(hash)
        .execute(&state.app.pool)
        .await
        .is_err()
    {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    }
    with_cookie(
        Redirect::to("/login").into_response(),
        auth::session_cookie_name(state.app.secure_cookies),
        "",
        0,
        state.app.secure_cookies,
    )
}

pub(crate) fn csrf(token: &str) -> String {
    let mut hash = Sha256::new();
    hash.update(b"stricknani browser csrf v1\0");
    hash.update(token.as_bytes());
    hex::encode(hash.finalize())
}

pub(crate) fn valid_csrf(token: &str, supplied: &str) -> bool {
    bool::from(csrf(token).as_bytes().ct_eq(supplied.as_bytes()))
}

pub(crate) fn unauth_csrf_cookie_name(secure: bool) -> &'static str {
    if secure {
        HOST_LOGIN_CSRF_COOKIE
    } else {
        LOGIN_CSRF_COOKIE
    }
}

pub(crate) fn valid_unauth_csrf(headers: &HeaderMap, supplied: &str, secure: bool) -> bool {
    let Some(expected) = auth::cookie(headers, unauth_csrf_cookie_name(secure)) else {
        return false;
    };
    !expected.is_empty() && bool::from(expected.as_bytes().ct_eq(supplied.as_bytes()))
}

pub(crate) fn with_cookie(
    mut response: Response,
    name: &str,
    value: &str,
    max_age: i64,
    secure: bool,
) -> Response {
    let cookie = format!(
        "{name}={value}; Path=/; HttpOnly; SameSite=Lax; Max-Age={max_age}{}",
        if secure { "; Secure" } else { "" }
    );
    let Ok(cookie) = HeaderValue::from_str(&cookie) else {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    };
    response.headers_mut().append(header::SET_COOKIE, cookie);
    response
}

#[derive(Clone, Copy)]
enum Kind {
    Project,
    Yarn,
}
impl Kind {
    fn path(self) -> &'static str {
        match self {
            Self::Project => "projects",
            Self::Yarn => "yarns",
        }
    }
    fn title(self, labels: &Labels) -> &'static str {
        match self {
            Self::Project => labels.projects,
            Self::Yarn => labels.yarns,
        }
    }
}

#[derive(Default, Deserialize)]
struct CatalogQuery {
    page: Option<i64>,
    q: Option<String>,
    category: Option<String>,
    tag: Option<String>,
    favorite: Option<String>,
}

async fn projects(
    State(state): State<WebState>,
    headers: HeaderMap,
    Query(query): Query<CatalogQuery>,
) -> Response {
    catalog(state, headers, query, Kind::Project).await
}
async fn yarns(
    State(state): State<WebState>,
    headers: HeaderMap,
    Query(query): Query<CatalogQuery>,
) -> Response {
    catalog(state, headers, query, Kind::Yarn).await
}

async fn api_json(state: &WebState, token: &str, path: &str) -> Option<Value> {
    let (status, value) = api_request(state, token, Method::GET, path, None).await;
    status.is_success().then_some(value).flatten()
}

async fn api_request(
    state: &WebState,
    token: &str,
    method: Method,
    path: &str,
    json: Option<Value>,
) -> (StatusCode, Option<Value>) {
    let has_json = json.is_some();
    let body = match json {
        Some(value) => match serde_json::to_vec(&value) {
            Ok(bytes) => Body::from(bytes),
            Err(_) => return (StatusCode::INTERNAL_SERVER_ERROR, None),
        },
        None => Body::empty(),
    };
    let mut builder = Request::builder()
        .method(method)
        .uri(path)
        .header(header::AUTHORIZATION, format!("Bearer {token}"));
    if has_json {
        builder = builder.header(header::CONTENT_TYPE, "application/json");
    }
    let Ok(request) = builder.body(body) else {
        return (StatusCode::INTERNAL_SERVER_ERROR, None);
    };
    let response = match state.api.clone().oneshot(request).await {
        Ok(response) => response,
        Err(error) => match error {},
    };
    let status = response.status();
    if status == StatusCode::NO_CONTENT {
        return (status, None);
    }
    let Ok(bytes) = to_bytes(response.into_body(), 16 * 1024 * 1024).await else {
        return (StatusCode::INTERNAL_SERVER_ERROR, None);
    };
    (status, serde_json::from_slice(&bytes).ok())
}

async fn catalog(state: WebState, headers: HeaderMap, query: CatalogQuery, kind: Kind) -> Response {
    let session = match session(&state, &headers).await {
        Ok(session) => session,
        Err(auth::AuthError::Unauthorized) => return Redirect::to("/login").into_response(),
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    let page = query.page.unwrap_or(1);
    if page < 1 {
        return StatusCode::BAD_REQUEST.into_response();
    }
    if query.q.as_ref().is_some_and(|value| value.len() > 256) {
        return StatusCode::BAD_REQUEST.into_response();
    }
    let q = query.q.unwrap_or_default().trim().to_owned();
    let q = (!q.is_empty()).then_some(q);
    let (category, tag) = if matches!(kind, Kind::Project) {
        (
            query.category.filter(|value| !value.trim().is_empty()),
            query.tag.filter(|value| !value.trim().is_empty()),
        )
    } else {
        (None, None)
    };
    let favorite = match query.favorite.as_deref().filter(|value| !value.is_empty()) {
        Some("favorites") | Some("true") => Some(true),
        Some("not-favorites") | Some("false") => Some(false),
        None => None,
        Some(_) => return StatusCode::BAD_REQUEST.into_response(),
    };
    let favorite_filter = match favorite {
        Some(true) => "favorites",
        Some(false) => "not-favorites",
        None => "",
    };
    let path = catalog_api_path(
        kind,
        page,
        q.as_deref(),
        category.as_deref(),
        tag.as_deref(),
        favorite,
    );
    let Some(result) = api_json(&state, &session.token, &path).await else {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    };
    let cards = result["items"]
        .as_array()
        .into_iter()
        .flatten()
        .map(|item| Card {
            id: item["id"].as_i64().unwrap_or_default(),
            name: string(item, "name"),
            caption: match kind {
                Kind::Project => string(item, "category"),
                Kind::Yarn => [string(item, "brand"), string(item, "colorway")]
                    .into_iter()
                    .filter(|value| !value.is_empty())
                    .collect::<Vec<_>>()
                    .join(" · "),
            },
            preview: item["preview_url"].as_str().map(str::to_owned),
        })
        .collect();
    let labels = Labels::new(crate::language_from_headers(&headers));
    let previous_url = (page > 1).then(|| {
        catalog_page_url(
            kind,
            page - 1,
            q.as_deref(),
            category.as_deref(),
            tag.as_deref(),
            favorite_filter,
        )
    });
    let next_url = result["has_more"].as_bool().unwrap_or(false).then(|| {
        catalog_page_url(
            kind,
            page + 1,
            q.as_deref(),
            category.as_deref(),
            tag.as_deref(),
            favorite_filter,
        )
    });
    render(
        Page {
            title: kind.title(&labels).to_owned(),
            labels,
            csrf: csrf(&session.token),
            email: Some(session.email),
            is_admin: session.is_admin,
            body: PageBody::Catalog(Catalog {
                path: kind.path(),
                cards,
                q: q.unwrap_or_default(),
                category: category.unwrap_or_default(),
                tag: tag.unwrap_or_default(),
                favorite: favorite_filter.to_owned(),
                is_project: matches!(kind, Kind::Project),
                previous_url,
                next_url,
            }),
        },
        StatusCode::OK,
    )
}

fn catalog_api_path(
    kind: Kind,
    page: i64,
    q: Option<&str>,
    category: Option<&str>,
    tag: Option<&str>,
    favorite: Option<bool>,
) -> String {
    let mut query = url::form_urlencoded::Serializer::new(String::new());
    query.append_pair("page", &page.to_string());
    if let Some(q) = q {
        query.append_pair("q", q);
    }
    if let Some(category) = category {
        query.append_pair("category", category.trim());
    }
    if let Some(tag) = tag {
        query.append_pair("tag", tag.trim());
    }
    if let Some(favorite) = favorite {
        query.append_pair("favorite", if favorite { "true" } else { "false" });
    }
    format!("/api/v1/{}?{}", kind.path(), query.finish())
}

fn catalog_page_url(
    kind: Kind,
    page: i64,
    q: Option<&str>,
    category: Option<&str>,
    tag: Option<&str>,
    favorite: &str,
) -> String {
    let mut query = url::form_urlencoded::Serializer::new(String::new());
    query.append_pair("page", &page.to_string());
    if let Some(q) = q {
        query.append_pair("q", q);
    }
    if let Some(category) = category {
        query.append_pair("category", category.trim());
    }
    if let Some(tag) = tag {
        query.append_pair("tag", tag.trim());
    }
    if !favorite.is_empty() {
        query.append_pair("favorite", favorite);
    }
    format!("/{}?{}", kind.path(), query.finish())
}

async fn project(
    State(state): State<WebState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Response {
    detail(state, headers, id, Kind::Project, None).await
}
async fn yarn(State(state): State<WebState>, headers: HeaderMap, Path(id): Path<i64>) -> Response {
    detail(state, headers, id, Kind::Yarn, None).await
}

async fn detail(
    state: WebState,
    headers: HeaderMap,
    id: i64,
    kind: Kind,
    media_error: Option<&'static str>,
) -> Response {
    let session = match session(&state, &headers).await {
        Ok(session) => session,
        Err(auth::AuthError::Unauthorized) => return Redirect::to("/login").into_response(),
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    let Some(item) = api_json(
        &state,
        &session.token,
        &format!("/api/v1/{}/{id}", kind.path()),
    )
    .await
    else {
        return StatusCode::NOT_FOUND.into_response();
    };
    let language = crate::language_from_headers(&headers);
    let labels = Labels::new(language);
    let media_error = media_error.map(|key| {
        if key == "csrf" {
            labels.csrf_error
        } else {
            labels.media_error
        }
    });
    let metadata = [
        ("category", labels.category),
        ("brand", labels.brand),
        ("colorway", labels.color),
        ("yarn", labels.yarn),
        ("needles", labels.needles),
    ]
    .into_iter()
    .filter_map(|(key, label)| {
        item[key]
            .as_str()
            .filter(|value| !value.is_empty())
            .map(|value| (label, value.to_owned()))
    })
    .collect();
    let image_key = match kind {
        Kind::Project => "images",
        Kind::Yarn => "photos",
    };
    let images = item[image_key]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(|image| {
            Some(ImageView {
                url: image["url"].as_str()?.to_owned(),
                thumbnail: image["thumbnail_url"].as_str()?.to_owned(),
                alt: string(image, "alt_text"),
                delete_path: match kind {
                    Kind::Project => format!(
                        "/projects/{id}/media/images/{}/delete",
                        image["id"].as_i64()?
                    ),
                    Kind::Yarn => {
                        format!("/yarns/{id}/media/photos/{}/delete", image["id"].as_i64()?)
                    }
                },
            })
        })
        .collect();
    let attachments = item["attachments"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(|attachment| {
            Some(AttachmentView {
                url: attachment["url"].as_str()?.to_owned(),
                name: attachment["original_filename"].as_str()?.to_owned(),
                delete_path: format!(
                    "/projects/{id}/media/attachments/{}/delete",
                    attachment["id"].as_i64()?
                ),
            })
        })
        .collect();
    let steps = item["steps"]
        .as_array()
        .into_iter()
        .flatten()
        .map(|step| StepView {
            id: step["id"].as_i64().unwrap_or_default(),
            title: string(step, "title"),
            description: rich_text(&string(step, "description")),
        })
        .collect();
    let external_link = item["link"].as_str().and_then(safe_http_url);
    let archive_form_action = (state.app.archive_enabled && external_link.is_some())
        .then(|| format!("/{}/{id}/archive", kind.path()));
    let archive_link = item["link_archive"].as_str().and_then(safe_archive_url);
    let name = string(&item, "name");
    let body = Detail {
        id,
        path: kind.path(),
        favorite: item["is_favorite"].as_bool().unwrap_or(false),
        metadata,
        description: rich_text(&string(&item, "description")),
        notes: rich_text(&string(&item, "notes")),
        media_error,
        image_upload_path: match kind {
            Kind::Project => format!("/projects/{id}/media/title"),
            Kind::Yarn => format!("/yarns/{id}/media/photos"),
        },
        attachment_upload_path: if matches!(kind, Kind::Project) {
            Some(format!("/projects/{id}/media/attachments"))
        } else {
            None
        },
        images,
        attachments,
        steps,
        external_link,
        archive_link,
        archive_form_action,
        ocr_enabled: state.app.ocr_enabled,
    };
    render(
        Page {
            title: name,
            labels,
            csrf: csrf(&session.token),
            email: Some(session.email),
            is_admin: session.is_admin,
            body: PageBody::Detail(body),
        },
        StatusCode::OK,
    )
}

fn safe_http_url(value: &str) -> Option<String> {
    let url = url::Url::parse(value).ok()?;
    (matches!(url.scheme(), "https" | "http")
        && url.username().is_empty()
        && url.password().is_none()
        && url.port_or_known_default().is_some())
    .then(|| url.to_string())
}

fn safe_archive_url(value: &str) -> Option<String> {
    let url = url::Url::parse(value).ok()?;
    (url.scheme() == "https"
        && url.host_str() == Some("web.archive.org")
        && url.port_or_known_default() == Some(443)
        && url.username().is_empty()
        && url.password().is_none()
        && url.path().starts_with("/web/"))
    .then(|| url.to_string())
}

async fn new_project(State(state): State<WebState>, headers: HeaderMap) -> Response {
    editor_page(state, headers, Kind::Project, None, None, None).await
}
async fn edit_project(
    State(state): State<WebState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Response {
    editor_page(state, headers, Kind::Project, Some(id), None, None).await
}
async fn new_yarn(State(state): State<WebState>, headers: HeaderMap) -> Response {
    editor_page(state, headers, Kind::Yarn, None, None, None).await
}
async fn edit_yarn(
    State(state): State<WebState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Response {
    editor_page(state, headers, Kind::Yarn, Some(id), None, None).await
}

async fn editor_page(
    state: WebState,
    headers: HeaderMap,
    kind: Kind,
    id: Option<i64>,
    values: Option<FormValues>,
    error: Option<&'static str>,
) -> Response {
    let session = match session(&state, &headers).await {
        Ok(session) => session,
        Err(_) => return Redirect::to("/login").into_response(),
    };
    let item = if let Some(id) = id {
        let Some(item) = api_json(
            &state,
            &session.token,
            &format!("/api/v1/{}/{id}", kind.path()),
        )
        .await
        else {
            return StatusCode::NOT_FOUND.into_response();
        };
        item
    } else {
        serde_json::json!({})
    };
    let values = values.unwrap_or_else(|| FormValues::from_item(kind, &item));
    let mut options: Vec<Choice> = if matches!(kind, Kind::Project) {
        let yarns = api_json(&state, &session.token, "/api/v1/yarns").await;
        yarns
            .and_then(|value| value["items"].as_array().cloned())
            .unwrap_or_default()
            .into_iter()
            .map(|row| Choice {
                id: row["id"].as_i64().unwrap_or_default().to_string(),
                name: string(&row, "name"),
                selected: values
                    .get("yarn_ids")
                    .iter()
                    .any(|v| v == &row["id"].as_i64().unwrap_or_default().to_string()),
            })
            .collect()
    } else {
        Vec::new()
    };
    if matches!(kind, Kind::Project) {
        for yarn_id in values
            .get("yarn_ids")
            .iter()
            .filter_map(|value| value.parse::<i64>().ok())
        {
            if options
                .iter()
                .any(|option| option.id == yarn_id.to_string())
            {
                continue;
            }
            if let Some(yarn) =
                api_json(&state, &session.token, &format!("/api/v1/yarns/{yarn_id}")).await
            {
                options.push(Choice {
                    id: yarn_id.to_string(),
                    name: string(&yarn, "name"),
                    selected: true,
                });
            }
        }
    }
    let labels = Labels::new(crate::language_from_headers(&headers));
    let title = if id.is_some() {
        labels.edit
    } else {
        labels.create
    };
    let error = error.map(|key| {
        if key == "conflict" {
            labels.conflict_error
        } else {
            labels.validation_error
        }
    });
    let fields = values.fields(&labels, kind);
    let steps = values.steps();
    render(
        Page {
            title: title.to_owned(),
            labels,
            csrf: csrf(&session.token),
            email: Some(session.email),
            is_admin: session.is_admin,
            body: PageBody::Editor(Editor {
                path: kind.path(),
                id,
                error,
                fields,
                steps,
                options,
                current_name: error.and(id).map(|_| string(&item, "name")),
                current_updated_at: error.and(id).map(|_| string(&item, "updated_at")),
                expected_updated_at: values.one("expected_updated_at"),
            }),
        },
        StatusCode::OK,
    )
}

#[derive(Default)]
struct FormValues(std::collections::HashMap<String, Vec<String>>);
impl FormValues {
    fn parse(bytes: &[u8]) -> Option<Self> {
        let mut values = std::collections::HashMap::<String, Vec<String>>::new();
        for (key, value) in url::form_urlencoded::parse(bytes) {
            values
                .entry(key.into_owned())
                .or_default()
                .push(value.into_owned());
        }
        Some(Self(values))
    }
    fn get(&self, name: &str) -> Vec<String> {
        self.0.get(name).cloned().unwrap_or_default()
    }
    fn one(&self, name: &str) -> String {
        self.get(name).into_iter().next().unwrap_or_default()
    }
    fn from_item(kind: Kind, item: &Value) -> Self {
        let mut values = std::collections::HashMap::new();
        let keys: &[&str] = match kind {
            Kind::Project => &[
                "name",
                "category",
                "needles",
                "stitch_sample",
                "description",
                "notes",
                "other_materials",
                "link",
                "expected_updated_at",
            ],
            Kind::Yarn => &[
                "name",
                "description",
                "brand",
                "colorway",
                "dye_lot",
                "fiber_content",
                "weight_category",
                "recommended_needles",
                "weight_grams",
                "length_meters",
                "notes",
                "link",
                "expected_updated_at",
            ],
        };
        for key in keys {
            let value = if *key == "expected_updated_at" {
                item["updated_at"].as_str()
            } else {
                item[key].as_str()
            };
            let value = value.map(str::to_owned).unwrap_or_else(|| {
                item[key]
                    .as_i64()
                    .map(|n| n.to_string())
                    .unwrap_or_default()
            });
            values.insert((*key).to_owned(), vec![value]);
        }
        if let Some(tags) = item["tags"].as_array() {
            values.insert(
                "tags".to_owned(),
                vec![
                    tags.iter()
                        .filter_map(Value::as_str)
                        .collect::<Vec<_>>()
                        .join("\n"),
                ],
            );
        }
        if let Some(ids) = item["yarn_ids"].as_array() {
            values.insert(
                "yarn_ids".to_owned(),
                ids.iter()
                    .filter_map(Value::as_i64)
                    .map(|id| id.to_string())
                    .collect(),
            );
        }
        if let Some(steps) = item["steps"].as_array() {
            for step in steps {
                for (field, key) in [
                    ("step_id", "id"),
                    ("step_title", "title"),
                    ("step_description", "description"),
                    ("step_number", "step_number"),
                ] {
                    values.entry(field.to_owned()).or_default().push(
                        step[key]
                            .as_str()
                            .map(str::to_owned)
                            .unwrap_or_else(|| step[key].as_i64().unwrap_or_default().to_string()),
                    );
                }
            }
        }
        values.insert(
            "is_ai_enhanced".to_owned(),
            vec![
                if item["is_ai_enhanced"].as_bool().unwrap_or(false) {
                    "true"
                } else {
                    "false"
                }
                .to_owned(),
            ],
        );
        Self(values)
    }
    fn fields(&self, labels: &Labels, kind: Kind) -> Vec<Field> {
        let specs: &[(&str, &str, bool, bool)] = match kind {
            Kind::Project => &[
                ("Name", "name", false, false),
                ("Category", "category", false, false),
                ("Needles", "needles", false, false),
                ("Stitch sample", "stitch_sample", false, false),
                ("Description", "description", true, false),
                ("Notes", "notes", true, false),
                ("Other materials", "other_materials", true, false),
                ("Tags (one per line)", "tags", true, false),
                ("Link", "link", false, false),
                ("AI enhanced", "is_ai_enhanced", false, true),
            ],
            Kind::Yarn => &[
                ("Name", "name", false, false),
                ("Brand", "brand", false, false),
                ("Colorway", "colorway", false, false),
                ("Dye lot", "dye_lot", false, false),
                ("Fiber content", "fiber_content", false, false),
                ("Weight category", "weight_category", false, false),
                ("Recommended needles", "recommended_needles", false, false),
                ("Weight in grams", "weight_grams", false, false),
                ("Length in meters", "length_meters", false, false),
                ("Description", "description", true, false),
                ("Notes", "notes", true, false),
                ("Link", "link", false, false),
                ("AI enhanced", "is_ai_enhanced", false, true),
            ],
        };
        specs
            .iter()
            .map(|(fallback, name, textarea, checkbox)| Field {
                label: match *name {
                    "name" => labels.name,
                    "category" => labels.category,
                    "needles" | "recommended_needles" => labels.needles,
                    "description" => labels.description,
                    "notes" => labels.notes,
                    "tags" => labels.tags,
                    "other_materials" => labels.materials,
                    "link" => labels.link,
                    "brand" => labels.brand,
                    "colorway" => labels.color,
                    "fiber_content" => labels.fiber,
                    "weight_category" => labels.weight,
                    "dye_lot" => labels.dye_lot,
                    "weight_grams" => labels.weight_grams,
                    "length_meters" => labels.length_meters,
                    "is_ai_enhanced" => labels.ai_enhanced,
                    _ => fallback,
                },
                name,
                value: self.one(name),
                textarea: *textarea,
                checkbox: *checkbox,
            })
            .collect()
    }
    fn steps(&self) -> Vec<StepForm> {
        let ids = self.get("step_id");
        let titles = self.get("step_title");
        let descriptions = self.get("step_description");
        let numbers = self.get("step_number");
        (0..titles.len())
            .map(|i| StepForm {
                id: ids.get(i).cloned().unwrap_or_default(),
                title: titles[i].clone(),
                description: descriptions.get(i).cloned().unwrap_or_default(),
                step_number: numbers
                    .get(i)
                    .cloned()
                    .unwrap_or_else(|| (i + 1).to_string()),
            })
            .collect()
    }
    fn payload(&self, kind: Kind, expected: bool) -> Value {
        let mut object = serde_json::Map::new();
        let string_fields: &[&str] = match kind {
            Kind::Project => &[
                "name",
                "category",
                "needles",
                "stitch_sample",
                "description",
                "notes",
                "other_materials",
                "link",
            ],
            Kind::Yarn => &[
                "name",
                "description",
                "brand",
                "colorway",
                "dye_lot",
                "fiber_content",
                "weight_category",
                "recommended_needles",
                "notes",
                "link",
            ],
        };
        for field in string_fields {
            object.insert(
                (*field).to_owned(),
                serde_json::json!(empty_none(self.one(field))),
            );
        }
        for field in ["weight_grams", "length_meters"] {
            let raw = self.one(field);
            object.insert(
                field.to_owned(),
                serde_json::json!(if raw.trim().is_empty() {
                    None
                } else {
                    raw.parse::<i64>().ok()
                }),
            );
        }
        object.insert(
            "is_ai_enhanced".to_owned(),
            serde_json::json!(
                self.get("is_ai_enhanced")
                    .iter()
                    .any(|v| v == "true" || v == "on")
            ),
        );
        if matches!(kind, Kind::Project) {
            object.insert(
                "tags".to_owned(),
                serde_json::json!(
                    self.one("tags")
                        .lines()
                        .map(str::trim)
                        .filter(|s| !s.is_empty())
                        .collect::<Vec<_>>()
                ),
            );
            object.insert(
                "yarn_ids".to_owned(),
                serde_json::json!(
                    self.get("yarn_ids")
                        .iter()
                        .filter_map(|v| v.parse::<i64>().ok())
                        .collect::<Vec<_>>()
                ),
            );
            let ids = self.get("step_id");
            let titles = self.get("step_title");
            let descriptions = self.get("step_description");
            let numbers = self.get("step_number");
            let steps=(0..titles.len()).filter_map(|i| {
                let title=titles.get(i)?.trim(); if title.is_empty() { return None; }
                Some(serde_json::json!({"id":ids.get(i).and_then(|v| v.parse::<i64>().ok()),"title":title,"description":empty_none(descriptions.get(i).cloned().unwrap_or_default()),"step_number":numbers.get(i).and_then(|v|v.parse::<i64>().ok()).unwrap_or(i as i64+1)}))
            }).collect::<Vec<_>>();
            object.insert("steps".to_owned(), serde_json::json!(steps));
        }
        if expected {
            object.insert(
                "expected_updated_at".to_owned(),
                serde_json::json!(self.one("expected_updated_at")),
            );
        }
        Value::Object(object)
    }
}
fn empty_none(value: String) -> Option<String> {
    let value = value.trim().to_owned();
    (!value.is_empty()).then_some(value)
}

async fn read_form(request: Request<Body>) -> Option<FormValues> {
    let bytes = to_bytes(request.into_body(), 128 * 1024).await.ok()?;
    FormValues::parse(&bytes)
}
async fn form_response(
    state: WebState,
    headers: HeaderMap,
    values: FormValues,
    kind: Kind,
    id: Option<i64>,
    error: &'static str,
) -> Response {
    editor_page(state, headers, kind, id, Some(values), Some(error)).await
}
async fn create_project(
    State(state): State<WebState>,
    headers: HeaderMap,
    request: Request<Body>,
) -> Response {
    save_resource(state, headers, request, Kind::Project, None).await
}
async fn save_project(
    State(state): State<WebState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    request: Request<Body>,
) -> Response {
    save_resource(state, headers, request, Kind::Project, Some(id)).await
}
async fn create_yarn(
    State(state): State<WebState>,
    headers: HeaderMap,
    request: Request<Body>,
) -> Response {
    save_resource(state, headers, request, Kind::Yarn, None).await
}
async fn save_yarn(
    State(state): State<WebState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    request: Request<Body>,
) -> Response {
    save_resource(state, headers, request, Kind::Yarn, Some(id)).await
}
async fn save_resource(
    state: WebState,
    headers: HeaderMap,
    request: Request<Body>,
    kind: Kind,
    id: Option<i64>,
) -> Response {
    let Ok(session) = session(&state, &headers).await else {
        return Redirect::to("/login").into_response();
    };
    let Some(mut values) = read_form(request).await else {
        return StatusCode::BAD_REQUEST.into_response();
    };
    if !valid_csrf(&session.token, &values.one("csrf")) {
        return StatusCode::FORBIDDEN.into_response();
    }
    match values.one("step_action").as_str() {
        "add" if matches!(kind, Kind::Project) => {
            values
                .0
                .entry("step_id".into())
                .or_default()
                .push(String::new());
            values
                .0
                .entry("step_title".into())
                .or_default()
                .push(String::new());
            values
                .0
                .entry("step_description".into())
                .or_default()
                .push(String::new());
            let next = values.get("step_number").len() + 1;
            values
                .0
                .entry("step_number".into())
                .or_default()
                .push(next.to_string());
            return editor_page(state, headers, kind, id, Some(values), None).await;
        }
        action if action.starts_with("remove:") && matches!(kind, Kind::Project) => {
            let index = action
                .strip_prefix("remove:")
                .and_then(|v| v.parse::<usize>().ok())
                .unwrap_or(usize::MAX);
            for key in ["step_id", "step_title", "step_description", "step_number"] {
                if let Some(rows) = values.0.get_mut(key)
                    && index < rows.len()
                {
                    rows.remove(index);
                }
            }
            return editor_page(state, headers, kind, id, Some(values), None).await;
        }
        _ => {}
    }
    let values_for_error = FormValues(values.0.clone());
    let method = if id.is_some() {
        Method::PUT
    } else {
        Method::POST
    };
    let path = match id {
        Some(id) => format!("/api/v1/{}/{id}", kind.path()),
        None => format!("/api/v1/{}", kind.path()),
    };
    let (status, result) = api_request(
        &state,
        &session.token,
        method,
        &path,
        Some(values.payload(kind, id.is_some())),
    )
    .await;
    if status == StatusCode::CONFLICT {
        // Re-render the submitted values beside the latest owner-scoped record.
        return form_response(state, headers, values_for_error, kind, id, "conflict").await;
    }
    if !status.is_success() {
        return form_response(state, headers, values_for_error, kind, id, "invalid").await;
    }
    let Some(result) = result else {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    };
    let new_id = result["id"].as_i64().or(id);
    Redirect::to(&format!(
        "/{}/{}/edit",
        kind.path(),
        new_id.unwrap_or_default()
    ))
    .into_response()
}

async fn confirm_project_delete(
    State(state): State<WebState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Response {
    delete_confirmation(state, headers, id, Kind::Project).await
}
async fn confirm_yarn_delete(
    State(state): State<WebState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Response {
    delete_confirmation(state, headers, id, Kind::Yarn).await
}
async fn delete_confirmation(state: WebState, headers: HeaderMap, id: i64, kind: Kind) -> Response {
    let Ok(session) = session(&state, &headers).await else {
        return Redirect::to("/login").into_response();
    };
    let Some(item) = api_json(
        &state,
        &session.token,
        &format!("/api/v1/{}/{id}", kind.path()),
    )
    .await
    else {
        return StatusCode::NOT_FOUND.into_response();
    };
    let labels = Labels::new(crate::language_from_headers(&headers));
    render(
        Page {
            title: labels.delete.to_owned(),
            labels,
            csrf: csrf(&session.token),
            email: Some(session.email),
            is_admin: session.is_admin,
            body: PageBody::Delete(DeleteView {
                path: kind.path(),
                id,
                name: string(&item, "name"),
                cancel_path: format!("/{}/{id}", kind.path()),
            }),
        },
        StatusCode::OK,
    )
}
async fn delete_project(
    State(state): State<WebState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    request: Request<Body>,
) -> Response {
    delete_resource(state, headers, id, Kind::Project, request).await
}
async fn delete_yarn(
    State(state): State<WebState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    request: Request<Body>,
) -> Response {
    delete_resource(state, headers, id, Kind::Yarn, request).await
}
async fn delete_resource(
    state: WebState,
    headers: HeaderMap,
    id: i64,
    kind: Kind,
    request: Request<Body>,
) -> Response {
    let Ok(session) = session(&state, &headers).await else {
        return Redirect::to("/login").into_response();
    };
    let Some(form) = read_form(request).await else {
        return StatusCode::BAD_REQUEST.into_response();
    };
    if !valid_csrf(&session.token, &form.one("csrf")) {
        return StatusCode::FORBIDDEN.into_response();
    }
    let (status, _) = api_request(
        &state,
        &session.token,
        Method::DELETE,
        &format!("/api/v1/{}/{id}", kind.path()),
        None,
    )
    .await;
    if !status.is_success() {
        return status.into_response();
    }
    Redirect::to(&format!("/{}", kind.path())).into_response()
}
async fn toggle_project_favorite(
    State(state): State<WebState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    request: Request<Body>,
) -> Response {
    toggle_favorite(state, headers, id, Kind::Project, request).await
}
async fn toggle_yarn_favorite(
    State(state): State<WebState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    request: Request<Body>,
) -> Response {
    toggle_favorite(state, headers, id, Kind::Yarn, request).await
}
async fn toggle_favorite(
    state: WebState,
    headers: HeaderMap,
    id: i64,
    kind: Kind,
    request: Request<Body>,
) -> Response {
    let Ok(session) = session(&state, &headers).await else {
        return Redirect::to("/login").into_response();
    };
    let Some(form) = read_form(request).await else {
        return StatusCode::BAD_REQUEST.into_response();
    };
    if !valid_csrf(&session.token, &form.one("csrf")) {
        return StatusCode::FORBIDDEN.into_response();
    }
    let Some(item) = api_json(
        &state,
        &session.token,
        &format!("/api/v1/{}/{id}", kind.path()),
    )
    .await
    else {
        return StatusCode::NOT_FOUND.into_response();
    };
    let method = if item["is_favorite"].as_bool().unwrap_or(false) {
        Method::DELETE
    } else {
        Method::POST
    };
    let (status, _) = api_request(
        &state,
        &session.token,
        method,
        &format!("/api/v1/{}/{id}/favorite", kind.path()),
        None,
    )
    .await;
    if !status.is_success() {
        return status.into_response();
    }
    Redirect::to(&format!("/{}/{id}", kind.path())).into_response()
}

async fn upload_project_title(
    State(state): State<WebState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    multipart: Multipart,
) -> Response {
    upload_media(
        state,
        headers,
        id,
        Kind::Project,
        format!("/api/v1/projects/{id}/images/title"),
        true,
        multipart,
    )
    .await
}
async fn upload_project_step(
    State(state): State<WebState>,
    headers: HeaderMap,
    Path((id, step_id)): Path<(i64, i64)>,
    multipart: Multipart,
) -> Response {
    upload_media(
        state,
        headers,
        id,
        Kind::Project,
        format!("/api/v1/projects/{id}/steps/{step_id}/images"),
        true,
        multipart,
    )
    .await
}
async fn upload_project_attachment(
    State(state): State<WebState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    multipart: Multipart,
) -> Response {
    upload_media(
        state,
        headers,
        id,
        Kind::Project,
        format!("/api/v1/projects/{id}/attachments"),
        false,
        multipart,
    )
    .await
}
async fn upload_yarn_photo(
    State(state): State<WebState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    multipart: Multipart,
) -> Response {
    upload_media(
        state,
        headers,
        id,
        Kind::Yarn,
        format!("/api/v1/yarns/{id}/photos"),
        false,
        multipart,
    )
    .await
}

struct BrowserUpload {
    csrf: String,
    filename: String,
    content_type: Option<String>,
    bytes: Vec<u8>,
    alt_text: Option<String>,
}

async fn upload_media(
    state: WebState,
    headers: HeaderMap,
    id: i64,
    kind: Kind,
    api_path: String,
    has_alt_text: bool,
    mut multipart: Multipart,
) -> Response {
    let Ok(session) = session(&state, &headers).await else {
        return Redirect::to("/login").into_response();
    };
    let mut upload = BrowserUpload {
        csrf: String::new(),
        filename: String::new(),
        content_type: None,
        bytes: Vec::new(),
        alt_text: None,
    };
    let mut saw_file = false;
    loop {
        let field = match multipart.next_field().await {
            Ok(Some(field)) => field,
            Ok(None) => break,
            Err(_) => return detail(state, headers, id, kind, Some("upload")).await,
        };
        let field_name = field.name().unwrap_or_default().to_owned();
        match field_name.as_str() {
            "csrf" if upload.csrf.is_empty() => {
                let Ok(bytes) = field.bytes().await else {
                    return detail(state, headers, id, kind, Some("upload")).await;
                };
                if bytes.len() > 256 {
                    return detail(state, headers, id, kind, Some("upload")).await;
                }
                upload.csrf = String::from_utf8_lossy(&bytes).into_owned();
                if !valid_csrf(&session.token, &upload.csrf) {
                    return detail(state, headers, id, kind, Some("csrf")).await;
                }
            }
            "file" if !saw_file && valid_csrf(&session.token, &upload.csrf) => {
                saw_file = true;
                upload.filename = field
                    .file_name()
                    .unwrap_or("file")
                    .chars()
                    .map(|c| {
                        if c.is_ascii() && !c.is_ascii_control() && !matches!(c, '"' | '\\' | '/') {
                            c
                        } else {
                            '_'
                        }
                    })
                    .collect();
                upload.content_type = field.content_type().map(str::to_owned);
                let Ok(bytes) = field.bytes().await else {
                    return detail(state, headers, id, kind, Some("upload")).await;
                };
                if bytes.len() > 25 * 1024 * 1024 {
                    return StatusCode::PAYLOAD_TOO_LARGE.into_response();
                }
                upload.bytes.extend_from_slice(&bytes);
            }
            "alt_text" if has_alt_text && upload.alt_text.is_none() => {
                let Ok(bytes) = field.bytes().await else {
                    return detail(state, headers, id, kind, Some("upload")).await;
                };
                if bytes.len() > 4096 {
                    return detail(state, headers, id, kind, Some("upload")).await;
                }
                upload.alt_text = Some(String::from_utf8_lossy(&bytes).into_owned());
            }
            "alt_text" if !has_alt_text => {
                let Ok(bytes) = field.bytes().await else {
                    return detail(state, headers, id, kind, Some("upload")).await;
                };
                if bytes.len() > 4096 {
                    return detail(state, headers, id, kind, Some("upload")).await;
                }
            }
            _ => return detail(state, headers, id, kind, Some("upload")).await,
        }
    }
    if !saw_file || upload.bytes.is_empty() {
        return detail(state, headers, id, kind, Some("upload")).await;
    }
    let mut random = [0u8; 18];
    if OsRng.try_fill_bytes(&mut random).is_err() {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    }
    let boundary = format!("stricknani-{}", hex::encode(random));
    let filename = upload.filename.replace(['\\', '"'], "_");
    let content_type = upload
        .content_type
        .as_deref()
        .filter(|value| HeaderValue::from_str(value).is_ok())
        .unwrap_or("application/octet-stream");
    let mut body = Vec::with_capacity(upload.bytes.len() + 1024);
    append_part_header(
        &mut body,
        &boundary,
        &format!("form-data; name=\"file\"; filename=\"{filename}\""),
        Some(content_type),
    );
    body.extend_from_slice(&upload.bytes);
    body.extend_from_slice(b"\r\n");
    if let Some(alt_text) = upload.alt_text {
        append_part_header(&mut body, &boundary, "form-data; name=\"alt_text\"", None);
        body.extend_from_slice(alt_text.as_bytes());
        body.extend_from_slice(b"\r\n");
    }
    body.extend_from_slice(format!("--{boundary}--\r\n").as_bytes());
    let content_type = format!("multipart/form-data; boundary={boundary}");
    let request = match Request::builder()
        .method(Method::POST)
        .uri(api_path)
        .header(header::AUTHORIZATION, format!("Bearer {}", session.token))
        .header(header::CONTENT_TYPE, content_type)
        .body(Body::from(body))
    {
        Ok(request) => request,
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    let response = match state
        .api
        .clone()
        .layer(DefaultBodyLimit::max(27 * 1024 * 1024))
        .oneshot(request)
        .await
    {
        Ok(response) => response,
        Err(error) => match error {},
    };
    if response.status().is_success() {
        Redirect::to(&format!("/{}/{id}", kind.path())).into_response()
    } else {
        detail(state, headers, id, kind, Some("upload")).await
    }
}

fn append_part_header(
    body: &mut Vec<u8>,
    boundary: &str,
    disposition: &str,
    content_type: Option<&str>,
) {
    body.extend_from_slice(
        format!("--{boundary}\r\nContent-Disposition: {disposition}\r\n").as_bytes(),
    );
    if let Some(content_type) = content_type {
        body.extend_from_slice(format!("Content-Type: {content_type}\r\n").as_bytes());
    }
    body.extend_from_slice(b"\r\n");
}

async fn delete_project_image(
    State(state): State<WebState>,
    headers: HeaderMap,
    Path((id, media_id)): Path<(i64, i64)>,
    request: Request<Body>,
) -> Response {
    delete_media(
        state,
        headers,
        id,
        Kind::Project,
        media_id,
        format!("/api/v1/projects/{id}/images/{media_id}"),
        request,
    )
    .await
}
async fn delete_project_attachment(
    State(state): State<WebState>,
    headers: HeaderMap,
    Path((id, media_id)): Path<(i64, i64)>,
    request: Request<Body>,
) -> Response {
    delete_media(
        state,
        headers,
        id,
        Kind::Project,
        media_id,
        format!("/api/v1/projects/{id}/attachments/{media_id}"),
        request,
    )
    .await
}
async fn delete_yarn_photo(
    State(state): State<WebState>,
    headers: HeaderMap,
    Path((id, media_id)): Path<(i64, i64)>,
    request: Request<Body>,
) -> Response {
    delete_media(
        state,
        headers,
        id,
        Kind::Yarn,
        media_id,
        format!("/api/v1/yarns/{id}/photos/{media_id}"),
        request,
    )
    .await
}
async fn delete_media(
    state: WebState,
    headers: HeaderMap,
    id: i64,
    kind: Kind,
    _media_id: i64,
    api_path: String,
    request: Request<Body>,
) -> Response {
    let Ok(session) = session(&state, &headers).await else {
        return Redirect::to("/login").into_response();
    };
    let Some(form) = read_form(request).await else {
        return StatusCode::BAD_REQUEST.into_response();
    };
    if !valid_csrf(&session.token, &form.one("csrf")) {
        return detail(state, headers, id, kind, Some("csrf")).await;
    }
    let (status, _) = api_request(&state, &session.token, Method::DELETE, &api_path, None).await;
    if status.is_success() {
        Redirect::to(&format!("/{}/{id}", kind.path())).into_response()
    } else {
        detail(state, headers, id, kind, Some("upload")).await
    }
}

async fn categories(State(state): State<WebState>, headers: HeaderMap) -> Response {
    let Ok(session) = session(&state, &headers).await else {
        return Redirect::to("/login").into_response();
    };
    let Some(items) = api_json(&state, &session.token, "/api/v1/categories").await else {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    };
    let values = items
        .as_array()
        .into_iter()
        .flatten()
        .map(|v| CategoryView {
            id: v["id"].as_i64().unwrap_or_default(),
            name: string(v, "name"),
        })
        .collect();
    let labels = Labels::new(crate::language_from_headers(&headers));
    render(
        Page {
            title: labels.category_management.to_owned(),
            labels,
            csrf: csrf(&session.token),
            email: Some(session.email),
            is_admin: session.is_admin,
            body: PageBody::Categories(values),
        },
        StatusCode::OK,
    )
}
async fn create_category(
    State(state): State<WebState>,
    headers: HeaderMap,
    request: Request<Body>,
) -> Response {
    save_category_inner(state, headers, None, request).await
}
async fn save_category(
    State(state): State<WebState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    request: Request<Body>,
) -> Response {
    save_category_inner(state, headers, Some(id), request).await
}
async fn save_category_inner(
    state: WebState,
    headers: HeaderMap,
    id: Option<i64>,
    request: Request<Body>,
) -> Response {
    let Ok(session) = session(&state, &headers).await else {
        return Redirect::to("/login").into_response();
    };
    let Some(form) = read_form(request).await else {
        return StatusCode::BAD_REQUEST.into_response();
    };
    if !valid_csrf(&session.token, &form.one("csrf")) {
        return StatusCode::FORBIDDEN.into_response();
    }
    let path = id
        .map(|id| format!("/api/v1/categories/{id}"))
        .unwrap_or_else(|| "/api/v1/categories".to_owned());
    let method = if id.is_some() {
        Method::PUT
    } else {
        Method::POST
    };
    let (status, _) = api_request(
        &state,
        &session.token,
        method,
        &path,
        Some(serde_json::json!({"name":form.one("name")})),
    )
    .await;
    if !status.is_success() {
        return status.into_response();
    }
    Redirect::to("/categories").into_response()
}
async fn delete_category(
    State(state): State<WebState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    request: Request<Body>,
) -> Response {
    let Ok(session) = session(&state, &headers).await else {
        return Redirect::to("/login").into_response();
    };
    let Some(form) = read_form(request).await else {
        return StatusCode::BAD_REQUEST.into_response();
    };
    if !valid_csrf(&session.token, &form.one("csrf")) {
        return StatusCode::FORBIDDEN.into_response();
    }
    let (status, _) = api_request(
        &state,
        &session.token,
        Method::DELETE,
        &format!("/api/v1/categories/{id}"),
        None,
    )
    .await;
    if !status.is_success() {
        return status.into_response();
    }
    Redirect::to("/categories").into_response()
}

async fn confirm_category_delete(
    State(state): State<WebState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Response {
    let Ok(session) = session(&state, &headers).await else {
        return Redirect::to("/login").into_response();
    };
    let Some(items) = api_json(&state, &session.token, "/api/v1/categories").await else {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    };
    let Some(item) = items
        .as_array()
        .into_iter()
        .flatten()
        .find(|item| item["id"].as_i64() == Some(id))
    else {
        return StatusCode::NOT_FOUND.into_response();
    };
    let labels = Labels::new(crate::language_from_headers(&headers));
    render(
        Page {
            title: labels.delete.to_owned(),
            labels,
            csrf: csrf(&session.token),
            email: Some(session.email),
            is_admin: session.is_admin,
            body: PageBody::Delete(DeleteView {
                path: "categories",
                id,
                name: string(item, "name"),
                cancel_path: "/categories".to_owned(),
            }),
        },
        StatusCode::OK,
    )
}

fn string(value: &Value, key: &str) -> String {
    value[key].as_str().unwrap_or_default().to_owned()
}
pub(crate) fn rich_text(source: &str) -> String {
    let mut html = String::new();
    pulldown_cmark::html::push_html(
        &mut html,
        pulldown_cmark::Parser::new_ext(
            source,
            pulldown_cmark::Options::ENABLE_TABLES | pulldown_cmark::Options::ENABLE_STRIKETHROUGH,
        ),
    );
    ammonia::clean(&html)
}

async fn language(Path(language): Path<String>) -> Response {
    if !matches!(language.as_str(), "en" | "de") {
        return StatusCode::BAD_REQUEST.into_response();
    }
    let mut response = Redirect::to("/projects").into_response();
    let Ok(cookie) = HeaderValue::from_str(&format!(
        "language={language}; Path=/; SameSite=Lax; Max-Age=31536000"
    )) else {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    };
    response.headers_mut().append(header::SET_COOKIE, cookie);
    response
}

struct Card {
    id: i64,
    name: String,
    caption: String,
    preview: Option<String>,
}
struct Catalog {
    path: &'static str,
    cards: Vec<Card>,
    q: String,
    category: String,
    tag: String,
    favorite: String,
    is_project: bool,
    previous_url: Option<String>,
    next_url: Option<String>,
}
struct ImageView {
    url: String,
    thumbnail: String,
    alt: String,
    delete_path: String,
}
struct AttachmentView {
    url: String,
    name: String,
    delete_path: String,
}
struct StepView {
    id: i64,
    title: String,
    description: String,
}
struct Detail {
    id: i64,
    path: &'static str,
    favorite: bool,
    metadata: Vec<(&'static str, String)>,
    description: String,
    notes: String,
    media_error: Option<&'static str>,
    image_upload_path: String,
    attachment_upload_path: Option<String>,
    images: Vec<ImageView>,
    attachments: Vec<AttachmentView>,
    steps: Vec<StepView>,
    external_link: Option<String>,
    archive_link: Option<String>,
    archive_form_action: Option<String>,
    ocr_enabled: bool,
}
enum PageBody {
    Login(Option<&'static str>, bool),
    Catalog(Catalog),
    Detail(Detail),
    Editor(Editor),
    Delete(DeleteView),
    Categories(Vec<CategoryView>),
}

struct Editor {
    path: &'static str,
    id: Option<i64>,
    error: Option<&'static str>,
    fields: Vec<Field>,
    steps: Vec<StepForm>,
    options: Vec<Choice>,
    expected_updated_at: String,
    current_name: Option<String>,
    current_updated_at: Option<String>,
}
struct Field {
    label: &'static str,
    name: &'static str,
    value: String,
    textarea: bool,
    checkbox: bool,
}
struct StepForm {
    id: String,
    title: String,
    description: String,
    step_number: String,
}
struct Choice {
    id: String,
    name: String,
    selected: bool,
}
struct DeleteView {
    path: &'static str,
    id: i64,
    name: String,
    cancel_path: String,
}
struct CategoryView {
    id: i64,
    name: String,
}

#[derive(Template)]
#[template(path = "web.html")]
struct Page {
    title: String,
    labels: Labels,
    csrf: String,
    email: Option<String>,
    is_admin: bool,
    body: PageBody,
}

fn render(page: Page, status: StatusCode) -> Response {
    match page.render() {
        Ok(html) => {
            let mut response = (status, Html(html)).into_response();
            response
                .headers_mut()
                .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
            response
        }
        Err(_) => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }
}

struct Labels {
    language: &'static str,
    projects: &'static str,
    yarns: &'static str,
    yarn: &'static str,
    gauge: &'static str,
    sign_in: &'static str,
    create_account: &'static str,
    sign_out: &'static str,
    email: &'static str,
    password: &'static str,
    invalid_login: &'static str,
    empty: &'static str,
    previous: &'static str,
    next: &'static str,
    category: &'static str,
    brand: &'static str,
    color: &'static str,
    needles: &'static str,
    notes: &'static str,
    steps: &'static str,
    photos: &'static str,
    attachments: &'static str,
    source: &'static str,
    create: &'static str,
    edit: &'static str,
    save: &'static str,
    delete: &'static str,
    favorite: &'static str,
    unfavorite: &'static str,
    confirm_delete: &'static str,
    cancel: &'static str,
    name: &'static str,
    description: &'static str,
    tags: &'static str,
    materials: &'static str,
    link: &'static str,
    fiber: &'static str,
    weight: &'static str,
    dye_lot: &'static str,
    weight_grams: &'static str,
    length_meters: &'static str,
    ai_enhanced: &'static str,
    add_step: &'static str,
    category_management: &'static str,
    imports: &'static str,
    search: &'static str,
    tag_filter: &'static str,
    favorite_filter: &'static str,
    all_items: &'static str,
    favorites_only: &'static str,
    non_favorites: &'static str,
    apply_filters: &'static str,
    account: &'static str,
    admin_users: &'static str,
    audit_events: &'static str,
    conflict_error: &'static str,
    validation_error: &'static str,
    upload: &'static str,
    file: &'static str,
    alt_text: &'static str,
    media_error: &'static str,
    csrf_error: &'static str,
    archive_save: &'static str,
    archive_saved: &'static str,
    ocr_run: &'static str,
    ocr_language: &'static str,
    ocr_busy: &'static str,
    ocr_error: &'static str,
}
impl Labels {
    fn new(language: Language) -> Self {
        let tr = |en, de| crate::i18n::text(language, en, de);
        Self {
            language: tr("en", "de"),
            projects: tr("Projects", "Projekte"),
            yarns: tr("Yarns", "Garne"),
            yarn: tr("Yarn", "Garn"),
            gauge: tr("Gauge calculator", "Maschenprobe berechnen"),
            sign_in: tr("Sign in", "Anmelden"),
            create_account: tr("Create an account", "Konto erstellen"),
            sign_out: tr("Sign out", "Abmelden"),
            email: tr("Email", "E-Mail"),
            password: tr("Password", "Passwort"),
            invalid_login: tr(
                "Invalid email or password",
                "Ungültige E-Mail oder ungültiges Passwort",
            ),
            empty: tr("Your collection is empty.", "Deine Sammlung ist leer."),
            previous: tr("Previous", "Zurück"),
            next: tr("Next", "Weiter"),
            category: tr("Category", "Kategorie"),
            brand: tr("Brand", "Marke"),
            color: tr("Colorway", "Farbe"),
            needles: tr("Needles", "Nadeln"),
            notes: tr("Notes", "Notizen"),
            steps: tr("Steps", "Schritte"),
            photos: tr("Photos", "Fotos"),
            attachments: tr("Attachments", "Anhänge"),
            source: tr("Original source", "Originalquelle"),
            create: tr("Create", "Erstellen"),
            edit: tr("Edit", "Bearbeiten"),
            save: tr("Save", "Speichern"),
            delete: tr("Delete", "Löschen"),
            favorite: tr("Add to favorites", "Zu Favoriten hinzufügen"),
            unfavorite: tr("Remove from favorites", "Aus Favoriten entfernen"),
            confirm_delete: tr("Delete this item?", "Diesen Eintrag löschen?"),
            cancel: tr("Cancel", "Abbrechen"),
            name: tr("Name", "Name"),
            description: tr("Description", "Beschreibung"),
            tags: tr("Tags (one per line)", "Schlagwörter (eins pro Zeile)"),
            materials: tr("Other materials", "Weitere Materialien"),
            link: tr("Link", "Link"),
            fiber: tr("Fiber content", "Faserzusammensetzung"),
            weight: tr("Weight category", "Garnstärke"),
            dye_lot: tr("Dye lot", "Partie"),
            weight_grams: tr("Weight in grams", "Gewicht in Gramm"),
            length_meters: tr("Length in meters", "Länge in Metern"),
            ai_enhanced: tr("AI enhanced", "Mit KI ergänzt"),
            add_step: tr("Add step", "Schritt hinzufügen"),
            category_management: tr("Manage categories", "Kategorien verwalten"),
            imports: tr("Imports", "Daten importieren"),
            search: tr("Search", "Suche"),
            tag_filter: tr("Tag", "Schlagwort"),
            favorite_filter: tr("Favorites", "Favoriten"),
            all_items: tr("All items", "Alle Einträge"),
            favorites_only: tr("Favorites only", "Nur Favoriten"),
            non_favorites: tr("Exclude favorites", "Favoriten ausschließen"),
            apply_filters: tr("Apply filters", "Filter anwenden"),
            account: tr("Account", "Konto"),
            admin_users: tr("User accounts", "Benutzerkonten"),
            audit_events: tr("Audit events", "Audit-Protokoll"),
            conflict_error: tr(
                "This item changed since you opened it. Your entries are still shown. Review the current saved version before saving again.",
                "Dieser Eintrag wurde geändert, seit du ihn geöffnet hast. Deine Eingaben bleiben erhalten. Prüfe die aktuelle Version, bevor du erneut speicherst.",
            ),
            validation_error: tr(
                "Please check the form and try again.",
                "Bitte prüfe das Formular und versuche es erneut.",
            ),
            upload: tr("Upload", "Hochladen"),
            file: tr("File", "Datei"),
            alt_text: tr("Alternative text", "Alternativtext"),
            media_error: tr(
                "The file could not be uploaded or deleted. Check the file and try again.",
                "Die Datei konnte nicht hochgeladen oder gelöscht werden. Prüfe die Datei und versuche es erneut.",
            ),
            csrf_error: tr(
                "The form expired. Reload this page and try again.",
                "Das Formular ist abgelaufen. Lade die Seite neu und versuche es erneut.",
            ),
            archive_save: tr("Save link to Wayback", "Link bei Wayback speichern"),
            archive_saved: tr("Saved to Wayback", "Bei Wayback gespeichert"),
            ocr_run: tr("Read text", "Text erkennen"),
            ocr_language: tr("OCR language", "OCR-Sprache"),
            ocr_busy: tr("Reading text…", "Text wird erkannt …"),
            ocr_error: tr(
                "Text recognition failed. Try again.",
                "Texterkennung fehlgeschlagen. Bitte erneut versuchen.",
            ),
        }
    }
}
