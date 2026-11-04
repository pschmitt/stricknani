//! Migration 0017 must apply to a database that already holds real-shaped
//! account and token rows, without changing their meaning.

use sqlx::{Row, migrate::Migrator, sqlite::SqlitePoolOptions};

const OIDC_MIGRATION: i64 = 17;

#[tokio::test]
async fn migration_0017_preserves_existing_accounts_and_tokens() {
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();

    // Everything before 0017: the database as deployed on the preview.
    let all = sqlx::migrate!();
    let dir =
        std::env::temp_dir().join(format!("stricknani-oidc-migration-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let mut copied = 0;
    for entry in std::fs::read_dir(concat!(env!("CARGO_MANIFEST_DIR"), "/migrations")).unwrap() {
        let path = entry.unwrap().path();
        let version: i64 = path
            .file_name()
            .and_then(|name| name.to_str())
            .and_then(|name| name.split('_').next())
            .and_then(|prefix| prefix.parse().ok())
            .unwrap();
        if version < OIDC_MIGRATION {
            std::fs::copy(&path, dir.join(path.file_name().unwrap())).unwrap();
            copied += 1;
        }
    }
    assert!(copied >= 15);
    Migrator::new(dir.clone())
        .await
        .unwrap()
        .run(&pool)
        .await
        .unwrap();
    std::fs::remove_dir_all(&dir).unwrap();

    let local = sqlx::query(
        "INSERT INTO users (email, password_hash, is_admin, active) \
         VALUES ('knitter@example.invalid', '$2b$12$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyz0123', 0, 1)",
    )
    .execute(&pool)
    .await
    .unwrap()
    .last_insert_rowid();
    let disabled = sqlx::query(
        "INSERT INTO users (email, password_hash, is_admin, active) \
         VALUES ('gone@example.invalid', 'legacy-hash', 0, 0)",
    )
    .execute(&pool)
    .await
    .unwrap()
    .last_insert_rowid();
    for (user, name, hash, purpose, expires) in [
        (
            local,
            "Browser session",
            "hash-browser",
            "browser",
            Some(4_000_000_000_i64),
        ),
        (local, "Android", "hash-api-forever", "api", None),
        (
            local,
            "CLI",
            "hash-api-expiring",
            "api",
            Some(4_000_000_000),
        ),
        (disabled, "Old", "hash-old", "api", None),
    ] {
        sqlx::query(
            "INSERT INTO api_tokens (user_id, name, token_hash, created_at, expires_at, purpose) \
             VALUES (?, ?, ?, 1700000000, ?, ?)",
        )
        .bind(user)
        .bind(name)
        .bind(hash)
        .bind(expires)
        .bind(purpose)
        .execute(&pool)
        .await
        .unwrap();
    }
    sqlx::query("UPDATE api_tokens SET revoked_at = 1700000100 WHERE token_hash = 'hash-old'")
        .execute(&pool)
        .await
        .unwrap();

    // Now the full migration set: only the new migrations are pending.
    all.run(&pool).await.unwrap();

    let users =
        sqlx::query("SELECT email, password_hash, active, password_enabled FROM users ORDER BY id")
            .fetch_all(&pool)
            .await
            .unwrap();
    assert_eq!(users.len(), 2);
    assert_eq!(
        users[0].get::<String, _>("email"),
        "knitter@example.invalid"
    );
    assert!(
        users[0]
            .get::<String, _>("password_hash")
            .starts_with("$2b$12$")
    );
    assert_eq!(users[0].get::<i64, _>("password_enabled"), 1);
    assert_eq!(users[1].get::<i64, _>("active"), 0);
    assert_eq!(users[1].get::<i64, _>("password_enabled"), 1);

    let tokens = sqlx::query(
        "SELECT token_hash, expires_at, revoked_at, auth_origin, oidc_identity_id, \
         oidc_provider_id, reauth_at FROM api_tokens ORDER BY id",
    )
    .fetch_all(&pool)
    .await
    .unwrap();
    assert_eq!(tokens.len(), 4);
    for token in &tokens {
        assert_eq!(token.get::<String, _>("auth_origin"), "local");
        assert_eq!(token.get::<Option<i64>, _>("oidc_identity_id"), None);
        assert_eq!(token.get::<Option<String>, _>("oidc_provider_id"), None);
        assert_eq!(token.get::<Option<i64>, _>("reauth_at"), None);
    }
    assert_eq!(tokens[1].get::<Option<i64>, _>("expires_at"), None);
    assert_eq!(
        tokens[3].get::<Option<i64>, _>("revoked_at"),
        Some(1_700_000_100)
    );

    // Existing local tokens stay updatable (trigger must not reject them).
    sqlx::query(
        "UPDATE api_tokens SET expires_at = 4000000001 WHERE token_hash = 'hash-api-expiring'",
    )
    .execute(&pool)
    .await
    .unwrap();

    // The new constraints are live: an OIDC-origin token must carry an
    // identity and a finite lifetime.
    let identity = sqlx::query(
        "INSERT INTO oidc_identities (user_id, provider_id, issuer, subject, linked_at) \
         VALUES (?, 'authelia', 'https://id.example.invalid', 'subject-1', 1700000200)",
    )
    .bind(local)
    .execute(&pool)
    .await
    .unwrap()
    .last_insert_rowid();
    let insert_oidc = |expires: Option<i64>, identity: Option<i64>| {
        sqlx::query(
            "INSERT INTO api_tokens (user_id, name, token_hash, created_at, expires_at, purpose, \
             auth_origin, oidc_identity_id, oidc_provider_id) \
             VALUES (?, 'OIDC', 'hash-oidc', 1700000300, ?, 'api', 'oidc', ?, 'authelia')",
        )
        .bind(local)
        .bind(expires)
        .bind(identity)
    };
    assert!(
        insert_oidc(None, Some(identity))
            .execute(&pool)
            .await
            .is_err()
    );
    assert!(
        insert_oidc(Some(1_700_000_300 + 604_801), Some(identity))
            .execute(&pool)
            .await
            .is_err()
    );
    assert!(
        insert_oidc(Some(1_700_000_300 + 3600), None)
            .execute(&pool)
            .await
            .is_err()
    );
    insert_oidc(Some(1_700_000_300 + 3600), Some(identity))
        .execute(&pool)
        .await
        .unwrap();
}
