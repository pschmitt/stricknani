//! Apply explicit provider configuration changes without stranding local users.

use std::collections::{HashMap, HashSet};

use sqlx::{Row, SqlitePool};

#[derive(Debug, thiserror::Error)]
pub enum ConfigurationError {
    #[error("provider removal would disable the final login method")]
    LastLoginMethod,
    #[error("cannot apply provider configuration")]
    Database(#[from] sqlx::Error),
}

/// Revoke credentials for removed providers, preserving every active user's
/// final login method. Call for configured IDs, never for discovery outcomes.
///
/// The check and revocations share SQLite's serialized write transaction.
pub async fn reconcile(
    pool: &SqlitePool,
    enabled_provider_ids: &[String],
) -> Result<(), ConfigurationError> {
    let enabled: HashSet<&str> = enabled_provider_ids.iter().map(String::as_str).collect();
    let mut tx = pool.begin().await?;
    sqlx::query("UPDATE users SET id = id WHERE active = 1 AND password_enabled = 0")
        .execute(&mut *tx)
        .await?;
    let rows = sqlx::query(
        "SELECT u.id, i.provider_id FROM users u JOIN oidc_identities i ON i.user_id = u.id \
         WHERE u.active = 1 AND u.password_enabled = 0",
    )
    .fetch_all(&mut *tx)
    .await?;
    let mut usable = HashMap::new();
    for row in rows {
        let id: i64 = row.get("id");
        let provider: String = row.get("provider_id");
        let entry = usable.entry(id).or_insert(false);
        *entry |= enabled.contains(provider.as_str());
    }
    if usable.values().any(|has_method| !has_method) {
        return Err(ConfigurationError::LastLoginMethod);
    }
    let providers: Vec<String> = sqlx::query_scalar(
        "SELECT DISTINCT oidc_provider_id FROM api_tokens \
         WHERE auth_origin = 'oidc' AND revoked_at IS NULL AND oidc_provider_id IS NOT NULL",
    )
    .fetch_all(&mut *tx)
    .await?;
    for provider in providers {
        if enabled.contains(provider.as_str()) {
            continue;
        }
        let revoked = sqlx::query(
            "UPDATE api_tokens SET revoked_at = unixepoch() \
             WHERE auth_origin = 'oidc' AND oidc_provider_id = ? AND revoked_at IS NULL",
        )
        .bind(&provider)
        .execute(&mut *tx)
        .await?;
        if revoked.rows_affected() > 0 {
            sqlx::query(
                "INSERT INTO auth_audit_events(action, provider_id, created_at) \
                 VALUES ('oidc_provider_tokens_revoked', ?, unixepoch())",
            )
            .bind(&provider)
            .execute(&mut *tx)
            .await?;
        }
    }
    tx.commit().await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::{ConfigurationError, reconcile};
    use sqlx::SqlitePool;

    async fn fixture(password_enabled: bool) -> (SqlitePool, i64) {
        let pool = crate::db::connect("sqlite::memory:").await.unwrap();
        let user = crate::db::create_user(
            &pool,
            "provider-policy@example.test",
            "provider-policy-password".into(),
        )
        .await
        .unwrap();
        sqlx::query("UPDATE users SET password_enabled = ? WHERE id = ?")
            .bind(password_enabled)
            .bind(user)
            .execute(&pool)
            .await
            .unwrap();
        let identity = sqlx::query("INSERT INTO oidc_identities(user_id, provider_id, issuer, subject, linked_at) VALUES (?, 'authelia', 'https://issuer.example.test/', 'opaque-test-subject', unixepoch())")
            .bind(user).execute(&pool).await.unwrap().last_insert_rowid();
        for purpose in ["api", "browser"] {
            sqlx::query("INSERT INTO api_tokens(user_id, name, token_hash, created_at, expires_at, purpose, auth_origin, oidc_identity_id, oidc_provider_id) VALUES (?, 'provider policy fixture', ?, unixepoch(), unixepoch()+3600, ?, 'oidc', ?, 'authelia')")
                .bind(user).bind(format!("provider-policy-{purpose}-hash")).bind(purpose).bind(identity).execute(&pool).await.unwrap();
        }
        sqlx::query("INSERT INTO api_tokens(user_id, name, token_hash, created_at) VALUES (?, 'local fixture', 'provider-policy-local-hash', unixepoch())")
            .bind(user).execute(&pool).await.unwrap();
        (pool, user)
    }

    #[tokio::test]
    async fn configured_provider_outage_does_not_revoke_and_final_method_removal_rolls_back() {
        let (pool, _) = fixture(false).await;
        reconcile(&pool, &["authelia".to_owned()]).await.unwrap();
        assert!(matches!(
            reconcile(&pool, &[]).await,
            Err(ConfigurationError::LastLoginMethod)
        ));
        let revoked: i64 =
            sqlx::query_scalar("SELECT count(*) FROM api_tokens WHERE revoked_at IS NOT NULL")
                .fetch_one(&pool)
                .await
                .unwrap();
        assert_eq!(revoked, 0);
    }

    #[tokio::test]
    async fn explicit_provider_removal_revokes_only_its_credentials() {
        let (pool, user) = fixture(true).await;
        reconcile(&pool, &[]).await.unwrap();
        let counts: (i64, i64) = sqlx::query_as("SELECT sum(auth_origin = 'oidc' AND revoked_at IS NOT NULL), sum(auth_origin = 'local' AND revoked_at IS NOT NULL) FROM api_tokens WHERE user_id = ?")
            .bind(user).fetch_one(&pool).await.unwrap();
        assert_eq!(counts, (2, 0));
        let events: i64 = sqlx::query_scalar(
            "SELECT count(*) FROM auth_audit_events WHERE action = 'oidc_provider_tokens_revoked'",
        )
        .fetch_one(&pool)
        .await
        .unwrap();
        assert_eq!(events, 1);
    }
}
