//! Migration 0036 adds `impersonation_tokens`: a credential is bound to two
//! different accounts, must expire after it was created, and disappears with
//! either account.

use sqlx::sqlite::SqlitePoolOptions;

async fn pool_with_two_users() -> (sqlx::SqlitePool, i64, i64) {
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    sqlx::query("PRAGMA foreign_keys = ON")
        .execute(&pool)
        .await
        .unwrap();
    sqlx::migrate!().run(&pool).await.unwrap();
    let mut ids = Vec::new();
    for email in ["a@example.invalid", "b@example.invalid"] {
        ids.push(
            sqlx::query("INSERT INTO users (email, password_hash) VALUES (?, 'x')")
                .bind(email)
                .execute(&pool)
                .await
                .unwrap()
                .last_insert_rowid(),
        );
    }
    (pool, ids[0], ids[1])
}

async fn insert(
    pool: &sqlx::SqlitePool,
    hash: &str,
    admin: i64,
    target: i64,
    ttl: i64,
) -> Result<sqlx::sqlite::SqliteQueryResult, sqlx::Error> {
    sqlx::query(
        "INSERT INTO impersonation_tokens \
         (token_hash, admin_user_id, target_user_id, created_at, expires_at) \
         VALUES (?, ?, ?, unixepoch(), unixepoch() + ?)",
    )
    .bind(hash)
    .bind(admin)
    .bind(target)
    .bind(ttl)
    .execute(pool)
    .await
}

#[tokio::test]
async fn migration_0036_constrains_and_cascades_impersonation_tokens() {
    let (pool, admin, target) = pool_with_two_users().await;
    insert(&pool, "h1", admin, target, 3600).await.unwrap();
    assert!(
        insert(&pool, "h1", admin, target, 3600).await.is_err(),
        "unique hash"
    );
    assert!(
        insert(&pool, "h2", admin, admin, 3600).await.is_err(),
        "not oneself"
    );
    assert!(
        insert(&pool, "h3", admin, target, 0).await.is_err(),
        "must expire later"
    );
    assert!(
        insert(&pool, "h4", admin, 9999, 3600).await.is_err(),
        "target exists"
    );

    sqlx::query("DELETE FROM users WHERE id = ?")
        .bind(target)
        .execute(&pool)
        .await
        .unwrap();
    let left: i64 = sqlx::query_scalar("SELECT count(*) FROM impersonation_tokens")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(left, 0, "deleting the account removes its credentials");
}
