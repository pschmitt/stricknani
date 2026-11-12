//! Migration 0038 (on top of 0037) adds the `passkeys` table and widens the step-up purposes and auth-audit actions
//! (two tables rebuilt because SQLite cannot alter a CHECK): the rows that exist before it survive,
//! the new values are accepted and unknown ones still are not.

use sqlx::{migrate::Migrator, sqlite::SqlitePoolOptions};

const PASSKEY_MIGRATION: i64 = 38;

#[tokio::test]
async fn migration_0038_keeps_existing_auth_rows_and_accepts_passkey_values() {
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    sqlx::query("PRAGMA foreign_keys = ON")
        .execute(&pool)
        .await
        .unwrap();

    // The database as deployed before passkeys.
    let dir = std::env::temp_dir().join(format!(
        "stricknani-passkeys-migration-{}",
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
        if version < PASSKEY_MIGRATION {
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
    let token_id = sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, purpose) \
         VALUES (?, 't', ?, 1, 'api')",
    )
    .bind(user_id)
    .bind("ab".repeat(32))
    .execute(&pool)
    .await
    .unwrap()
    .last_insert_rowid();
    sqlx::query(
        "INSERT INTO auth_reauth_proofs (proof_hash, user_id, api_token_id, purpose, \
         target_user_id, expires_at) VALUES (?, ?, ?, 'change_password', ?, 4000000000)",
    )
    .bind("cd".repeat(32))
    .bind(user_id)
    .bind(token_id)
    .bind(user_id)
    .execute(&pool)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO auth_audit_events (action, actor_user_id, target_user_id, created_at) \
         VALUES ('api_token_created', ?, ?, 5)",
    )
    .bind(user_id)
    .bind(user_id)
    .execute(&pool)
    .await
    .unwrap();

    // What 0037 added: a restore proof, and a restore audit row with its bounded details.
    sqlx::query(
        "INSERT INTO auth_reauth_proofs (proof_hash, user_id, api_token_id, purpose, \
         target_user_id, expires_at) VALUES (?, ?, ?, 'restore_backup', ?, 4000000000)",
    )
    .bind("12".repeat(32))
    .bind(user_id)
    .bind(token_id)
    .bind(user_id)
    .execute(&pool)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO auth_audit_events (action, actor_user_id, target_user_id, created_at, details) \
         VALUES ('backup_restored', ?, ?, 7, '{\"backup\":\"predeploy-1.7.0\"}')",
    )
    .bind(user_id)
    .bind(user_id)
    .execute(&pool)
    .await
    .unwrap();

    sqlx::migrate!().run(&pool).await.unwrap();

    let proofs: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM auth_reauth_proofs WHERE purpose = 'change_password'",
    )
    .fetch_one(&pool)
    .await
    .unwrap();
    let audits: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM auth_audit_events WHERE action = 'api_token_created' AND created_at = 5",
    )
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!((proofs, audits), (1, 1), "existing rows are carried over");
    let details: Option<String> = sqlx::query_scalar(
        "SELECT details FROM auth_audit_events WHERE action = 'backup_restored' AND created_at = 7",
    )
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(
        details.as_deref(),
        Some("{\"backup\":\"predeploy-1.7.0\"}"),
        "0037's audit details are copied, not dropped"
    );
    let restore_proofs: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM auth_reauth_proofs WHERE purpose = 'restore_backup'",
    )
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(restore_proofs, 1, "restore_backup proofs survive");

    sqlx::query(
        "INSERT INTO auth_reauth_proofs (proof_hash, user_id, api_token_id, purpose, \
         target_user_id, expires_at) VALUES (?, ?, ?, 'manage_passkeys', ?, 4000000000)",
    )
    .bind("ef".repeat(32))
    .bind(user_id)
    .bind(token_id)
    .bind(user_id)
    .execute(&pool)
    .await
    .expect("manage_passkeys is a step-up purpose");
    for action in [
        "passkey_registered",
        "passkey_removed",
        "passkey_login_succeeded",
        "passkey_reauthenticated",
        "backup_restored",
    ] {
        sqlx::query("INSERT INTO auth_audit_events (action, created_at) VALUES (?, 6)")
            .bind(action)
            .execute(&pool)
            .await
            .unwrap_or_else(|error| panic!("{action} is an audit action: {error}"));
    }
    assert!(
        sqlx::query("INSERT INTO auth_audit_events (action, created_at) VALUES ('nonsense', 6)")
            .execute(&pool)
            .await
            .is_err(),
        "unknown actions are still refused"
    );

    // The passkeys table: one credential id once, bounded fields, gone with the account.
    let insert = |credential: Vec<u8>, nickname: &'static str| {
        let pool = pool.clone();
        async move {
            sqlx::query(
                "INSERT INTO passkeys (user_id, credential_id, user_handle, passkey, nickname, \
                 created_at) VALUES (?, ?, ?, '{}', ?, 1)",
            )
            .bind(user_id)
            .bind(credential)
            .bind(vec![1_u8; 16])
            .bind(nickname)
            .execute(&pool)
            .await
        }
    };
    insert(vec![9; 32], "Pixel").await.unwrap();
    assert!(
        insert(vec![9; 32], "Again").await.is_err(),
        "unique credential id"
    );
    assert!(
        insert(vec![8; 4], "Short").await.is_err(),
        "credential id length"
    );
    assert!(
        insert(vec![7; 32], "").await.is_err(),
        "nickname is not empty"
    );
    sqlx::query("DELETE FROM users WHERE id = ?")
        .bind(user_id)
        .execute(&pool)
        .await
        .unwrap();
    let left: i64 = sqlx::query_scalar("SELECT count(*) FROM passkeys")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(left, 0, "cascade on user delete");
}
