//! Passphrase-encrypted data exports (`POST /account/export` + one-time download ticket) and
//! their restore (`X-Archive-Passphrase`): age interoperability, ticket rules and the
//! decryption work cap. See `docs/data-export.md`, "Encryption (optional)".

use std::{
    collections::BTreeMap,
    io::{Cursor, Read},
    time::{Duration, Instant},
};

use axum::{
    body::Body,
    http::{Request, StatusCode},
    response::Response,
};
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use http_body_util::BodyExt;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use stricknani_server::{api_v2, db};
use tower::ServiceExt;

const OLD: &str = "2026-01-01T00:00:00.000000Z";
const PASSPHRASE: &str = "correct horse battery staple";
const UNICODE_PASSPHRASE: &str = "Strickliesel-Ä-ö-ü-ß-🧶";
const HOST: &str = "app.example.test";
const ORIGIN: &str = "https://app.example.test";
const PASSWORD: &str = "encryption-test-password-long";

struct Fx {
    _root: tempfile::TempDir,
    app: axum::Router,
    pool: SqlitePool,
    ben: i64,
    cara: i64,
}

fn pat(name: &str) -> String {
    format!("encryption-{name}-api-pat")
}

async fn seed_user(pool: &SqlitePool, name: &str) -> i64 {
    let id = sqlx::query(
        "INSERT INTO users (email, password_hash, is_admin, active) VALUES (?, 'test-only-hash', 0, 1)",
    )
    .bind(format!("{name}@example.test"))
    .execute(pool)
    .await
    .unwrap()
    .last_insert_rowid();
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, expires_at, purpose) \
         VALUES (?, 'encryption fixture', ?, unixepoch(), unixepoch() + 86400, 'api')",
    )
    .bind(id)
    .bind(hex::encode(Sha256::digest(pat(name).as_bytes())))
    .execute(pool)
    .await
    .unwrap();
    id
}

fn png() -> Vec<u8> {
    let image = image::RgbaImage::from_fn(8, 8, |x, y| {
        image::Rgba([40, (x * 30) as u8, (y * 30) as u8, 255])
    });
    let mut output = Cursor::new(Vec::new());
    image::DynamicImage::ImageRgba8(image)
        .write_to(&mut output, image::ImageFormat::Png)
        .unwrap();
    output.into_inner()
}

async fn fixture_with(ttl: Option<Duration>) -> Fx {
    let pool = db::connect("sqlite::memory:").await.unwrap();
    let root = tempfile::tempdir().unwrap();
    let state = db::AppState::new(pool.clone())
        .with_secure_cookies(true)
        .with_media_root(root.path().to_path_buf());
    let anika = seed_user(&pool, "anika").await;
    let ben = seed_user(&pool, "ben").await;
    let cara = seed_user(&pool, "cara").await;
    let project: i64 = sqlx::query_scalar(
        "INSERT INTO projects (owner_id,name,description,tags,created_at,updated_at) \
         VALUES (?, 'Wollsocken', 'Rippenmuster', '[]', ?, ?) RETURNING id",
    )
    .bind(anika)
    .bind(OLD)
    .bind(OLD)
    .fetch_one(&pool)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO yarns (owner_id,name,created_at,updated_at) VALUES (?, 'Merino', ?, ?)",
    )
    .bind(anika)
    .bind(OLD)
    .bind(OLD)
    .execute(&pool)
    .await
    .unwrap();
    let bytes = png();
    sqlx::query(
        "INSERT INTO media_files (owner_id,project_id,kind,public_id,filename,original_filename, \
         content_type,size_bytes,alt_text,is_primary,position,is_project_cover) \
         VALUES (?,?,'title',1,'cover.png','cover.png','image/png',?,'alt',1,0,1)",
    )
    .bind(anika)
    .bind(project)
    .bind(bytes.len() as i64)
    .execute(&pool)
    .await
    .unwrap();
    let dir = root.path().join("projects").join(project.to_string());
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join("cover.png"), &bytes).unwrap();
    let app = match ttl {
        Some(ttl) => api_v2::router_with_export_ticket_ttl(state, false, ttl),
        None => api_v2::router(state, false),
    };
    Fx {
        _root: root,
        app,
        pool,
        ben,
        cara,
    }
}

async fn fixture() -> Fx {
    fixture_with(None).await
}

async fn send(f: &Fx, request: Request<Body>) -> Response {
    f.app.clone().oneshot(request).await.unwrap()
}

async fn body(response: Response) -> Vec<u8> {
    response
        .into_body()
        .collect()
        .await
        .unwrap()
        .to_bytes()
        .to_vec()
}

async fn json_body(response: Response) -> Value {
    serde_json::from_slice(&body(response).await).unwrap_or(Value::Null)
}

fn bearer(name: &str) -> String {
    format!("Bearer {}", pat(name))
}

async fn post_export(f: &Fx, name: &str, payload: Value) -> (StatusCode, Value) {
    let response = send(
        f,
        Request::post("/account/export")
            .header("authorization", bearer(name))
            .header("content-type", "application/json")
            .body(Body::from(payload.to_string()))
            .unwrap(),
    )
    .await;
    let status = response.status();
    (status, json_body(response).await)
}

/// Request an encrypted export and return its download path (without the `/api/v2` prefix).
async fn ticket(f: &Fx, name: &str, archive: &str, passphrase: &str) -> String {
    let (status, value) = post_export(
        f,
        name,
        json!({"archive": archive, "passphrase": passphrase}),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED, "{value}");
    let url = value["data"]["url"].as_str().unwrap();
    assert!(
        value["data"]["expires_at"].as_str().unwrap().ends_with('Z'),
        "{value}"
    );
    url.strip_prefix("/api/v2").expect("api prefix").to_owned()
}

async fn download(f: &Fx, name: &str, path: &str) -> Response {
    send(
        f,
        Request::get(path)
            .header("authorization", bearer(name))
            .body(Body::empty())
            .unwrap(),
    )
    .await
}

async fn plain_export(f: &Fx, name: &str, archive: &str) -> Vec<u8> {
    let response = send(
        f,
        Request::get(format!("/account/export?archive={archive}"))
            .header("authorization", bearer(name))
            .body(Body::empty())
            .unwrap(),
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    body(response).await
}

async fn encrypted_export(f: &Fx, name: &str, archive: &str, passphrase: &str) -> Vec<u8> {
    let path = ticket(f, name, archive, passphrase).await;
    let response = download(f, name, &path).await;
    assert_eq!(response.status(), StatusCode::OK);
    body(response).await
}

fn decrypt(bytes: &[u8], passphrase: &str) -> Vec<u8> {
    let decryptor = age::Decryptor::new_buffered(bytes).expect("an age file");
    assert!(decryptor.is_scrypt());
    // The default cap depends on a timing probe; a loaded host would refuse work factor 18.
    let mut identity =
        age::scrypt::Identity::new(age::secrecy::SecretString::from(passphrase.to_owned()));
    identity.set_max_work_factor(20);
    let mut reader = decryptor
        .decrypt(std::iter::once(&identity as &dyn age::Identity))
        .expect("the passphrase opens the file");
    let mut plain = Vec::new();
    reader.read_to_end(&mut plain).unwrap();
    plain
}

/// Files of an archive by path below its top-level directory.
fn files(bytes: &[u8]) -> BTreeMap<String, Vec<u8>> {
    let mut files = BTreeMap::new();
    if bytes.starts_with(&[0x1f, 0x8b]) {
        let mut tar = tar::Archive::new(flate2::read::GzDecoder::new(bytes));
        for entry in tar.entries().unwrap() {
            let mut entry = entry.unwrap();
            let path = entry.path().unwrap().to_string_lossy().into_owned();
            let mut data = Vec::new();
            entry.read_to_end(&mut data).unwrap();
            let (_, rest) = path.split_once('/').unwrap();
            files.insert(rest.to_owned(), data);
        }
    } else {
        let mut zip = zip::ZipArchive::new(Cursor::new(bytes.to_vec())).expect("a zip");
        for index in 0..zip.len() {
            let mut entry = zip.by_index(index).unwrap();
            let path = entry.name().unwrap().into_owned();
            let mut data = Vec::new();
            entry.read_to_end(&mut data).unwrap();
            let (_, rest) = path.split_once('/').unwrap();
            files.insert(rest.to_owned(), data);
        }
    }
    files
}

fn passphrase_header(passphrase: &str) -> String {
    URL_SAFE_NO_PAD.encode(passphrase.as_bytes())
}

async fn post_import(
    f: &Fx,
    name: &str,
    data: Vec<u8>,
    passphrase_header: Option<&str>,
) -> (StatusCode, Value) {
    let mut request = Request::post("/account/import").header("authorization", bearer(name));
    if let Some(value) = passphrase_header {
        request = request.header("x-archive-passphrase", value);
    }
    let response = send(f, request.body(Body::from(data)).unwrap()).await;
    let status = response.status();
    (status, json_body(response).await)
}

async fn restore_with(f: &Fx, name: &str, data: Vec<u8>, passphrase: &str) -> Value {
    let (status, value) = post_import(f, name, data, Some(&passphrase_header(passphrase))).await;
    assert_eq!(status, StatusCode::OK, "{value}");
    value["data"].clone()
}

async fn restore_error(
    f: &Fx,
    data: Vec<u8>,
    header: Option<&str>,
    status: StatusCode,
    code: &str,
) {
    let (got, value) = post_import(f, "ben", data, header).await;
    assert_eq!(got, status, "{value}");
    assert_eq!(value["error"]["code"], code, "{value}");
    let text = value.to_string();
    assert!(!text.contains(PASSPHRASE), "{text}");
}

/// A user's projects as (name, description, sha256 of the cover image).
async fn restored(f: &Fx, user: i64) -> Vec<(String, String, String)> {
    let rows: Vec<(i64, String, String)> =
        sqlx::query_as("SELECT id, name, description FROM projects WHERE owner_id = ? ORDER BY id")
            .bind(user)
            .fetch_all(&f.pool)
            .await
            .unwrap();
    let mut out = Vec::new();
    for (id, name, description) in rows {
        let file: String = sqlx::query_scalar(
            "SELECT filename FROM media_files WHERE project_id = ? AND kind = 'title'",
        )
        .bind(id)
        .fetch_one(&f.pool)
        .await
        .unwrap();
        let bytes = std::fs::read(
            f._root
                .path()
                .join("projects")
                .join(id.to_string())
                .join(file),
        )
        .unwrap();
        out.push((name, description, hex::encode(Sha256::digest(bytes))));
    }
    out
}

async fn round_trip(archive: &str, passphrase: &str) {
    let f = fixture().await;
    let path = ticket(&f, "anika", archive, passphrase).await;
    let response = download(&f, "anika", &path).await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        response.headers()["content-type"],
        "application/octet-stream"
    );
    assert_eq!(response.headers()["cache-control"], "no-store");
    let disposition = response.headers()["content-disposition"]
        .to_str()
        .unwrap()
        .to_owned();
    assert!(
        disposition.starts_with("attachment; filename=\"stricknani-export-")
            && disposition.ends_with(&format!(".{archive}.age\"")),
        "{disposition}"
    );
    let encrypted = body(response).await;
    assert!(encrypted.starts_with(b"age-encryption.org/v1\n"));
    assert!(
        encrypted
            .windows(passphrase.len())
            .all(|w| w != passphrase.as_bytes())
    );

    // The decrypted bytes are the normal archive.
    let inner = decrypt(&encrypted, passphrase);
    let plain = plain_export(&f, "anika", archive).await;
    let mut inner_files = files(&inner);
    let mut plain_files = files(&plain);
    assert!(inner_files.remove("manifest.json").is_some());
    assert!(plain_files.remove("manifest.json").is_some());
    assert_eq!(inner_files, plain_files);
    assert!(inner_files.keys().any(|name| name.starts_with("media/")));

    // Restoring it equals restoring the plain archive.
    let from_encrypted = restore_with(&f, "ben", encrypted, passphrase).await;
    let (status, plain_value) = post_import(&f, "cara", plain, None).await;
    assert_eq!(status, StatusCode::OK, "{plain_value}");
    assert_eq!(from_encrypted, plain_value["data"]);
    assert_eq!(from_encrypted["projects"], 1);
    assert_eq!(from_encrypted["media_files"], 1);
    let ben = restored(&f, f.ben).await;
    assert_eq!(ben.len(), 1);
    assert_eq!(ben[0].0, "Wollsocken");
    assert_eq!(ben, restored(&f, f.cara).await);
}

#[tokio::test]
async fn tar_gz_round_trip() {
    round_trip("tar.gz", PASSPHRASE).await;
}

#[tokio::test]
async fn zip_round_trip() {
    round_trip("zip", PASSPHRASE).await;
}

#[tokio::test]
async fn non_ascii_passphrase_round_trip() {
    round_trip("tar.gz", UNICODE_PASSPHRASE).await;
}

#[tokio::test]
async fn an_export_can_be_kept_for_the_age_cli() {
    // With STRICKNANI_AGE_FIXTURE_DIR set, leave encrypted exports there for `age -d`.
    let Some(dir) = std::env::var_os("STRICKNANI_AGE_FIXTURE_DIR") else {
        return;
    };
    let f = fixture().await;
    for (archive, passphrase) in [("tar.gz", PASSPHRASE), ("zip", UNICODE_PASSPHRASE)] {
        let bytes = encrypted_export(&f, "anika", archive, passphrase).await;
        std::fs::write(
            std::path::Path::new(&dir).join(format!("export.{archive}.age")),
            bytes,
        )
        .unwrap();
    }
}

#[tokio::test]
async fn tickets_are_single_use_and_bound_to_the_account() {
    let f = fixture().await;
    let path = ticket(&f, "anika", "tar.gz", PASSPHRASE).await;

    // Another account and anonymous callers get nothing, and do not use the ticket up.
    let foreign = download(&f, "ben", &path).await;
    assert_eq!(foreign.status(), StatusCode::NOT_FOUND);
    let anonymous = send(&f, Request::get(&path).body(Body::empty()).unwrap()).await;
    assert_eq!(anonymous.status(), StatusCode::UNAUTHORIZED);

    let first = download(&f, "anika", &path).await;
    assert_eq!(first.status(), StatusCode::OK);
    assert!(!body(first).await.is_empty());
    let second = download(&f, "anika", &path).await;
    assert_eq!(second.status(), StatusCode::NOT_FOUND);
    let value = json_body(second).await;
    assert_eq!(value["error"]["code"], "not_found");

    let unknown = download(
        &f,
        "anika",
        "/account/export/download/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    )
    .await;
    assert_eq!(unknown.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn tickets_are_random_and_distinct() {
    let f = fixture().await;
    let first = ticket(&f, "anika", "tar.gz", PASSPHRASE).await;
    let second = ticket(&f, "anika", "tar.gz", PASSPHRASE).await;
    assert_ne!(first, second);
    let token = first.rsplit('/').next().unwrap();
    assert_eq!(URL_SAFE_NO_PAD.decode(token).unwrap().len(), 32);
}

#[tokio::test]
async fn expired_tickets_are_gone() {
    let f = fixture_with(Some(Duration::from_millis(150))).await;
    let path = ticket(&f, "anika", "tar.gz", PASSPHRASE).await;
    tokio::time::sleep(Duration::from_millis(400)).await;
    let response = download(&f, "anika", &path).await;
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
    // A fresh ticket still works with the same state.
    let fresh = ticket(&f, "anika", "zip", PASSPHRASE).await;
    let ok = download(&f, "anika", &fresh).await;
    assert_eq!(ok.status(), StatusCode::OK);
}

#[tokio::test]
async fn passphrase_and_archive_are_validated() {
    let f = fixture().await;
    let long = "x".repeat(1025);
    let bad = [
        json!({"archive": "tar.gz", "passphrase": "elevenchars"}),
        json!({"archive": "tar.gz", "passphrase": ""}),
        json!({"archive": "tar.gz", "passphrase": long}),
        // Bounds are bytes, not characters: five two-byte characters and one more are 11.
        json!({"archive": "tar.gz", "passphrase": "äääääx"}),
        json!({"archive": "rar", "passphrase": PASSPHRASE}),
        json!({"archive": "tar.gz"}),
    ];
    for payload in bad {
        let (status, value) = post_export(&f, "anika", payload.clone()).await;
        assert_eq!(status, StatusCode::BAD_REQUEST, "{payload} -> {value}");
        assert_eq!(value["error"]["code"], "invalid_request", "{value}");
        assert!(!value.to_string().contains(PASSPHRASE));
    }
    // Bounds are bytes: 12 and 1024 pass, six two-byte characters are 12 bytes.
    for passphrase in ["x".repeat(12), "x".repeat(1024), "ääääää".to_owned()] {
        let (status, value) = post_export(&f, "anika", json!({"passphrase": passphrase})).await;
        assert_eq!(status, StatusCode::CREATED, "{value}");
    }
    // Not JSON at all, and no credentials.
    let response = send(
        &f,
        Request::post("/account/export")
            .header("authorization", bearer("anika"))
            .header("content-type", "application/json")
            .body(Body::from("nope"))
            .unwrap(),
    )
    .await;
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    let response = send(
        &f,
        Request::post("/account/export")
            .header("content-type", "application/json")
            .body(Body::from(json!({"passphrase": PASSPHRASE}).to_string()))
            .unwrap(),
    )
    .await;
    // No credentials: refused by the browser-session CSRF check before anything else.
    assert!(
        matches!(
            response.status(),
            StatusCode::UNAUTHORIZED | StatusCode::FORBIDDEN
        ),
        "{}",
        response.status()
    );
}

#[tokio::test]
async fn restore_asks_for_the_passphrase_and_rejects_a_wrong_one() {
    let f = fixture().await;
    let encrypted = encrypted_export(&f, "anika", "tar.gz", PASSPHRASE).await;
    restore_error(
        &f,
        encrypted.clone(),
        None,
        StatusCode::UNPROCESSABLE_ENTITY,
        "passphrase_required",
    )
    .await;
    restore_error(
        &f,
        encrypted.clone(),
        Some(&passphrase_header("another passphrase")),
        StatusCode::UNPROCESSABLE_ENTITY,
        "wrong_passphrase",
    )
    .await;
    // A case or whitespace difference is a different passphrase.
    restore_error(
        &f,
        encrypted.clone(),
        Some(&passphrase_header("Correct horse battery staple")),
        StatusCode::UNPROCESSABLE_ENTITY,
        "wrong_passphrase",
    )
    .await;
    // Not base64url (padding, standard alphabet, garbage), and not UTF-8.
    for header in ["!!!not-base64!!!", "Y29ycmVjdA==", "+/+/", "gA"] {
        restore_error(
            &f,
            encrypted.clone(),
            Some(header),
            StatusCode::BAD_REQUEST,
            "invalid_request",
        )
        .await;
    }
    // Nothing was restored by the failures; the right passphrase still works.
    assert!(restored(&f, f.ben).await.is_empty());
    restore_with(&f, "ben", encrypted, PASSPHRASE).await;
    assert_eq!(restored(&f, f.ben).await.len(), 1);
}

#[tokio::test]
async fn a_plain_archive_ignores_the_passphrase_header() {
    let f = fixture().await;
    let plain = plain_export(&f, "anika", "zip").await;
    let value = restore_with(&f, "ben", plain, "irrelevant passphrase").await;
    assert_eq!(value["projects"], 1);
}

#[tokio::test]
async fn a_damaged_encrypted_file_is_an_invalid_archive() {
    let f = fixture().await;
    let encrypted = encrypted_export(&f, "anika", "tar.gz", PASSPHRASE).await;
    let header = Some(passphrase_header(PASSPHRASE));
    let truncated = encrypted[..encrypted.len() - 40].to_vec();
    restore_error(
        &f,
        truncated,
        header.as_deref(),
        StatusCode::BAD_REQUEST,
        "invalid_archive",
    )
    .await;
    let mut flipped = encrypted.clone();
    let last = flipped.len() - 1;
    flipped[last] ^= 0x55;
    restore_error(
        &f,
        flipped,
        header.as_deref(),
        StatusCode::BAD_REQUEST,
        "invalid_archive",
    )
    .await;
    // Only the first line is right.
    restore_error(
        &f,
        b"age-encryption.org/v1\nnot a header".to_vec(),
        header.as_deref(),
        StatusCode::BAD_REQUEST,
        "invalid_archive",
    )
    .await;
    assert!(restored(&f, f.ben).await.is_empty());
}

/// An age file whose scrypt stanza asks for work factor `log_n`. The header MAC is invalid, but
/// a reader that unwrapped the stanza would first spend the scrypt work.
fn crafted(log_n: u32) -> Vec<u8> {
    // All-zero bytes keep the unpadded base64 canonical.
    let b64 = |len: usize| URL_SAFE_NO_PAD.encode(vec![0_u8; len]);
    let mut file = format!(
        "age-encryption.org/v1\n-> scrypt {} {log_n}\n{}\n--- {}\n",
        b64(16),
        b64(32),
        b64(32),
    )
    .into_bytes();
    file.extend_from_slice(&[7_u8; 16 + 64]);
    file
}

#[tokio::test]
async fn excessive_scrypt_work_factors_are_refused_without_the_work() {
    let f = fixture().await;
    for log_n in [21, 22, 40] {
        let started = Instant::now();
        restore_error(
            &f,
            crafted(log_n),
            Some(&passphrase_header(PASSPHRASE)),
            StatusCode::BAD_REQUEST,
            "invalid_archive",
        )
        .await;
        // 2^22 would take seconds and 4 GiB; refusing is immediate.
        assert!(
            started.elapsed() < Duration::from_secs(3),
            "log_n {log_n} took {:?}",
            started.elapsed()
        );
    }
}

fn cookie(response: &Response, prefix: &str) -> String {
    response
        .headers()
        .get_all("set-cookie")
        .iter()
        .filter_map(|value| value.to_str().ok())
        .find_map(|value| value.strip_prefix(prefix))
        .and_then(|value| value.split(';').next())
        .expect("expected cookie should be set")
        .to_owned()
}

#[tokio::test]
async fn browser_sessions_need_csrf_to_request_an_export() {
    let f = fixture().await;
    db::create_user(&f.pool, "owner@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    let bootstrap = send(&f, Request::get("/session").body(Body::empty()).unwrap()).await;
    let preauth = cookie(&bootstrap, "__Host-stricknani_v2_login_csrf=");
    let pre_csrf = json_body(bootstrap).await["data"]["csrf_token"]
        .as_str()
        .unwrap()
        .to_owned();
    let login = send(
        &f,
        Request::post("/auth/login")
            .header("host", HOST)
            .header("origin", ORIGIN)
            .header("sec-fetch-site", "same-origin")
            .header(
                "cookie",
                format!("__Host-stricknani_v2_login_csrf={preauth}"),
            )
            .header("x-csrf-token", pre_csrf)
            .header("content-type", "application/json")
            .body(Body::from(
                json!({"email": "owner@example.test", "password": PASSWORD}).to_string(),
            ))
            .unwrap(),
    )
    .await;
    assert_eq!(login.status(), StatusCode::OK);
    let session = cookie(&login, "__Host-stricknani_session=");
    let csrf = json_body(login).await["data"]["csrf_token"]
        .as_str()
        .unwrap()
        .to_owned();
    let payload = json!({"archive": "zip", "passphrase": PASSPHRASE}).to_string();
    let cookie_header = format!("__Host-stricknani_session={session}");

    let without = send(
        &f,
        Request::post("/account/export")
            .header("cookie", &cookie_header)
            .header("content-type", "application/json")
            .body(Body::from(payload.clone()))
            .unwrap(),
    )
    .await;
    assert_eq!(without.status(), StatusCode::FORBIDDEN);

    let with = send(
        &f,
        Request::post("/account/export")
            .header("cookie", &cookie_header)
            .header("host", HOST)
            .header("origin", ORIGIN)
            .header("sec-fetch-site", "same-origin")
            .header("x-csrf-token", &csrf)
            .header("content-type", "application/json")
            .body(Body::from(payload))
            .unwrap(),
    )
    .await;
    assert_eq!(with.status(), StatusCode::CREATED);
    let value = json_body(with).await;
    let path = value["data"]["url"]
        .as_str()
        .unwrap()
        .strip_prefix("/api/v2")
        .unwrap()
        .to_owned();

    // The browser then navigates to the URL with its session only.
    let response = send(
        &f,
        Request::get(&path)
            .header("cookie", &cookie_header)
            .body(Body::empty())
            .unwrap(),
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    let encrypted = body(response).await;
    assert!(files(&decrypt(&encrypted, PASSPHRASE)).contains_key("manifest.json"));
}
