use axum::body::Body;
use axum::http::{Request, StatusCode};
use sha2::{Digest, Sha256};
use sqlx::{SqlitePool, Transaction};
use stricknani_server::{db, media, media_ids};
use tower::ServiceExt;

const TOKEN: &str = "media-public-id-token";

async fn owner(pool: &SqlitePool) -> i64 {
    let id = sqlx::query(
        "INSERT INTO users (email, password_hash) VALUES ('media-id@example.test', 'test-hash')",
    )
    .execute(pool)
    .await
    .expect("test user should be inserted")
    .last_insert_rowid();
    let hash = hex::encode(Sha256::digest(TOKEN.as_bytes()));
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at) \
         VALUES (?, 'test', ?, unixepoch())",
    )
    .bind(id)
    .bind(hash)
    .execute(pool)
    .await
    .expect("test token should be inserted");
    id
}

async fn project(pool: &SqlitePool, owner_id: i64) -> i64 {
    sqlx::query(
        "INSERT INTO projects (owner_id, name, tags, created_at, updated_at) \
         VALUES (?, 'Project', '[]', '2020-01-01T00:00:00Z', '2020-01-01T00:00:00Z')",
    )
    .bind(owner_id)
    .execute(pool)
    .await
    .expect("project should be inserted")
    .last_insert_rowid()
}

async fn yarn(pool: &SqlitePool, owner_id: i64) -> i64 {
    sqlx::query(
        "INSERT INTO yarns (owner_id, name, created_at, updated_at) \
         VALUES (?, 'Yarn', '2020-01-01T00:00:00Z', '2020-01-01T00:00:00Z')",
    )
    .bind(owner_id)
    .execute(pool)
    .await
    .expect("yarn should be inserted")
    .last_insert_rowid()
}

async fn media_row(
    pool: &SqlitePool,
    owner_id: i64,
    project_id: Option<i64>,
    yarn_id: Option<i64>,
    kind: &str,
    public_id: i64,
    filename: &str,
) -> i64 {
    sqlx::query(
        "INSERT INTO media_files \
         (owner_id, project_id, yarn_id, kind, public_id, filename, original_filename, content_type, size_bytes) \
         VALUES (?, ?, ?, ?, ?, ?, ?, 'application/octet-stream', 1)",
    )
    .bind(owner_id)
    .bind(project_id)
    .bind(yarn_id)
    .bind(kind)
    .bind(public_id)
    .bind(filename)
    .bind(filename)
    .execute(pool)
    .await
    .expect("media row should be inserted")
    .last_insert_rowid()
}

async fn allocate(tx: &mut Transaction<'_, sqlx::Sqlite>, namespace: media_ids::Namespace) -> i64 {
    media_ids::allocate(tx, namespace)
        .await
        .expect("namespace ID should be allocated")
}

#[tokio::test]
async fn independent_media_namespaces_can_share_public_ids_and_delete_by_kind() {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("database should migrate");
    let owner_id = owner(&pool).await;
    let project_id = project(&pool, owner_id).await;
    let yarn_id = yarn(&pool, owner_id).await;

    let image_internal_id = media_row(
        &pool,
        owner_id,
        Some(project_id),
        None,
        "title",
        1,
        "image.png",
    )
    .await;
    let attachment_internal_id = media_row(
        &pool,
        owner_id,
        Some(project_id),
        None,
        "attachment",
        1,
        "pattern.pdf",
    )
    .await;
    let photo_internal_id =
        media_row(&pool, owner_id, None, Some(yarn_id), "photo", 1, "yarn.png").await;

    let counts: (i64, i64, i64) = sqlx::query_as(
        "SELECT \
            (SELECT COUNT(*) FROM media_files WHERE kind IN ('title', 'step') AND public_id = 1), \
            (SELECT COUNT(*) FROM media_files WHERE kind = 'attachment' AND public_id = 1), \
            (SELECT COUNT(*) FROM media_files WHERE kind = 'photo' AND public_id = 1)",
    )
    .fetch_one(&pool)
    .await
    .expect("namespace collision counts should be read");
    assert_eq!(counts, (1, 1, 1));

    let mut tx = pool.begin().await.expect("transaction should begin");
    assert_eq!(allocate(&mut tx, media_ids::Namespace::Image).await, 2);
    assert_eq!(allocate(&mut tx, media_ids::Namespace::Attachment).await, 2);
    assert_eq!(allocate(&mut tx, media_ids::Namespace::Photo).await, 2);
    tx.rollback()
        .await
        .expect("namespace sequence checks should roll back");

    let root = tempfile::tempdir().expect("media root should be created");
    let state = db::AppState::new(pool.clone()).with_media_root(root.path().to_path_buf());
    let response = media::router()
        .with_state(state)
        .oneshot(
            Request::delete(format!("/api/v1/projects/{project_id}/attachments/1"))
                .header("authorization", format!("Bearer {TOKEN}"))
                .body(Body::empty())
                .expect("delete request should be valid"),
        )
        .await
        .expect("router should respond");
    assert_eq!(response.status(), StatusCode::NO_CONTENT);

    let exists: (i64, i64, i64) = sqlx::query_as(
        "SELECT \
            EXISTS(SELECT 1 FROM media_files WHERE id = ?), \
            EXISTS(SELECT 1 FROM media_files WHERE id = ?), \
            EXISTS(SELECT 1 FROM media_files WHERE id = ?)",
    )
    .bind(image_internal_id)
    .bind(attachment_internal_id)
    .bind(photo_internal_id)
    .fetch_one(&pool)
    .await
    .expect("remaining media rows should be checked");
    assert_eq!(exists, (1, 0, 1));
}

#[tokio::test]
async fn committed_public_ids_are_not_reused_after_deletion_and_rollback_is_atomic() {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("database should migrate");
    let owner_id = owner(&pool).await;
    let project_id = project(&pool, owner_id).await;

    let mut rolled_back = pool.begin().await.expect("transaction should begin");
    let rolled_back_id = allocate(&mut rolled_back, media_ids::Namespace::Image).await;
    rolled_back
        .rollback()
        .await
        .expect("allocation should roll back");

    let mut tx = pool.begin().await.expect("transaction should begin");
    let first_id = allocate(&mut tx, media_ids::Namespace::Image).await;
    assert_eq!(first_id, rolled_back_id);
    sqlx::query(
        "INSERT INTO media_files \
         (owner_id, project_id, kind, public_id, filename, original_filename, content_type, size_bytes) \
         VALUES (?, ?, 'title', ?, 'first.png', 'first.png', 'image/png', 1)",
    )
    .bind(owner_id)
    .bind(project_id)
    .bind(first_id)
    .execute(&mut *tx)
    .await
    .expect("allocated row should be inserted");
    tx.commit().await.expect("allocation should commit");

    sqlx::query("DELETE FROM media_files WHERE public_id = ? AND kind = 'title'")
        .bind(first_id)
        .execute(&pool)
        .await
        .expect("media row should be deleted");

    let mut tx = pool.begin().await.expect("transaction should begin");
    let next_id = allocate(&mut tx, media_ids::Namespace::Image).await;
    tx.commit().await.expect("next allocation should commit");
    assert_eq!(next_id, first_id + 1);
}

#[tokio::test]
async fn media_public_ids_must_be_positive() {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("database should migrate");
    let owner_id = owner(&pool).await;
    let project_id = project(&pool, owner_id).await;

    let result = sqlx::query(
        "INSERT INTO media_files \
         (owner_id, project_id, kind, public_id, filename, original_filename, content_type, size_bytes) \
         VALUES (?, ?, 'title', 0, 'zero.png', 'zero.png', 'image/png', 1)",
    )
    .bind(owner_id)
    .bind(project_id)
    .execute(&pool)
    .await;
    assert!(result.is_err(), "zero public IDs must be rejected");

    let result = sqlx::query(
        "INSERT INTO media_files \
         (owner_id, project_id, kind, filename, original_filename, content_type, size_bytes) \
         VALUES (?, ?, 'title', 'missing.png', 'missing.png', 'image/png', 1)",
    )
    .bind(owner_id)
    .bind(project_id)
    .execute(&pool)
    .await;
    assert!(result.is_err(), "missing public IDs must be rejected");
}
