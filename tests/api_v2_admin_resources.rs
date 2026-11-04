use axum::{
    Router,
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use stricknani_server::{api_v2, db};
use tower::ServiceExt;

const ADMIN_SESSION: &str = "synthetic-admin-session";
const ADMIN_PAT: &str = "synthetic-admin-purpose-api-pat";
const MEMBER_SESSION: &str = "synthetic-member-session";

struct Fixture {
    _media_root: tempfile::TempDir,
    app: Router,
    pool: SqlitePool,
    admin_id: i64,
    member_id: i64,
}

async fn fixture() -> Fixture {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("in-memory database should migrate");
    let admin_id = db::create_user(
        &pool,
        "resource-admin@example.test",
        "admin-password-long".into(),
    )
    .await
    .expect("synthetic admin should be created");
    sqlx::query("UPDATE users SET is_admin = 1 WHERE id = ?")
        .bind(admin_id)
        .execute(&pool)
        .await
        .expect("synthetic admin role should be set");
    let member_id = db::create_user(
        &pool,
        "resource-member@example.test",
        "member-password-long".into(),
    )
    .await
    .expect("synthetic member should be created");
    add_session(&pool, admin_id, ADMIN_SESSION).await;
    add_api_pat(&pool, admin_id, ADMIN_PAT).await;
    add_session(&pool, member_id, MEMBER_SESSION).await;
    let media_root = tempfile::tempdir().expect("media root should be created");
    let state = db::AppState::new(pool.clone())
        .with_secure_cookies(true)
        .with_media_root(media_root.path().to_path_buf());
    Fixture {
        _media_root: media_root,
        app: api_v2::router(state.clone(), false)
            .merge(stricknani_server::media::router().with_state(state)),
        pool,
        admin_id,
        member_id,
    }
}

async fn add_session(pool: &SqlitePool, user_id: i64, session: &str) {
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, expires_at, purpose) \
         VALUES (?, 'synthetic browser session', ?, unixepoch(), unixepoch() + 86400, 'browser')",
    )
    .bind(user_id)
    .bind(hex::encode(Sha256::digest(session.as_bytes())))
    .execute(pool)
    .await
    .expect("synthetic browser session should be stored");
}

async fn add_api_pat(pool: &SqlitePool, user_id: i64, token: &str) {
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, expires_at, purpose) \
         VALUES (?, 'synthetic admin PAT', ?, unixepoch(), unixepoch() + 86400, 'api')",
    )
    .bind(user_id)
    .bind(hex::encode(Sha256::digest(token.as_bytes())))
    .execute(pool)
    .await
    .expect("synthetic PAT should be stored");
}

async fn insert_project(pool: &SqlitePool, owner_id: i64) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO projects(owner_id, name, created_at, updated_at) \
         VALUES (?, 'Synthetic project', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z') \
         RETURNING id",
    )
    .bind(owner_id)
    .fetch_one(pool)
    .await
    .expect("synthetic project should be inserted")
}

async fn insert_yarn(pool: &SqlitePool, owner_id: i64) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO yarns(owner_id, name, created_at, updated_at) \
         VALUES (?, 'Synthetic yarn', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z') \
         RETURNING id",
    )
    .bind(owner_id)
    .fetch_one(pool)
    .await
    .expect("synthetic yarn should be inserted")
}

async fn insert_category(pool: &SqlitePool, owner_id: i64) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO categories(user_id, name) VALUES (?, 'Synthetic category') RETURNING id",
    )
    .bind(owner_id)
    .fetch_one(pool)
    .await
    .expect("synthetic category should be inserted")
}

#[allow(clippy::too_many_arguments)]
async fn insert_media(
    pool: &SqlitePool,
    owner_id: i64,
    parent_id: i64,
    kind: &str,
    public_id: i64,
    filename: &str,
    content_type: &str,
    safe_inline: bool,
    step_id: Option<i64>,
) {
    let (project_id, yarn_id) = match kind {
        "title" | "attachment" => (Some(parent_id), None),
        "step" => (Some(parent_id), None),
        "photo" => (None, Some(parent_id)),
        _ => panic!("test media kind should be valid"),
    };
    sqlx::query(
        "INSERT INTO media_files( \
             owner_id, project_id, yarn_id, step_id, kind, filename, original_filename, \
             content_type, size_bytes, alt_text, is_primary, is_safe_inline, public_id \
         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 8, 'synthetic media', 1, ?, ?)",
    )
    .bind(owner_id)
    .bind(project_id)
    .bind(yarn_id)
    .bind(step_id)
    .bind(kind)
    .bind(filename)
    .bind(format!("original-{filename}"))
    .bind(content_type)
    .bind(safe_inline)
    .bind(public_id)
    .execute(pool)
    .await
    .expect("synthetic media should be inserted");
}

fn request(path: &str, session: Option<&str>) -> Request<Body> {
    let mut request = Request::get(path);
    if let Some(session) = session {
        request = request.header("cookie", format!("__Host-stricknani_session={session}"));
    }
    request
        .body(Body::empty())
        .expect("request should be built")
}

async fn json(response: axum::response::Response) -> serde_json::Value {
    serde_json::from_slice(
        &response
            .into_body()
            .collect()
            .await
            .expect("body should read")
            .to_bytes(),
    )
    .expect("response should be JSON")
}

#[tokio::test]
async fn admin_resource_reads_require_an_active_browser_admin_and_do_not_mutate_resources() {
    let fixture = fixture().await;
    let project_id = insert_project(&fixture.pool, fixture.member_id).await;
    let yarn_id = insert_yarn(&fixture.pool, fixture.member_id).await;
    let category_id = insert_category(&fixture.pool, fixture.member_id).await;
    let audit_before: i64 = sqlx::query_scalar("SELECT count(*) FROM audit_events")
        .fetch_one(&fixture.pool)
        .await
        .expect("audit count should be readable");

    for (kind, id, expected_name) in [
        ("project", project_id, "Synthetic project"),
        ("yarn", yarn_id, "Synthetic yarn"),
        ("category", category_id, "Synthetic category"),
    ] {
        let response = fixture
            .app
            .clone()
            .oneshot(request(
                &format!("/admin/resources/{kind}/{id}"),
                Some(ADMIN_SESSION),
            ))
            .await
            .expect("router should answer");
        assert_eq!(response.status(), StatusCode::OK);
        let body = json(response).await;
        assert_eq!(body["data"]["kind"], kind);
        assert_eq!(body["data"]["id"], id);
        assert_eq!(body["data"]["name"], expected_name);
        assert_eq!(body["data"]["owner"]["id"], fixture.member_id);
        assert_eq!(
            body["data"]["owner"]["email"],
            "resource-member@example.test"
        );
        assert_eq!(body["data"]["owner"]["avatar_url"], serde_json::Value::Null);
        assert_eq!(body["data"]["read_only"], true);
        assert_eq!(body["data"]["resource"]["id"], id);
    }

    let pat_resource = fixture
        .app
        .clone()
        .oneshot(
            Request::get(format!("/admin/resources/project/{project_id}"))
                .header("authorization", format!("Bearer {ADMIN_PAT}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .expect("router should answer");
    assert_eq!(pat_resource.status(), StatusCode::OK);
    assert_eq!(json(pat_resource).await["data"]["read_only"], true);

    let anonymous = fixture
        .app
        .clone()
        .oneshot(request(
            &format!("/admin/resources/project/{project_id}"),
            None,
        ))
        .await
        .expect("router should answer");
    assert_eq!(anonymous.status(), StatusCode::UNAUTHORIZED);

    let member = fixture
        .app
        .clone()
        .oneshot(request(
            &format!("/admin/resources/project/{project_id}"),
            Some(MEMBER_SESSION),
        ))
        .await
        .expect("router should answer");
    assert_eq!(member.status(), StatusCode::FORBIDDEN);
    assert_eq!(json(member).await["error"]["code"], "admin_required");

    let bearer_only = fixture
        .app
        .clone()
        .oneshot(
            Request::get(format!("/admin/resources/project/{project_id}"))
                .header("authorization", format!("Bearer {ADMIN_SESSION}"))
                .body(Body::empty())
                .expect("request should be built"),
        )
        .await
        .expect("router should answer");
    assert_eq!(bearer_only.status(), StatusCode::UNAUTHORIZED);

    let wrong_kind = fixture
        .app
        .clone()
        .oneshot(request("/admin/resources/unknown/1", Some(ADMIN_SESSION)))
        .await
        .expect("router should answer");
    assert_eq!(wrong_kind.status(), StatusCode::NOT_FOUND);

    let missing = fixture
        .app
        .clone()
        .oneshot(request(
            "/admin/resources/project/999999",
            Some(ADMIN_SESSION),
        ))
        .await
        .expect("router should answer");
    assert_eq!(missing.status(), StatusCode::NOT_FOUND);

    sqlx::query("UPDATE users SET is_admin = 0 WHERE id = ?")
        .bind(fixture.admin_id)
        .execute(&fixture.pool)
        .await
        .expect("admin role should be removable");
    let demoted = fixture
        .app
        .clone()
        .oneshot(request(
            &format!("/admin/resources/project/{project_id}"),
            Some(ADMIN_SESSION),
        ))
        .await
        .expect("router should answer");
    assert_eq!(demoted.status(), StatusCode::FORBIDDEN);

    sqlx::query("UPDATE users SET is_admin = 1, active = 0 WHERE id = ?")
        .bind(fixture.admin_id)
        .execute(&fixture.pool)
        .await
        .expect("admin should be disableable");
    let disabled_admin = fixture
        .app
        .clone()
        .oneshot(request(
            &format!("/admin/resources/project/{project_id}"),
            Some(ADMIN_SESSION),
        ))
        .await
        .expect("router should answer");
    assert_eq!(disabled_admin.status(), StatusCode::UNAUTHORIZED);

    sqlx::query("UPDATE users SET is_admin = 1, active = 1 WHERE id = ?")
        .bind(fixture.admin_id)
        .execute(&fixture.pool)
        .await
        .expect("synthetic admin should be restored for the owner-scope check");

    let owner_route = fixture
        .app
        .clone()
        .oneshot(request(
            &format!("/projects/{project_id}"),
            Some(ADMIN_SESSION),
        ))
        .await
        .expect("router should answer");
    assert_eq!(owner_route.status(), StatusCode::NOT_FOUND);

    let audit_after: i64 = sqlx::query_scalar("SELECT count(*) FROM audit_events")
        .fetch_one(&fixture.pool)
        .await
        .expect("audit count should be readable");
    assert_eq!(audit_before, audit_after);
}

#[tokio::test]
async fn admin_resource_media_is_parent_scoped_and_preserves_safe_download_headers() {
    let fixture = fixture().await;
    let project_id = insert_project(&fixture.pool, fixture.member_id).await;
    let yarn_id = insert_yarn(&fixture.pool, fixture.member_id).await;
    let root = fixture._media_root.path();
    let project_dir = root.join("projects").join(project_id.to_string());
    let project_thumbnails = root
        .join("thumbnails/projects")
        .join(project_id.to_string());
    let yarn_dir = root.join("yarns").join(yarn_id.to_string());
    let yarn_thumbnails = root.join("thumbnails/yarns").join(yarn_id.to_string());
    for directory in [
        &project_dir,
        &project_thumbnails,
        &yarn_dir,
        &yarn_thumbnails,
    ] {
        tokio::fs::create_dir_all(directory)
            .await
            .expect("media directory should be created");
    }
    tokio::fs::write(project_dir.join("opaque.jpg"), b"project image")
        .await
        .expect("project image should be written");
    tokio::fs::write(
        project_thumbnails.join("thumb_opaque.jpg"),
        b"project thumb",
    )
    .await
    .expect("project thumbnail should be written");
    tokio::fs::write(project_dir.join("pattern.pdf"), b"pdf bytes")
        .await
        .expect("attachment should be written");
    tokio::fs::write(project_dir.join("step.jpg"), b"step image")
        .await
        .expect("step image should be written");
    tokio::fs::write(yarn_dir.join("yarn.jpg"), b"yarn image")
        .await
        .expect("yarn photo should be written");
    tokio::fs::write(yarn_thumbnails.join("thumb_yarn.jpg"), b"yarn thumb")
        .await
        .expect("yarn thumbnail should be written");
    insert_media(
        &fixture.pool,
        fixture.member_id,
        project_id,
        "title",
        9001,
        "opaque.jpg",
        "image/jpeg",
        true,
        None,
    )
    .await;
    let step_id: i64 = sqlx::query_scalar(
        "INSERT INTO project_steps(project_id, title, step_number) \
         VALUES (?, 'Synthetic step', 1) RETURNING id",
    )
    .bind(project_id)
    .fetch_one(&fixture.pool)
    .await
    .expect("synthetic step should be inserted");
    insert_media(
        &fixture.pool,
        fixture.member_id,
        project_id,
        "step",
        9004,
        "step.jpg",
        "image/jpeg",
        true,
        Some(step_id),
    )
    .await;
    insert_media(
        &fixture.pool,
        fixture.member_id,
        project_id,
        "attachment",
        9001,
        "pattern.pdf",
        "application/pdf",
        false,
        None,
    )
    .await;
    insert_media(
        &fixture.pool,
        fixture.member_id,
        yarn_id,
        "photo",
        9003,
        "yarn.jpg",
        "image/jpeg",
        true,
        None,
    )
    .await;

    let detail = fixture
        .app
        .clone()
        .oneshot(request(
            &format!("/admin/resources/project/{project_id}"),
            Some(ADMIN_SESSION),
        ))
        .await
        .expect("router should answer");
    let detail = json(detail).await;
    assert_eq!(
        detail["data"]["resource"]["images"][0]["url"],
        format!("/api/v2/admin/resources/project/{project_id}/media/image/9001")
    );
    assert_eq!(
        detail["data"]["resource"]["images"][0]["thumbnail_url"],
        format!("/api/v2/admin/resources/project/{project_id}/media/image/9001/thumbnail")
    );
    assert_eq!(
        detail["data"]["resource"]["attachments"][0]["url"],
        format!("/api/v2/admin/resources/project/{project_id}/media/attachment/9001")
    );
    let step_image = detail["data"]["resource"]["images"]
        .as_array()
        .expect("project images should be serialized")
        .iter()
        .find(|image| image["id"] == 9004)
        .expect("step image should be present");
    assert_eq!(step_image["step_id"], step_id);
    assert_eq!(
        step_image["url"],
        format!("/api/v2/admin/resources/project/{project_id}/media/image/9004")
    );

    let owner_only_route = fixture
        .app
        .clone()
        .oneshot(request(
            &format!("/media/projects/{project_id}/opaque.jpg"),
            Some(ADMIN_SESSION),
        ))
        .await
        .expect("router should answer");
    assert_eq!(owner_only_route.status(), StatusCode::FORBIDDEN);

    let image = fixture
        .app
        .clone()
        .oneshot(request(
            &format!("/admin/resources/project/{project_id}/media/image/9001"),
            Some(ADMIN_SESSION),
        ))
        .await
        .expect("router should answer");
    assert_eq!(image.status(), StatusCode::OK);
    assert_eq!(image.headers()["content-type"], "image/jpeg");
    assert_eq!(image.headers()["cache-control"], "no-store");
    assert_eq!(image.headers()["x-content-type-options"], "nosniff");
    assert_eq!(
        image.into_body().collect().await.unwrap().to_bytes(),
        "project image"
    );

    let pat_image = fixture
        .app
        .clone()
        .oneshot(
            Request::get(format!(
                "/admin/resources/project/{project_id}/media/image/9001"
            ))
            .header("authorization", format!("Bearer {ADMIN_PAT}"))
            .body(Body::empty())
            .unwrap(),
        )
        .await
        .expect("router should answer");
    assert_eq!(pat_image.status(), StatusCode::OK);
    assert_eq!(
        pat_image.into_body().collect().await.unwrap().to_bytes(),
        "project image"
    );

    let thumbnail = fixture
        .app
        .clone()
        .oneshot(request(
            &format!("/admin/resources/project/{project_id}/media/image/9001/thumbnail"),
            Some(ADMIN_SESSION),
        ))
        .await
        .expect("router should answer");
    assert_eq!(thumbnail.status(), StatusCode::OK);
    assert_eq!(
        thumbnail.into_body().collect().await.unwrap().to_bytes(),
        "project thumb"
    );

    let pat_thumbnail = fixture
        .app
        .clone()
        .oneshot(
            Request::get(format!(
                "/admin/resources/project/{project_id}/media/image/9001/thumbnail"
            ))
            .header("authorization", format!("Bearer {ADMIN_PAT}"))
            .body(Body::empty())
            .unwrap(),
        )
        .await
        .expect("router should answer");
    assert_eq!(pat_thumbnail.status(), StatusCode::OK);
    assert_eq!(
        pat_thumbnail
            .into_body()
            .collect()
            .await
            .unwrap()
            .to_bytes(),
        "project thumb"
    );

    let attachment = fixture
        .app
        .clone()
        .oneshot(request(
            &format!("/admin/resources/project/{project_id}/media/attachment/9001"),
            Some(ADMIN_SESSION),
        ))
        .await
        .expect("router should answer");
    assert_eq!(attachment.status(), StatusCode::OK);
    assert!(
        attachment.headers()["content-disposition"]
            .to_str()
            .unwrap()
            .starts_with("attachment;")
    );
    assert_eq!(attachment.headers()["content-type"], "application/pdf");
    assert_eq!(
        attachment.into_body().collect().await.unwrap().to_bytes(),
        "pdf bytes"
    );

    let pat_attachment = fixture
        .app
        .clone()
        .oneshot(
            Request::get(format!(
                "/admin/resources/project/{project_id}/media/attachment/9001"
            ))
            .header("authorization", format!("Bearer {ADMIN_PAT}"))
            .body(Body::empty())
            .unwrap(),
        )
        .await
        .expect("router should answer");
    assert_eq!(pat_attachment.status(), StatusCode::OK);
    assert!(
        pat_attachment.headers()["content-disposition"]
            .to_str()
            .unwrap()
            .starts_with("attachment;")
    );
    assert_eq!(
        pat_attachment
            .into_body()
            .collect()
            .await
            .unwrap()
            .to_bytes(),
        "pdf bytes"
    );

    let yarn_detail = fixture
        .app
        .clone()
        .oneshot(request(
            &format!("/admin/resources/yarn/{yarn_id}"),
            Some(ADMIN_SESSION),
        ))
        .await
        .expect("router should answer");
    let yarn_detail = json(yarn_detail).await;
    assert_eq!(
        yarn_detail["data"]["resource"]["photos"][0]["url"],
        format!("/api/v2/admin/resources/yarn/{yarn_id}/media/photo/9003")
    );
    let yarn_photo = fixture
        .app
        .clone()
        .oneshot(request(
            &format!("/admin/resources/yarn/{yarn_id}/media/photo/9003"),
            Some(ADMIN_SESSION),
        ))
        .await
        .expect("router should answer");
    assert_eq!(yarn_photo.status(), StatusCode::OK);

    let wrong_parent = fixture
        .app
        .clone()
        .oneshot(request(
            &format!("/admin/resources/yarn/{yarn_id}/media/image/9001"),
            Some(ADMIN_SESSION),
        ))
        .await
        .expect("router should answer");
    assert_eq!(wrong_parent.status(), StatusCode::NOT_FOUND);

    let attachment_thumbnail = fixture
        .app
        .clone()
        .oneshot(request(
            &format!("/admin/resources/project/{project_id}/media/attachment/9001/thumbnail"),
            Some(ADMIN_SESSION),
        ))
        .await
        .expect("router should answer");
    assert_eq!(attachment_thumbnail.status(), StatusCode::NOT_FOUND);

    let unknown_kind = fixture
        .app
        .clone()
        .oneshot(request(
            &format!("/admin/resources/category/{project_id}/media/image/9001"),
            Some(ADMIN_SESSION),
        ))
        .await
        .expect("router should answer");
    assert_eq!(unknown_kind.status(), StatusCode::NOT_FOUND);

    let member_media = fixture
        .app
        .clone()
        .oneshot(request(
            &format!("/admin/resources/project/{project_id}/media/image/9001"),
            Some(MEMBER_SESSION),
        ))
        .await
        .expect("router should answer");
    assert_eq!(member_media.status(), StatusCode::FORBIDDEN);
}

#[tokio::test]
async fn admin_resource_routes_are_get_only() {
    let fixture = fixture().await;
    let project_id = insert_project(&fixture.pool, fixture.member_id).await;
    for method in ["POST", "PUT", "PATCH", "DELETE"] {
        let response = fixture
            .app
            .clone()
            .oneshot(
                Request::builder()
                    .method(method)
                    .uri(format!("/admin/resources/project/{project_id}"))
                    .header(
                        "cookie",
                        format!("__Host-stricknani_session={ADMIN_SESSION}"),
                    )
                    .body(Body::empty())
                    .expect("request should be built"),
            )
            .await
            .expect("router should answer");
        assert_eq!(response.status(), StatusCode::METHOD_NOT_ALLOWED);
    }
}
