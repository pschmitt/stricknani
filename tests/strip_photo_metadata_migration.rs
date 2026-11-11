//! Migration 0034 adds `users.strip_photo_metadata`: off for every account that exists before it
//! (so nobody's behaviour changes) and only 0 or 1 are accepted afterwards.

use sqlx::{migrate::Migrator, sqlite::SqlitePoolOptions};

const STRIP_MIGRATION: i64 = 34;

#[tokio::test]
async fn migration_0034_leaves_existing_accounts_off_and_checks_values() {
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();

    // Everything before 0034: the database as deployed before the setting.
    let dir = std::env::temp_dir().join(format!(
        "stricknani-strip-metadata-migration-{}",
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
        if version < STRIP_MIGRATION {
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

    let user_id =
        sqlx::query("INSERT INTO users (email, password_hash) VALUES ('a@example.invalid', 'x')")
            .execute(&pool)
            .await
            .unwrap()
            .last_insert_rowid();

    sqlx::migrate!().run(&pool).await.unwrap();

    let stored: i64 = sqlx::query_scalar("SELECT strip_photo_metadata FROM users WHERE id = ?")
        .bind(user_id)
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(stored, 0, "existing accounts keep today's behaviour");

    let fresh =
        sqlx::query("INSERT INTO users (email, password_hash) VALUES ('b@example.invalid', 'x')")
            .execute(&pool)
            .await
            .unwrap()
            .last_insert_rowid();
    let stored: i64 = sqlx::query_scalar("SELECT strip_photo_metadata FROM users WHERE id = ?")
        .bind(fresh)
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(stored, 0, "new accounts default to off");

    sqlx::query("UPDATE users SET strip_photo_metadata = 1 WHERE id = ?")
        .bind(user_id)
        .execute(&pool)
        .await
        .unwrap();
    let rejected = sqlx::query("UPDATE users SET strip_photo_metadata = 2 WHERE id = ?")
        .bind(user_id)
        .execute(&pool)
        .await;
    assert!(rejected.is_err(), "only 0 and 1 satisfy the CHECK");
}
