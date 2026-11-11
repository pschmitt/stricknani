//! Migrations 0031 (trash) and 0032 (project status) on a database as deployed
//! before either of them, with projects, steps, yarns, links, shares and media.

use sqlx::{SqlitePool, migrate::Migrator, sqlite::SqlitePoolOptions};

const TRASH_MIGRATION: i64 = 31;

async fn database_before_trash() -> SqlitePool {
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    let dir = std::env::temp_dir().join(format!(
        "stricknani-trash-status-migration-{}",
        std::process::id()
    ));
    std::fs::create_dir_all(&dir).unwrap();
    for entry in std::fs::read_dir(concat!(env!("CARGO_MANIFEST_DIR"), "/migrations")).unwrap() {
        let path = entry.unwrap().path();
        let version: i64 = path
            .file_name()
            .and_then(|name| name.to_str())
            .and_then(|name| name.split('_').next())
            .and_then(|prefix| prefix.parse().ok())
            .unwrap();
        if version < TRASH_MIGRATION {
            std::fs::copy(&path, dir.join(path.file_name().unwrap())).unwrap();
        }
    }
    Migrator::new(dir.clone())
        .await
        .unwrap()
        .run(&pool)
        .await
        .unwrap();
    std::fs::remove_dir_all(&dir).unwrap();
    pool
}

#[tokio::test]
async fn trash_then_status_migrations_keep_a_realistic_database_intact() {
    let pool = database_before_trash().await;
    let user =
        sqlx::query("INSERT INTO users (email, password_hash) VALUES ('a@example.invalid', 'x')")
            .execute(&pool)
            .await
            .unwrap()
            .last_insert_rowid();
    let other =
        sqlx::query("INSERT INTO users (email, password_hash) VALUES ('b@example.invalid', 'x')")
            .execute(&pool)
            .await
            .unwrap()
            .last_insert_rowid();
    let project = sqlx::query(
        "INSERT INTO projects (owner_id, name, tags, created_at, updated_at) \
         VALUES (?, 'Old cardigan', '[]', '2020-01-01T00:00:00Z', '2020-01-01T00:00:00Z')",
    )
    .bind(user)
    .execute(&pool)
    .await
    .unwrap()
    .last_insert_rowid();
    let yarn = sqlx::query(
        "INSERT INTO yarns (owner_id, name, created_at, updated_at) \
         VALUES (?, 'Merino', '2020-01-01T00:00:00Z', '2020-01-01T00:00:00Z')",
    )
    .bind(user)
    .execute(&pool)
    .await
    .unwrap()
    .last_insert_rowid();
    sqlx::query("INSERT INTO project_yarns (project_id, yarn_id) VALUES (?, ?)")
        .bind(project)
        .bind(yarn)
        .execute(&pool)
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO project_steps (project_id, title, step_number) VALUES (?, 'Cast on', 1)",
    )
    .bind(project)
    .execute(&pool)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO resource_shares (kind, resource_id, user_id, role, created_by, created_at, updated_at) \
         VALUES ('project', ?, ?, 'viewer', ?, '2020-01-01T00:00:00Z', '2020-01-01T00:00:00Z')",
    )
    .bind(project)
    .bind(other)
    .bind(user)
    .execute(&pool)
    .await
    .unwrap();

    sqlx::migrate!().run(&pool).await.unwrap();

    // Existing rows are live and in progress; nothing was lost.
    let (status, deleted_at): (String, Option<String>) =
        sqlx::query_as("SELECT status, deleted_at FROM projects WHERE id = ?")
            .bind(project)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(status, "in_progress");
    assert!(deleted_at.is_none());
    let yarn_deleted: Option<String> =
        sqlx::query_scalar("SELECT deleted_at FROM yarns WHERE id = ?")
            .bind(yarn)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert!(yarn_deleted.is_none());
    for (table, expected) in [
        ("project_steps", 1_i64),
        ("project_yarns", 1),
        ("resource_shares", 1),
    ] {
        let count: i64 =
            sqlx::query_scalar(sqlx::AssertSqlSafe(format!("SELECT COUNT(*) FROM {table}")))
                .fetch_one(&pool)
                .await
                .unwrap();
        assert_eq!(count, expected, "{table}");
    }

    // Both features work on the migrated rows, together.
    sqlx::query(
        "UPDATE projects SET status = 'done', deleted_at = '2026-11-01T00:00:00Z' WHERE id = ?",
    )
    .bind(project)
    .execute(&pool)
    .await
    .unwrap();
    let live: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM projects WHERE status = 'done' AND deleted_at IS NULL",
    )
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(live, 0);
}
