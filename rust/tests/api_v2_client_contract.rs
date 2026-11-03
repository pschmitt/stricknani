//! Client-visible contract of `/api/v2` as the Android app consumes it (T121.4).
//!
//! Every request is authenticated with an API-purpose Bearer PAT, goes through the fully composed
//! application (`/api/v2/...` plus the protected `/media/...` routes) and is checked against the
//! shapes the Kotlin DTOs in `android/app/src/main/kotlin/blue/anika/wolle/data/api/` decode:
//! required keys, nullability and list-vs-null. The Android `Json` is configured with
//! `ignoreUnknownKeys`, `isLenient` and `explicitNulls = false`, so extra keys and `null` for a
//! nullable field are fine, but a missing required key or a `null` for a non-null list is not.
//!
//! This file is the executable half of `docs/android-v2-parity.md`. It deliberately does not
//! repeat the exhaustive cookie/CSRF, ownership and validation coverage of the other
//! `api_v2_*.rs` suites.

use std::path::PathBuf;

use axum::{
    Router,
    body::Body,
    http::{HeaderMap, Request, StatusCode},
};
use http_body_util::BodyExt;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use stricknani_server::{app_with_state, db};
use tower::ServiceExt;

const OWNER_PAT: &str = "client-contract-owner-api-pat";
const OTHER_PAT: &str = "client-contract-other-api-pat";
const ADMIN_PAT: &str = "client-contract-admin-api-pat";
const OWNER_SESSION: &str = "client-contract-owner-browser-session";
const OWNER_BROWSER_AS_BEARER: &str = "client-contract-owner-browser-session";
const EXPIRED_PAT: &str = "client-contract-expired-api-pat";
const REVOKED_PAT: &str = "client-contract-revoked-api-pat";
const INACTIVE_PAT: &str = "client-contract-inactive-api-pat";
const OWNER_EMAIL: &str = "contract-owner@example.test";
const OWNER_PASSWORD: &str = "contract-owner-password";
const ORIGIN: &str = "https://app.example.test";
const HOST: &str = "app.example.test";

struct Fixture {
    app: Router,
    pool: SqlitePool,
    owner_id: i64,
    other_id: i64,
    _media_root: tempfile::TempDir,
}

async fn fixture() -> Fixture {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("database migrates");
    let owner_id = db::create_user(&pool, OWNER_EMAIL, OWNER_PASSWORD.into())
        .await
        .expect("owner exists");
    let other_id = db::create_user(
        &pool,
        "contract-other@example.test",
        "contract-other-password".into(),
    )
    .await
    .expect("other user exists");
    let admin_id = db::create_user(
        &pool,
        "contract-admin@example.test",
        "contract-admin-password".into(),
    )
    .await
    .expect("admin exists");
    let inactive_id = db::create_user(
        &pool,
        "contract-inactive@example.test",
        "contract-inactive-password".into(),
    )
    .await
    .expect("inactive user exists");
    sqlx::query("UPDATE users SET is_admin = 1 WHERE id = ?")
        .bind(admin_id)
        .execute(&pool)
        .await
        .unwrap();
    sqlx::query("UPDATE users SET active = 0 WHERE id = ?")
        .bind(inactive_id)
        .execute(&pool)
        .await
        .unwrap();
    add_token(
        &pool,
        owner_id,
        OWNER_PAT,
        "api",
        "unixepoch() + 86400",
        false,
    )
    .await;
    add_token(
        &pool,
        owner_id,
        OWNER_SESSION,
        "browser",
        "unixepoch() + 86400",
        false,
    )
    .await;
    add_token(
        &pool,
        owner_id,
        EXPIRED_PAT,
        "api",
        "unixepoch() - 10",
        false,
    )
    .await;
    add_token(
        &pool,
        owner_id,
        REVOKED_PAT,
        "api",
        "unixepoch() + 86400",
        true,
    )
    .await;
    add_token(
        &pool,
        other_id,
        OTHER_PAT,
        "api",
        "unixepoch() + 86400",
        false,
    )
    .await;
    add_token(
        &pool,
        admin_id,
        ADMIN_PAT,
        "api",
        "unixepoch() + 86400",
        false,
    )
    .await;
    add_token(
        &pool,
        inactive_id,
        INACTIVE_PAT,
        "api",
        "unixepoch() + 86400",
        false,
    )
    .await;
    let media_root = tempfile::tempdir().expect("media root is created");
    let static_root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("static");
    let state = db::AppState::new(pool.clone())
        .with_secure_cookies(true)
        .with_media_root(media_root.path().to_path_buf());
    Fixture {
        app: app_with_state(static_root, state),
        pool,
        owner_id,
        other_id,
        _media_root: media_root,
    }
}

async fn add_token(
    pool: &SqlitePool,
    user_id: i64,
    token: &str,
    purpose: &str,
    expires_sql: &str,
    revoked: bool,
) {
    let revoked_sql = if revoked { "unixepoch()" } else { "NULL" };
    sqlx::query(&format!(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, expires_at, purpose, revoked_at) \
         VALUES (?, 'client contract fixture', ?, unixepoch(), {expires_sql}, ?, {revoked_sql})"
    ))
    .bind(user_id)
    .bind(hex::encode(Sha256::digest(token.as_bytes())))
    .bind(purpose)
    .execute(pool)
    .await
    .expect("token is stored");
}

struct Reply {
    status: StatusCode,
    headers: HeaderMap,
    raw: Vec<u8>,
}

impl Reply {
    fn body(&self) -> Value {
        if self.raw.is_empty() {
            Value::Null
        } else {
            serde_json::from_slice(&self.raw).unwrap_or(Value::Null)
        }
    }

    fn data(&self) -> Value {
        self.body()["data"].clone()
    }
}

async fn send(app: &Router, request: Request<Body>) -> Reply {
    let response = app.clone().oneshot(request).await.unwrap();
    let status = response.status();
    let headers = response.headers().clone();
    let raw = response
        .into_body()
        .collect()
        .await
        .unwrap()
        .to_bytes()
        .to_vec();
    Reply {
        status,
        headers,
        raw,
    }
}

/// A request the way the app's OkHttp stack sends it: Bearer only, no cookie, no Origin, no CSRF.
fn api(method: &str, path: &str, token: &str, body: Option<Value>) -> Request<Body> {
    let mut builder = Request::builder()
        .method(method)
        .uri(path)
        .header("authorization", format!("Bearer {token}"));
    if body.is_some() {
        builder = builder.header("content-type", "application/json");
    }
    builder
        .body(body.map_or_else(Body::empty, |body| Body::from(body.to_string())))
        .unwrap()
}

async fn call(app: &Router, method: &str, path: &str, token: &str, body: Option<Value>) -> Reply {
    send(app, api(method, path, token, body)).await
}

async fn call_ok(
    app: &Router,
    method: &str,
    path: &str,
    token: &str,
    body: Option<Value>,
    expected: StatusCode,
) -> Value {
    let reply = call(app, method, path, token, body).await;
    assert_eq!(
        reply.status,
        expected,
        "{method} {path}: {}",
        String::from_utf8_lossy(&reply.raw)
    );
    reply.data()
}

/// A multipart field: name, optional (file name, content type) and the content.
type Part<'a> = (&'a str, Option<(&'a str, &'a str)>, &'a [u8]);

fn multipart(path: &str, token: &str, parts: &[Part<'_>]) -> Request<Body> {
    let boundary = "client-contract-boundary";
    let mut body = Vec::new();
    for (name, file, content) in parts {
        body.extend_from_slice(format!("--{boundary}\r\n").as_bytes());
        match file {
            Some((filename, content_type)) => body.extend_from_slice(
                format!(
                    "Content-Disposition: form-data; name=\"{name}\"; filename=\"{filename}\"\r\nContent-Type: {content_type}\r\n\r\n"
                )
                .as_bytes(),
            ),
            None => body.extend_from_slice(
                format!("Content-Disposition: form-data; name=\"{name}\"\r\n\r\n").as_bytes(),
            ),
        }
        body.extend_from_slice(content);
        body.extend_from_slice(b"\r\n");
    }
    body.extend_from_slice(format!("--{boundary}--\r\n").as_bytes());
    Request::post(path)
        .header("authorization", format!("Bearer {token}"))
        .header(
            "content-type",
            format!("multipart/form-data; boundary={boundary}"),
        )
        .body(Body::from(body))
        .unwrap()
}

fn png_bytes() -> Vec<u8> {
    let image = image::DynamicImage::new_rgba8(4, 4);
    let mut output = std::io::Cursor::new(Vec::new());
    image
        .write_to(&mut output, image::ImageFormat::Png)
        .expect("test image encodes");
    output.into_inner()
}

/// A path relative to the server root, exactly as the app resolves a returned `url`.
fn media_get(path: &str, token: &str) -> Request<Body> {
    api("GET", path, token, None)
}

// --- Kotlin DTO shape checks --------------------------------------------------------------------

#[derive(Clone, Copy, Debug)]
enum Kind {
    Int,
    Str,
    Bool,
    Arr,
}

/// How a Kotlin property reacts to the key: `Req` has no default and is non-null, `Nullable` is
/// `T? = null` (absent and `null` both decode), `Defaulted` is non-null with a default (absent
/// decodes, `null` does not).
#[derive(Clone, Copy, Debug)]
enum Need {
    Req,
    Nullable,
    Defaulted,
}

type Spec = &'static [(&'static str, Need, Kind)];

use Kind::{Arr, Bool, Int, Str};
use Need::{Defaulted, Nullable, Req};

/// `ProjectV2Dto`.
const PROJECT: Spec = &[
    ("id", Req, Int),
    ("name", Req, Str),
    ("category", Nullable, Str),
    ("yarn", Nullable, Str),
    ("needles", Nullable, Str),
    ("stitch_sample", Nullable, Str),
    ("description", Nullable, Str),
    ("description_html", Nullable, Str),
    ("notes", Nullable, Str),
    ("notes_html", Nullable, Str),
    ("other_materials", Nullable, Str),
    ("other_materials_html", Nullable, Str),
    ("tags", Defaulted, Arr),
    ("link", Nullable, Str),
    ("link_archive", Nullable, Str),
    ("is_ai_enhanced", Req, Bool),
    ("is_favorite", Req, Bool),
    ("created_at", Req, Str),
    ("updated_at", Req, Str),
    ("yarn_ids", Defaulted, Arr),
    ("steps", Defaulted, Arr),
    ("images", Defaulted, Arr),
    ("attachments", Defaulted, Arr),
];

/// `StepV2Dto`.
const STEP: Spec = &[
    ("id", Req, Int),
    ("title", Req, Str),
    ("description", Nullable, Str),
    ("description_html", Nullable, Str),
    ("step_number", Req, Int),
];

/// `YarnV2Dto`.
const YARN: Spec = &[
    ("id", Req, Int),
    ("name", Req, Str),
    ("description", Nullable, Str),
    ("description_html", Nullable, Str),
    ("brand", Nullable, Str),
    ("colorway", Nullable, Str),
    ("dye_lot", Nullable, Str),
    ("fiber_content", Nullable, Str),
    ("weight_category", Nullable, Str),
    ("recommended_needles", Nullable, Str),
    ("weight_grams", Nullable, Int),
    ("length_meters", Nullable, Int),
    ("notes", Nullable, Str),
    ("notes_html", Nullable, Str),
    ("link", Nullable, Str),
    ("link_archive", Nullable, Str),
    ("is_ai_enhanced", Req, Bool),
    ("is_favorite", Req, Bool),
    ("created_at", Req, Str),
    ("updated_at", Req, Str),
    ("project_ids", Defaulted, Arr),
    ("photos", Defaulted, Arr),
];

/// `ProjectListItemDto`.
const PROJECT_ITEM: Spec = &[
    ("id", Req, Int),
    ("name", Req, Str),
    ("category", Nullable, Str),
    ("tags", Defaulted, Arr),
    ("is_favorite", Req, Bool),
    ("updated_at", Req, Str),
    ("preview_url", Nullable, Str),
];

/// `YarnListItemDto`.
const YARN_ITEM: Spec = &[
    ("id", Req, Int),
    ("name", Req, Str),
    ("brand", Nullable, Str),
    ("colorway", Nullable, Str),
    ("weight_category", Nullable, Str),
    ("is_favorite", Req, Bool),
    ("updated_at", Req, Str),
    ("preview_url", Nullable, Str),
];

/// `ImageDto` (every property but `step_id` is non-null and has no default).
const IMAGE: Spec = &[
    ("id", Req, Int),
    ("url", Req, Str),
    ("thumbnail_url", Req, Str),
    ("alt_text", Req, Str),
    ("image_type", Req, Str),
    ("is_title_image", Req, Bool),
    ("is_stitch_sample", Req, Bool),
    ("step_id", Nullable, Int),
];

/// `AttachmentDto`.
const ATTACHMENT: Spec = &[
    ("id", Req, Int),
    ("filename", Req, Str),
    ("original_filename", Req, Str),
    ("content_type", Req, Str),
    ("size_bytes", Req, Int),
    ("url", Req, Str),
];

/// `YarnPhotoDto`.
const PHOTO: Spec = &[
    ("id", Req, Int),
    ("url", Req, Str),
    ("thumbnail_url", Req, Str),
    ("alt_text", Req, Str),
    ("is_primary", Req, Bool),
];

/// `CategoryDto`.
const CATEGORY: Spec = &[("id", Req, Int), ("name", Req, Str)];

fn kind_matches(value: &Value, kind: Kind) -> bool {
    match kind {
        Int => value.is_i64() || value.is_u64(),
        Str => value.is_string(),
        Bool => value.is_boolean(),
        Arr => value.is_array(),
    }
}

/// Assert `value` decodes into the Kotlin DTO described by `spec`.
fn assert_decodes(value: &Value, spec: Spec, context: &str) {
    let object = value
        .as_object()
        .unwrap_or_else(|| panic!("{context}: expected an object, got {value}"));
    for (key, need, kind) in spec {
        match (object.get(*key), need) {
            (None, Need::Req) => panic!("{context}: required key `{key}` is missing in {value}"),
            (None | Some(Value::Null), Need::Nullable) | (None, Need::Defaulted) => {}
            (Some(Value::Null), Need::Req | Need::Defaulted) => {
                panic!("{context}: non-null key `{key}` is null in {value}")
            }
            (Some(found), _) => assert!(
                kind_matches(found, *kind),
                "{context}: key `{key}` should be {kind:?}, got {found}"
            ),
        }
    }
}

fn assert_all_decode(values: &Value, spec: Spec, context: &str) {
    for (index, value) in values.as_array().expect("array").iter().enumerate() {
        assert_decodes(value, spec, &format!("{context}[{index}]"));
    }
}

/// Decode a full project the way the Android mapper (`ProjectV2Dto.toV1`) consumes it.
fn assert_project(value: &Value, context: &str) {
    assert_decodes(value, PROJECT, context);
    assert_all_decode(&value["steps"], STEP, &format!("{context}.steps"));
    assert_all_decode(&value["images"], IMAGE, &format!("{context}.images"));
    assert_all_decode(
        &value["attachments"],
        ATTACHMENT,
        &format!("{context}.attachments"),
    );
}

fn assert_yarn(value: &Value, context: &str) {
    assert_decodes(value, YARN, context);
    assert_all_decode(&value["photos"], PHOTO, &format!("{context}.photos"));
}

/// The `{error:{code,message,fields?,current?}}` envelope (`V2ConflictEnvelope`/`ApiErrorEnvelopeDto`).
fn assert_error(reply: &Reply, status: StatusCode, code: &str) -> Value {
    assert_eq!(
        reply.status,
        status,
        "unexpected status: {}",
        String::from_utf8_lossy(&reply.raw)
    );
    let body = reply.body();
    assert!(
        body.get("detail").is_none(),
        "v1 `detail` must not appear in v2 errors: {body}"
    );
    let error = body["error"].clone();
    assert_eq!(error["code"], code, "{body}");
    assert!(error["message"].is_string(), "{body}");
    if let Some(fields) = error.get("fields") {
        for (field, message) in fields.as_object().expect("fields is an object") {
            assert!(
                message.is_string(),
                "fields.{field} must be a string: {body}"
            );
        }
    }
    error
}

// --- 1. Authentication and the capability probe -------------------------------------------------

#[tokio::test]
async fn meta_probe_reports_the_pat_owner_and_rejects_bad_bearers_without_cookie_fallback() {
    let fixture = fixture().await;

    let reply = call(&fixture.app, "GET", "/api/v2/meta", OWNER_PAT, None).await;
    assert_eq!(reply.status, StatusCode::OK);
    assert!(
        reply
            .headers
            .get("cache-control")
            .is_some_and(|value| value.to_str().unwrap().contains("no-store")),
        "authenticated API responses are never cached"
    );
    let data = reply.data();
    assert!(data["version"].is_string());
    assert!(data["build_id"].is_string());
    assert_eq!(data["is_admin"], false);
    // `MetaV2UserDto`: id (Long), email, is_admin (default false), avatar_url (nullable).
    assert_decodes(
        &data["user"],
        &[
            ("id", Req, Int),
            ("email", Req, Str),
            ("is_admin", Defaulted, Bool),
            ("avatar_url", Nullable, Str),
        ],
        "meta.user",
    );
    assert_eq!(data["user"]["id"], fixture.owner_id);
    assert_eq!(data["user"]["email"], OWNER_EMAIL);
    assert_eq!(data["user"]["is_admin"], false);

    let admin = call(&fixture.app, "GET", "/api/v2/meta", ADMIN_PAT, None).await;
    assert_eq!(admin.data()["is_admin"], true);
    assert_eq!(admin.data()["user"]["is_admin"], true);

    // No credentials: still 200 so the app can tell "v2 server, no user" from "no v2".
    let anonymous = send(
        &fixture.app,
        Request::get("/api/v2/meta").body(Body::empty()).unwrap(),
    )
    .await;
    assert_eq!(anonymous.status, StatusCode::OK);
    assert!(anonymous.data()["user"].is_null());
    assert!(anonymous.data()["build_id"].is_string());

    // Everything that is not a live API-purpose PAT is 401 `session_expired`, never a downgrade.
    for (label, token) in [
        ("unknown", "client-contract-unknown-token"),
        ("expired", EXPIRED_PAT),
        ("revoked", REVOKED_PAT),
        ("inactive owner", INACTIVE_PAT),
        ("browser-purpose token", OWNER_BROWSER_AS_BEARER),
    ] {
        let reply = call(&fixture.app, "GET", "/api/v2/meta", token, None).await;
        assert_error(&reply, StatusCode::UNAUTHORIZED, "session_expired");
        assert!(
            !String::from_utf8_lossy(&reply.raw).contains(OWNER_EMAIL),
            "{label}"
        );
    }

    // An invalid Bearer next to a perfectly valid session cookie must not authenticate as the
    // cookie's user (and the probe must not report "no user" either).
    let with_cookie = send(
        &fixture.app,
        Request::get("/api/v2/meta")
            .header("authorization", "Bearer client-contract-unknown-token")
            .header(
                "cookie",
                format!("__Host-stricknani_session={OWNER_SESSION}"),
            )
            .body(Body::empty())
            .unwrap(),
    )
    .await;
    assert_error(&with_cookie, StatusCode::UNAUTHORIZED, "session_expired");

    // Without a Bearer the same cookie is accepted on a read route (browser clients).
    let cookie_only = send(
        &fixture.app,
        Request::get("/api/v2/meta")
            .header(
                "cookie",
                format!("__Host-stricknani_session={OWNER_SESSION}"),
            )
            .body(Body::empty())
            .unwrap(),
    )
    .await;
    assert_eq!(cookie_only.data()["user"]["id"], fixture.owner_id);

    // A non-Bearer scheme and a repeated Authorization header are both invalid.
    let basic = send(
        &fixture.app,
        Request::get("/api/v2/meta")
            .header("authorization", format!("Basic {OWNER_PAT}"))
            .body(Body::empty())
            .unwrap(),
    )
    .await;
    assert_error(&basic, StatusCode::UNAUTHORIZED, "session_expired");
    let duplicated = send(
        &fixture.app,
        Request::get("/api/v2/meta")
            .header("authorization", format!("Bearer {OWNER_PAT}"))
            .header("authorization", format!("Bearer {OWNER_PAT}"))
            .body(Body::empty())
            .unwrap(),
    )
    .await;
    assert_error(&duplicated, StatusCode::UNAUTHORIZED, "session_expired");
}

/// Every route `ProjectsV2Api`, `YarnsV2Api`, `CategoriesV2Api`, `AdminApi` and the v2 sync
/// shape use, as (method, path with ids that do not need to exist).
const ANDROID_ROUTES: &[(&str, &str)] = &[
    ("GET", "/api/v2/projects?page=1"),
    ("GET", "/api/v2/projects/1"),
    ("POST", "/api/v2/projects"),
    ("PUT", "/api/v2/projects/1"),
    ("DELETE", "/api/v2/projects/1"),
    ("PUT", "/api/v2/projects/1/favorite"),
    ("DELETE", "/api/v2/projects/1/favorite"),
    ("POST", "/api/v2/projects/1/images/title"),
    ("POST", "/api/v2/projects/1/steps/1/images"),
    ("POST", "/api/v2/projects/1/attachments"),
    ("DELETE", "/api/v2/projects/1/images/1"),
    ("DELETE", "/api/v2/projects/1/attachments/1"),
    ("GET", "/api/v2/yarns?page=1"),
    ("GET", "/api/v2/yarns/1"),
    ("POST", "/api/v2/yarns"),
    ("PUT", "/api/v2/yarns/1"),
    ("DELETE", "/api/v2/yarns/1"),
    ("PUT", "/api/v2/yarns/1/favorite"),
    ("DELETE", "/api/v2/yarns/1/favorite"),
    ("POST", "/api/v2/yarns/1/photos"),
    ("DELETE", "/api/v2/yarns/1/photos/1"),
    ("GET", "/api/v2/categories"),
    ("POST", "/api/v2/categories"),
    ("PUT", "/api/v2/categories/1"),
    ("DELETE", "/api/v2/categories/1"),
    ("GET", "/api/v2/projects/1/history?page=1"),
    ("GET", "/api/v2/projects/1/history/1"),
    ("GET", "/api/v2/yarns/1/history?page=1"),
    ("GET", "/api/v2/yarns/1/history/1"),
    ("GET", "/api/v2/activity?limit=10"),
    ("GET", "/api/v2/sync/projects"),
    ("GET", "/api/v2/sync/yarns"),
    ("GET", "/api/v2/admin/users?page=1"),
    ("GET", "/api/v2/admin/audit?page=1"),
];

#[tokio::test]
async fn every_android_route_requires_a_valid_bearer_and_never_falls_back_to_the_cookie() {
    let fixture = fixture().await;
    for (method, path) in ANDROID_ROUTES {
        let build = |bearer: Option<&str>, cookie: bool| {
            let mut builder = Request::builder().method(*method).uri(*path);
            if let Some(token) = bearer {
                builder = builder.header("authorization", format!("Bearer {token}"));
            }
            if cookie {
                // Same-origin browser headers, so a cookie fallback would get as far as CSRF.
                builder = builder
                    .header(
                        "cookie",
                        format!("__Host-stricknani_session={OWNER_SESSION}"),
                    )
                    .header("host", HOST)
                    .header("origin", ORIGIN)
                    .header("sec-fetch-site", "same-origin");
            }
            if !matches!(*method, "GET" | "DELETE") {
                builder = builder.header("content-type", "application/json");
            }
            builder.body(Body::from("{}")).unwrap()
        };

        let invalid = send(
            &fixture.app,
            build(Some("client-contract-unknown-token"), true),
        )
        .await;
        assert_eq!(
            invalid.status,
            StatusCode::UNAUTHORIZED,
            "{method} {path} must reject an invalid Bearer even with a valid cookie"
        );
        assert_error(&invalid, StatusCode::UNAUTHORIZED, "session_expired");

        let anonymous = send(&fixture.app, build(None, false)).await;
        // Anonymous writes are stopped by the browser origin check (403 `csrf_failed`) before the
        // session check; either way nothing is reachable without credentials.
        assert!(
            matches!(
                anonymous.status,
                StatusCode::UNAUTHORIZED | StatusCode::FORBIDDEN
            ),
            "{method} {path} must reject anonymous callers, got {}",
            anonymous.status
        );

        // A valid PAT is accepted: the route exists (never 404/405 for the method) and does not
        // demand Origin or CSRF. The id 1 may or may not exist, so only auth outcomes are pinned.
        let valid = call(&fixture.app, method, path, OWNER_PAT, Some(json!({}))).await;
        assert_ne!(valid.status, StatusCode::UNAUTHORIZED, "{method} {path}");
        assert_ne!(
            valid.status,
            StatusCode::METHOD_NOT_ALLOWED,
            "{method} {path}"
        );
        if path.starts_with("/api/v2/admin") {
            // A PAT of a non-administrator reaches the admin gate and is refused there.
            assert_error(&valid, StatusCode::FORBIDDEN, "admin_required");
        } else {
            assert_ne!(valid.status, StatusCode::FORBIDDEN, "{method} {path}");
        }
    }
}

#[tokio::test]
async fn native_token_login_mints_a_pat_that_the_probe_and_resources_accept() {
    let fixture = fixture().await;
    let login = send(
        &fixture.app,
        Request::post("/api/v2/auth/token")
            .header("content-type", "application/json")
            .body(Body::from(
                json!({"email": OWNER_EMAIL, "password": OWNER_PASSWORD, "token_name": "Contract"})
                    .to_string(),
            ))
            .unwrap(),
    )
    .await;
    assert_eq!(
        login.status,
        StatusCode::OK,
        "{}",
        String::from_utf8_lossy(&login.raw)
    );
    // v1 answered `{token}`; v2 wraps it: the Android `TokenMintResponse` must read `data.token`.
    let body = login.body();
    assert!(body.get("token").is_none(), "{body}");
    let token = body["data"]["token"]
        .as_str()
        .expect("token string")
        .to_owned();

    let meta = call(&fixture.app, "GET", "/api/v2/meta", &token, None).await;
    assert_eq!(meta.data()["user"]["email"], OWNER_EMAIL);
    let categories = call(&fixture.app, "GET", "/api/v2/categories", &token, None).await;
    assert_eq!(categories.status, StatusCode::OK);

    let wrong = send(
        &fixture.app,
        Request::post("/api/v2/auth/token")
            .header("content-type", "application/json")
            .body(Body::from(
                json!({"email": OWNER_EMAIL, "password": "definitely-wrong-password"}).to_string(),
            ))
            .unwrap(),
    )
    .await;
    // The app maps 401/403 to "invalid credentials".
    assert_error(&wrong, StatusCode::UNAUTHORIZED, "invalid_credentials");
}

// --- 2. Project and yarn payloads, writes and replay semantics ----------------------------------

#[tokio::test]
async fn project_writes_decode_as_the_android_dtos_and_replay_deletes_are_idempotent() {
    let fixture = fixture().await;
    let app = &fixture.app;

    let yarn = call_ok(
        app,
        "POST",
        "/api/v2/yarns",
        OWNER_PAT,
        Some(json!({"name": "Linked yarn"})),
        StatusCode::CREATED,
    )
    .await;
    assert_yarn(&yarn, "created yarn");
    let yarn_id = yarn["id"].as_i64().unwrap();

    // Kotlin's default Json omits default-valued properties, so a minimal create is just the name.
    let sparse = call_ok(
        app,
        "POST",
        "/api/v2/projects",
        OWNER_PAT,
        Some(json!({"name": "Sparse"})),
        StatusCode::CREATED,
    )
    .await;
    assert_project(&sparse, "sparse create");
    assert_eq!(sparse["tags"], json!([]));
    assert_eq!(sparse["is_favorite"], false);

    // The full `ProjectWriteRequest` an offline edit replays, with the optional `id`/`image_urls`
    // keys the app serialises for steps.
    let created = call_ok(
        app,
        "POST",
        "/api/v2/projects",
        OWNER_PAT,
        Some(json!({
            "name": "Full",
            "category": "Sweaters",
            "needles": "4 mm",
            "stitch_sample": "22 sts",
            "description": "**bold** text",
            "notes": "n",
            "other_materials": "buttons",
            "tags": ["winter gift", "wool"],
            "link": "https://example.test/pattern",
            "is_ai_enhanced": false,
            "yarn_ids": [yarn_id],
            "steps": [
                {"title": "Cast on", "description": "d1", "step_number": 1, "image_urls": []},
                {"title": "Knit", "step_number": 2}
            ],
            "image_urls": [],
            "expected_updated_at": null
        })),
        StatusCode::CREATED,
    )
    .await;
    assert_project(&created, "full create");
    let project_id = created["id"].as_i64().unwrap();
    assert_eq!(created["category"], "Sweaters");
    // v2 keeps each tag verbatim (v1 would have split "winter gift" into two).
    assert_eq!(created["tags"], json!(["winter gift", "wool"]));
    assert_eq!(created["yarn_ids"], json!([yarn_id]));
    assert!(
        created["description_html"]
            .as_str()
            .unwrap()
            .contains("<strong>bold</strong>")
    );
    let steps = created["steps"].as_array().unwrap();
    assert_eq!(steps.len(), 2);
    assert_eq!(steps[0]["step_number"], 1);

    // The yarn side of the link is visible through the yarn DTO.
    let yarn_after = call_ok(
        app,
        "GET",
        &format!("/api/v2/yarns/{yarn_id}"),
        OWNER_PAT,
        None,
        StatusCode::OK,
    )
    .await;
    assert_eq!(yarn_after["project_ids"], json!([project_id]));

    // Replay of an edit: reorder the steps, keep the ids so step images survive, echo
    // `updated_at` verbatim as `expected_updated_at`.
    let first = steps[0]["id"].as_i64().unwrap();
    let second = steps[1]["id"].as_i64().unwrap();
    let updated = call_ok(
        app,
        "PUT",
        &format!("/api/v2/projects/{project_id}"),
        OWNER_PAT,
        Some(json!({
            "name": "Full renamed",
            "yarn_ids": [yarn_id],
            "steps": [
                {"id": second, "title": "Knit", "step_number": 1},
                {"id": first, "title": "Cast on", "description": "d1", "step_number": 2}
            ],
            "expected_updated_at": created["updated_at"]
        })),
        StatusCode::OK,
    )
    .await;
    assert_project(&updated, "update");
    assert_eq!(updated["name"], "Full renamed");
    let ids: Vec<i64> = updated["steps"]
        .as_array()
        .unwrap()
        .iter()
        .map(|s| s["id"].as_i64().unwrap())
        .collect();
    assert_eq!(
        ids,
        vec![second, first],
        "step ids are preserved by an update"
    );

    // `image_urls` on update is rejected: the app only ever uploads media over multipart.
    let reject = call(
        app,
        "PUT",
        &format!("/api/v2/projects/{project_id}"),
        OWNER_PAT,
        Some(json!({"name": "x", "image_urls": ["https://example.test/a.png"]})),
    )
    .await;
    let error = assert_error(
        &reject,
        StatusCode::UNPROCESSABLE_ENTITY,
        "validation_error",
    );
    assert!(error["fields"]["image_urls"].is_string());

    // Detail and list agree on identity, and the list item decodes as `ProjectListItemDto`.
    let detail = call_ok(
        app,
        "GET",
        &format!("/api/v2/projects/{project_id}"),
        OWNER_PAT,
        None,
        StatusCode::OK,
    )
    .await;
    assert_eq!(detail["updated_at"], updated["updated_at"]);
    let list = call(app, "GET", "/api/v2/projects", OWNER_PAT, None).await;
    assert_eq!(list.status, StatusCode::OK);
    assert_all_decode(&list.data(), PROJECT_ITEM, "project list");

    // Validation failures carry `fields` as a string map (the app only shows `message`).
    let invalid = call(
        app,
        "POST",
        "/api/v2/projects",
        OWNER_PAT,
        Some(json!({"name": "  "})),
    )
    .await;
    let error = assert_error(
        &invalid,
        StatusCode::UNPROCESSABLE_ENTITY,
        "validation_error",
    );
    assert!(error["fields"]["name"].is_string());

    // Replay semantics (`replayDelete`, `dropDeletedProject`): delete is 204 with no body, a
    // repeated delete is a plain 404 the app treats as success, and an update of a deleted
    // project is a 404 (not a 409).
    let delete = call(
        app,
        "DELETE",
        &format!("/api/v2/projects/{project_id}"),
        OWNER_PAT,
        None,
    )
    .await;
    assert_eq!(delete.status, StatusCode::NO_CONTENT);
    assert!(delete.raw.is_empty());
    let again = call(
        app,
        "DELETE",
        &format!("/api/v2/projects/{project_id}"),
        OWNER_PAT,
        None,
    )
    .await;
    assert_error(&again, StatusCode::NOT_FOUND, "not_found");
    let update_gone = call(
        app,
        "PUT",
        &format!("/api/v2/projects/{project_id}"),
        OWNER_PAT,
        Some(json!({"name": "gone", "expected_updated_at": updated["updated_at"]})),
    )
    .await;
    let error = assert_error(&update_gone, StatusCode::NOT_FOUND, "not_found");
    assert!(error.get("current").is_none());

    // Yarn deletes follow the same contract.
    let delete = call(
        app,
        "DELETE",
        &format!("/api/v2/yarns/{yarn_id}"),
        OWNER_PAT,
        None,
    )
    .await;
    assert_eq!(delete.status, StatusCode::NO_CONTENT);
    let again = call(
        app,
        "DELETE",
        &format!("/api/v2/yarns/{yarn_id}"),
        OWNER_PAT,
        None,
    )
    .await;
    assert_error(&again, StatusCode::NOT_FOUND, "not_found");

    // Another user's project is indistinguishable from a missing one.
    let foreign: i64 = sqlx::query_scalar(
        "INSERT INTO projects (owner_id, name, created_at, updated_at) \
         VALUES (?, 'Foreign', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z') RETURNING id",
    )
    .bind(fixture.other_id)
    .fetch_one(&fixture.pool)
    .await
    .unwrap();
    for (method, path) in [
        ("GET", format!("/api/v2/projects/{foreign}")),
        ("DELETE", format!("/api/v2/projects/{foreign}")),
        ("PUT", format!("/api/v2/projects/{foreign}/favorite")),
    ] {
        let reply = call(app, method, &path, OWNER_PAT, None).await;
        assert_error(&reply, StatusCode::NOT_FOUND, "not_found");
    }
}

#[tokio::test]
async fn stale_writes_return_409_with_the_current_entity_in_the_shape_the_replay_worker_parses() {
    let fixture = fixture().await;
    let app = &fixture.app;

    // --- project ---
    let created = call_ok(
        app,
        "POST",
        "/api/v2/projects",
        OWNER_PAT,
        Some(json!({"name": "Shared", "notes": "base"})),
        StatusCode::CREATED,
    )
    .await;
    let project_id = created["id"].as_i64().unwrap();
    let stale_stamp = created["updated_at"].clone();
    // "Another device" edits first.
    let theirs = call_ok(
        app,
        "PUT",
        &format!("/api/v2/projects/{project_id}"),
        OWNER_PAT,
        Some(json!({"name": "Their name", "notes": "theirs", "expected_updated_at": stale_stamp})),
        StatusCode::OK,
    )
    .await;
    assert_ne!(
        theirs["updated_at"], stale_stamp,
        "an update advances updated_at"
    );

    // The offline edit replays with the stamp it was based on.
    let mine = call(
        app,
        "PUT",
        &format!("/api/v2/projects/{project_id}"),
        OWNER_PAT,
        Some(json!({"name": "My name", "notes": "mine", "expected_updated_at": stale_stamp})),
    )
    .await;
    let error = assert_error(&mine, StatusCode::CONFLICT, "conflict");
    // `parseV2Conflict<ProjectV2Dto>`: `error.current` is the full, current project.
    assert_project(&error["current"], "409 error.current");
    assert_eq!(error["current"]["id"], project_id);
    assert_eq!(error["current"]["name"], "Their name");
    assert_eq!(error["current"]["notes"], "theirs");
    assert_eq!(error["current"]["updated_at"], theirs["updated_at"]);
    // `parseConflictDetail` (v1) must find nothing in a v2 body, and the other way round.
    assert!(mine.body().get("detail").is_none());
    // The rejected write changed nothing.
    let after = call_ok(
        app,
        "GET",
        &format!("/api/v2/projects/{project_id}"),
        OWNER_PAT,
        None,
        StatusCode::OK,
    )
    .await;
    assert_eq!(after["name"], "Their name");
    assert_eq!(after["updated_at"], theirs["updated_at"]);

    // After `adoptRemoteProject` the next edit uses `current.updated_at` and succeeds.
    let retry = call_ok(
        app,
        "PUT",
        &format!("/api/v2/projects/{project_id}"),
        OWNER_PAT,
        Some(json!({"name": "Retried", "expected_updated_at": error["current"]["updated_at"]})),
        StatusCode::OK,
    )
    .await;
    assert_eq!(retry["name"], "Retried");

    // A malformed stamp is a 422 with the field named, not a conflict.
    let invalid = call(
        app,
        "PUT",
        &format!("/api/v2/projects/{project_id}"),
        OWNER_PAT,
        Some(json!({"name": "x", "expected_updated_at": "yesterday"})),
    )
    .await;
    let error = assert_error(
        &invalid,
        StatusCode::UNPROCESSABLE_ENTITY,
        "validation_error",
    );
    assert!(error["fields"]["expected_updated_at"].is_string());

    // --- yarn ---
    let yarn = call_ok(
        app,
        "POST",
        "/api/v2/yarns",
        OWNER_PAT,
        Some(json!({"name": "Shared yarn", "brand": "A"})),
        StatusCode::CREATED,
    )
    .await;
    let yarn_id = yarn["id"].as_i64().unwrap();
    let yarn_stamp = yarn["updated_at"].clone();
    let theirs = call_ok(
        app,
        "PUT",
        &format!("/api/v2/yarns/{yarn_id}"),
        OWNER_PAT,
        Some(json!({"name": "Their yarn", "brand": "B", "weight_grams": 100, "expected_updated_at": yarn_stamp})),
        StatusCode::OK,
    )
    .await;
    assert_yarn(&theirs, "updated yarn");
    let mine = call(
        app,
        "PUT",
        &format!("/api/v2/yarns/{yarn_id}"),
        OWNER_PAT,
        Some(json!({"name": "My yarn", "expected_updated_at": yarn_stamp})),
    )
    .await;
    let error = assert_error(&mine, StatusCode::CONFLICT, "conflict");
    assert_yarn(&error["current"], "409 yarn error.current");
    assert_eq!(error["current"]["name"], "Their yarn");
    assert_eq!(error["current"]["weight_grams"], 100);
    assert_eq!(error["current"]["updated_at"], theirs["updated_at"]);

    // --- category: the 409 has `fields.name` and no entity ---
    let blue = call_ok(
        app,
        "POST",
        "/api/v2/categories",
        OWNER_PAT,
        Some(json!({"name": "Blue"})),
        StatusCode::CREATED,
    )
    .await;
    assert_decodes(&blue, CATEGORY, "created category");
    let red = call_ok(
        app,
        "POST",
        "/api/v2/categories",
        OWNER_PAT,
        Some(json!({"name": "Red"})),
        StatusCode::CREATED,
    )
    .await;
    let clash = call(
        app,
        "PUT",
        &format!("/api/v2/categories/{}", red["id"]),
        OWNER_PAT,
        Some(json!({"name": "Blue"})),
    )
    .await;
    let error = assert_error(&clash, StatusCode::CONFLICT, "conflict");
    assert!(error["fields"]["name"].is_string());
    assert!(error.get("current").is_none());
    let listed = call_ok(
        app,
        "GET",
        "/api/v2/categories",
        OWNER_PAT,
        None,
        StatusCode::OK,
    )
    .await;
    assert_all_decode(&listed, CATEGORY, "categories");
    assert_eq!(listed.as_array().unwrap().len(), 2);
}

#[tokio::test]
async fn favourite_uses_put_and_delete_and_both_are_idempotent() {
    let fixture = fixture().await;
    let app = &fixture.app;
    let project = call_ok(
        app,
        "POST",
        "/api/v2/projects",
        OWNER_PAT,
        Some(json!({"name": "Fav"})),
        StatusCode::CREATED,
    )
    .await;
    let yarn = call_ok(
        app,
        "POST",
        "/api/v2/yarns",
        OWNER_PAT,
        Some(json!({"name": "Fav yarn"})),
        StatusCode::CREATED,
    )
    .await;

    for (kind, id, spec_project) in [
        ("projects", project["id"].as_i64().unwrap(), true),
        ("yarns", yarn["id"].as_i64().unwrap(), false),
    ] {
        let path = format!("/api/v2/{kind}/{id}/favorite");
        // v1 used POST; v2 has no POST on this resource.
        let legacy = call(app, "POST", &path, OWNER_PAT, None).await;
        assert_eq!(legacy.status, StatusCode::METHOD_NOT_ALLOWED, "{kind}");

        for round in 0..2 {
            let on = call_ok(app, "PUT", &path, OWNER_PAT, None, StatusCode::OK).await;
            assert_eq!(on["is_favorite"], true, "{kind} PUT #{round}");
            if spec_project {
                assert_project(&on, "favourite project");
            } else {
                assert_yarn(&on, "favourite yarn");
            }
        }
        let filtered = call_ok(
            app,
            "GET",
            &format!("/api/v2/{kind}?favorite=true"),
            OWNER_PAT,
            None,
            StatusCode::OK,
        )
        .await;
        assert_eq!(
            filtered.as_array().unwrap().len(),
            1,
            "{kind} favourites filter"
        );
        for round in 0..2 {
            let off = call_ok(app, "DELETE", &path, OWNER_PAT, None, StatusCode::OK).await;
            assert_eq!(off["is_favorite"], false, "{kind} DELETE #{round}");
        }
        let filtered = call_ok(
            app,
            "GET",
            &format!("/api/v2/{kind}?favorite=true"),
            OWNER_PAT,
            None,
            StatusCode::OK,
        )
        .await;
        assert!(filtered.as_array().unwrap().is_empty());
    }
}

// --- 3. Pagination ------------------------------------------------------------------------------

#[tokio::test]
async fn list_pages_are_fifty_wide_one_based_and_never_repeat_or_skip_rows() {
    let fixture = fixture().await;
    // 51 rows with identical timestamps force the ordering tie-break to hold across pages.
    for index in 0..51 {
        sqlx::query(
            "INSERT INTO projects (owner_id, name, created_at, updated_at) \
             VALUES (?, ?, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')",
        )
        .bind(fixture.owner_id)
        .bind(format!("Project {index:02}"))
        .execute(&fixture.pool)
        .await
        .unwrap();
        sqlx::query(
            "INSERT INTO yarns (owner_id, name, created_at, updated_at) \
             VALUES (?, ?, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')",
        )
        .bind(fixture.owner_id)
        .bind(format!("Yarn {index:02}"))
        .execute(&fixture.pool)
        .await
        .unwrap();
    }

    for (kind, spec) in [("projects", PROJECT_ITEM), ("yarns", YARN_ITEM)] {
        let mut seen = std::collections::BTreeSet::new();
        for (page_query, page, count, has_more) in [
            ("", 1, 50, true),
            ("?page=1", 1, 50, true),
            ("?page=2", 2, 1, false),
            ("?page=3", 3, 0, false),
        ] {
            let reply = call(
                &fixture.app,
                "GET",
                &format!("/api/v2/{kind}{page_query}"),
                OWNER_PAT,
                None,
            )
            .await;
            assert_eq!(reply.status, StatusCode::OK, "{kind}{page_query}");
            let body = reply.body();
            // `V2PageMeta`: page, per_page and has_more are all required.
            assert_decodes(
                &body["meta"],
                &[
                    ("page", Req, Int),
                    ("per_page", Req, Int),
                    ("has_more", Req, Bool),
                ],
                "meta",
            );
            assert_eq!(body["meta"]["page"], page, "{kind}{page_query}");
            assert_eq!(body["meta"]["per_page"], 50);
            assert_eq!(body["meta"]["has_more"], has_more, "{kind}{page_query}");
            assert_eq!(
                body["data"].as_array().unwrap().len(),
                count,
                "{kind}{page_query}"
            );
            assert_all_decode(&body["data"], spec, kind);
            if page_query != "?page=1" {
                for item in body["data"].as_array().unwrap() {
                    seen.insert(item["id"].as_i64().unwrap());
                }
            }
        }
        // Pages 1 (default) and 2 and 3 together cover all 51 rows exactly once.
        assert_eq!(seen.len(), 51, "{kind}: pages overlap or skip rows");
    }

    // The search filter keeps the same envelope and meta.
    let search = call(
        &fixture.app,
        "GET",
        "/api/v2/projects?q=Project%2007",
        OWNER_PAT,
        None,
    )
    .await;
    assert_eq!(search.body()["data"].as_array().unwrap().len(), 1);
    assert_eq!(search.body()["meta"]["has_more"], false);

    // Another account sees none of these rows.
    let other = call(&fixture.app, "GET", "/api/v2/projects", OTHER_PAT, None).await;
    assert!(other.body()["data"].as_array().unwrap().is_empty());
    assert_eq!(other.body()["meta"]["has_more"], false);
}

// --- 4. Media -----------------------------------------------------------------------------------

#[tokio::test]
async fn media_upload_delete_and_protected_urls_work_with_a_pat() {
    let fixture = fixture().await;
    let app = &fixture.app;
    let png = png_bytes();

    let project = call_ok(
        app,
        "POST",
        "/api/v2/projects",
        OWNER_PAT,
        Some(json!({"name": "With media", "steps": [{"title": "Step", "step_number": 1}]})),
        StatusCode::CREATED,
    )
    .await;
    let project_id = project["id"].as_i64().unwrap();
    let step_id = project["steps"][0]["id"].as_i64().unwrap();

    // Title image, with the optional `alt_text` part the Retrofit interface declares.
    let title = send(
        app,
        multipart(
            &format!("/api/v2/projects/{project_id}/images/title"),
            OWNER_PAT,
            &[
                ("alt_text", None, b"A title"),
                ("file", Some(("title.png", "image/png")), &png),
            ],
        ),
    )
    .await;
    assert_eq!(
        title.status,
        StatusCode::CREATED,
        "{}",
        String::from_utf8_lossy(&title.raw)
    );
    let title = title.data();
    assert_decodes(&title, IMAGE, "title image");
    assert_eq!(title["is_title_image"], true);
    assert_eq!(title["alt_text"], "A title");

    let step_image = send(
        app,
        multipart(
            &format!("/api/v2/projects/{project_id}/steps/{step_id}/images"),
            OWNER_PAT,
            &[("file", Some(("step.png", "image/png")), &png)],
        ),
    )
    .await;
    assert_eq!(step_image.status, StatusCode::CREATED);
    let step_image = step_image.data();
    assert_decodes(&step_image, IMAGE, "step image");
    assert_eq!(step_image["step_id"], step_id);

    let attachment = send(
        app,
        multipart(
            &format!("/api/v2/projects/{project_id}/attachments"),
            OWNER_PAT,
            &[(
                "file",
                Some(("pattern.pdf", "application/pdf")),
                b"%PDF-1.4\n%%EOF",
            )],
        ),
    )
    .await;
    assert_eq!(attachment.status, StatusCode::CREATED);
    let attachment = attachment.data();
    assert_decodes(&attachment, ATTACHMENT, "attachment");
    assert_eq!(attachment["original_filename"], "pattern.pdf");

    // The detail now lists all three and the list item carries the title thumbnail as preview.
    let detail = call_ok(
        app,
        "GET",
        &format!("/api/v2/projects/{project_id}"),
        OWNER_PAT,
        None,
        StatusCode::OK,
    )
    .await;
    assert_project(&detail, "detail with media");
    assert_eq!(detail["images"].as_array().unwrap().len(), 2);
    assert_eq!(detail["attachments"].as_array().unwrap().len(), 1);
    let list = call(app, "GET", "/api/v2/projects", OWNER_PAT, None).await;
    assert_eq!(list.data()[0]["preview_url"], title["thumbnail_url"]);

    // URLs are protected server-relative paths used as returned: fetchable with the same PAT.
    for url in [
        title["url"].as_str().unwrap(),
        title["thumbnail_url"].as_str().unwrap(),
        step_image["url"].as_str().unwrap(),
        attachment["url"].as_str().unwrap(),
    ] {
        assert!(url.starts_with("/media/"), "{url}");
        let fetched = send(app, media_get(url, OWNER_PAT)).await;
        assert_eq!(fetched.status, StatusCode::OK, "{url}");
        assert!(!fetched.raw.is_empty(), "{url}");
        // Not reachable anonymously, nor for another account, nor with an invalid PAT.
        let anonymous = send(app, Request::get(url).body(Body::empty()).unwrap()).await;
        assert_eq!(anonymous.status, StatusCode::UNAUTHORIZED, "{url}");
        let invalid = send(app, media_get(url, "client-contract-unknown-token")).await;
        assert_eq!(invalid.status, StatusCode::UNAUTHORIZED, "{url}");
        let foreign = send(app, media_get(url, OTHER_PAT)).await;
        assert_eq!(foreign.status, StatusCode::FORBIDDEN, "{url}");
    }

    // Rejections use the v2 envelope.
    let not_image = send(
        app,
        multipart(
            &format!("/api/v2/projects/{project_id}/images/title"),
            OWNER_PAT,
            &[(
                "file",
                Some(("fake.png", "image/png")),
                b"definitely not an image",
            )],
        ),
    )
    .await;
    assert_error(&not_image, StatusCode::BAD_REQUEST, "invalid_image");
    let foreign_upload = send(
        app,
        multipart(
            &format!("/api/v2/projects/{project_id}/images/title"),
            OTHER_PAT,
            &[("file", Some(("title.png", "image/png")), &png)],
        ),
    )
    .await;
    assert_error(&foreign_upload, StatusCode::NOT_FOUND, "not_found");

    // Deletes: 204 without a body, then 404 (which `replayImageDelete`/`replayAttachmentDelete`
    // treat as already done), and the stored file is gone.
    let title_url = title["url"].as_str().unwrap().to_owned();
    for (path, id_namespace) in [
        (
            format!("/api/v2/projects/{project_id}/images/{}", title["id"]),
            "image",
        ),
        (
            format!("/api/v2/projects/{project_id}/images/{}", step_image["id"]),
            "image",
        ),
        (
            format!(
                "/api/v2/projects/{project_id}/attachments/{}",
                attachment["id"]
            ),
            "attachment",
        ),
    ] {
        let deleted = call(app, "DELETE", &path, OWNER_PAT, None).await;
        assert_eq!(
            deleted.status,
            StatusCode::NO_CONTENT,
            "{id_namespace} {path}"
        );
        assert!(deleted.raw.is_empty());
        let again = call(app, "DELETE", &path, OWNER_PAT, None).await;
        assert_error(&again, StatusCode::NOT_FOUND, "not_found");
    }
    let gone = send(app, media_get(&title_url, OWNER_PAT)).await;
    assert_eq!(gone.status, StatusCode::NOT_FOUND);
    let detail = call_ok(
        app,
        "GET",
        &format!("/api/v2/projects/{project_id}"),
        OWNER_PAT,
        None,
        StatusCode::OK,
    )
    .await;
    assert!(detail["images"].as_array().unwrap().is_empty());
    assert!(detail["attachments"].as_array().unwrap().is_empty());
}

#[tokio::test]
async fn yarn_photos_upload_and_delete_with_a_pat_and_the_first_photo_is_primary() {
    let fixture = fixture().await;
    let app = &fixture.app;
    let png = png_bytes();
    let yarn = call_ok(
        app,
        "POST",
        "/api/v2/yarns",
        OWNER_PAT,
        Some(json!({"name": "Photo yarn"})),
        StatusCode::CREATED,
    )
    .await;
    let yarn_id = yarn["id"].as_i64().unwrap();

    let mut photos = Vec::new();
    for name in ["one.png", "two.png"] {
        let reply = send(
            app,
            multipart(
                &format!("/api/v2/yarns/{yarn_id}/photos"),
                OWNER_PAT,
                &[("file", Some((name, "image/png")), &png)],
            ),
        )
        .await;
        assert_eq!(
            reply.status,
            StatusCode::CREATED,
            "{}",
            String::from_utf8_lossy(&reply.raw)
        );
        let photo = reply.data();
        assert_decodes(&photo, PHOTO, "yarn photo");
        photos.push(photo);
    }
    assert_eq!(photos[0]["is_primary"], true);
    assert_eq!(photos[1]["is_primary"], false);

    let detail = call_ok(
        app,
        "GET",
        &format!("/api/v2/yarns/{yarn_id}"),
        OWNER_PAT,
        None,
        StatusCode::OK,
    )
    .await;
    assert_yarn(&detail, "yarn with photos");
    assert_eq!(detail["photos"].as_array().unwrap().len(), 2);
    let list = call(app, "GET", "/api/v2/yarns", OWNER_PAT, None).await;
    assert_eq!(list.data()[0]["preview_url"], photos[0]["thumbnail_url"]);
    let fetched = send(
        app,
        media_get(photos[0]["thumbnail_url"].as_str().unwrap(), OWNER_PAT),
    )
    .await;
    assert_eq!(fetched.status, StatusCode::OK);

    // Deleting the primary photo promotes the next one (the app displays `is_primary` first).
    let path = format!("/api/v2/yarns/{yarn_id}/photos/{}", photos[0]["id"]);
    let deleted = call(app, "DELETE", &path, OWNER_PAT, None).await;
    assert_eq!(deleted.status, StatusCode::NO_CONTENT);
    assert!(deleted.raw.is_empty());
    assert_error(
        &call(app, "DELETE", &path, OWNER_PAT, None).await,
        StatusCode::NOT_FOUND,
        "not_found",
    );
    let detail = call_ok(
        app,
        "GET",
        &format!("/api/v2/yarns/{yarn_id}"),
        OWNER_PAT,
        None,
        StatusCode::OK,
    )
    .await;
    assert_eq!(detail["photos"].as_array().unwrap().len(), 1);
    assert_eq!(detail["photos"][0]["is_primary"], true);
}

// --- 5. Sync after native writes ----------------------------------------------------------------

#[tokio::test]
async fn sync_reports_native_writes_with_the_detail_shape_and_tombstones_deletes() {
    let fixture = fixture().await;
    let app = &fixture.app;
    let project = call_ok(
        app,
        "POST",
        "/api/v2/projects",
        OWNER_PAT,
        Some(json!({"name": "Synced", "tags": ["a b"]})),
        StatusCode::CREATED,
    )
    .await;
    let yarn = call_ok(
        app,
        "POST",
        "/api/v2/yarns",
        OWNER_PAT,
        Some(json!({"name": "Synced yarn"})),
        StatusCode::CREATED,
    )
    .await;

    let first = call(app, "GET", "/api/v2/sync/projects", OWNER_PAT, None).await;
    assert_eq!(first.status, StatusCode::OK);
    let data = first.data();
    // `{data:{updated, deleted_ids, server_time, full_resync_required, has_more, next_cursor}}`.
    assert_decodes(
        &data,
        &[
            ("updated", Req, Arr),
            ("deleted_ids", Req, Arr),
            ("server_time", Req, Str),
            ("full_resync_required", Defaulted, Bool),
            ("has_more", Req, Bool),
        ],
        "sync envelope",
    );
    assert_eq!(data["has_more"], false);
    assert!(data["next_cursor"].is_null());
    // Entries are exactly what the detail endpoint returns, so `toV1()` applies unchanged.
    assert_eq!(data["updated"], json!([project.clone()]));
    assert_project(&data["updated"][0], "sync project");

    let yarns = call(app, "GET", "/api/v2/sync/yarns", OWNER_PAT, None).await;
    assert_eq!(yarns.data()["updated"], json!([yarn.clone()]));

    // Nothing newer than `server_time`, passed back verbatim.
    let since = data["server_time"].as_str().unwrap();
    let query: String = url_encode(since);
    let quiet = call(
        app,
        "GET",
        &format!("/api/v2/sync/projects?since={query}"),
        OWNER_PAT,
        None,
    )
    .await;
    assert_eq!(
        quiet.status,
        StatusCode::OK,
        "{}",
        String::from_utf8_lossy(&quiet.raw)
    );
    assert!(quiet.data()["updated"].as_array().unwrap().is_empty());
    assert!(quiet.data()["deleted_ids"].as_array().unwrap().is_empty());

    // A delete through the API shows up as a tombstone for the next pass.
    let project_id = project["id"].as_i64().unwrap();
    let deleted = call(
        app,
        "DELETE",
        &format!("/api/v2/projects/{project_id}"),
        OWNER_PAT,
        None,
    )
    .await;
    assert_eq!(deleted.status, StatusCode::NO_CONTENT);
    let after = call(
        app,
        "GET",
        &format!("/api/v2/sync/projects?since={query}"),
        OWNER_PAT,
        None,
    )
    .await;
    assert_eq!(after.data()["deleted_ids"], json!([project_id]));
    assert!(after.data()["updated"].as_array().unwrap().is_empty());

    // Another account's PAT never receives these rows.
    let other = call(app, "GET", "/api/v2/sync/projects", OTHER_PAT, None).await;
    assert!(other.data()["updated"].as_array().unwrap().is_empty());
}

fn url_encode(value: &str) -> String {
    url::form_urlencoded::byte_serialize(value.as_bytes()).collect()
}
