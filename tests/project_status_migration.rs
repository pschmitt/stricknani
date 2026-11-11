//! Migration 0032 adds `projects.status`: every project that exists before it
//! is in progress, and only the two known statuses are accepted afterwards.

use sqlx::{migrate::Migrator, sqlite::SqlitePoolOptions};

const STATUS_MIGRATION: i64 = 32;

#[tokio::test]
async fn migration_0032_marks_existing_projects_in_progress_and_checks_values() {
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();

    // Everything before 0032: the database as deployed before project status.
    let dir =
        std::env::temp_dir().join(format!("stricknani-status-migration-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    for entry in std::fs::read_dir(concat!(env!("CARGO_MANIFEST_DIR"), "/migrations")).unwrap() {
        let path = entry.unwrap().path();
        let version: i64 = path
            .file_name()
            .and_then(|name| name.to_str())
            .and_then(|name| name.split('_').next())
            .and_then(|prefix| prefix.parse().ok())
            .unwrap();
        if version < STATUS_MIGRATION {
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

    let user_id = sqlx::query("INSERT INTO users (email, password_hash) VALUES ('a@example.invalid', 'x')")
        .execute(&pool)
        .await
        .unwrap()
        .last_insert_rowid();
    let project_id = sqlx::query(
        "INSERT INTO projects (owner_id, name, created_at, updated_at) \
         VALUES (?, 'Old cardigan', '2020-01-01T00:00:00Z', '2020-01-01T00:00:00Z')",
    )
    .bind(user_id)
    .execute(&pool)
    .await
    .unwrap()
    .last_insert_rowid();

    sqlx::migrate!().run(&pool).await.unwrap();

    let status: String = sqlx::query_scalar("SELECT status FROM projects WHERE id = ?")
        .bind(project_id)
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(status, "in_progress");

    sqlx::query("UPDATE projects SET status = 'done' WHERE id = ?")
        .bind(project_id)
        .execute(&pool)
        .await
        .unwrap();
    let rejected = sqlx::query("UPDATE projects SET status = 'archived' WHERE id = ?")
        .bind(project_id)
        .execute(&pool)
        .await;
    assert!(rejected.is_err(), "unknown statuses must violate the CHECK");
}
