//! Shared, owner-checked administration operations for browser adapters.

use serde::Serialize;
use sqlx::{Row, SqlitePool};
use zeroize::Zeroizing;

use crate::{auth, db};

const PAGE_SIZE: i64 = 50;

#[derive(Debug)]
pub(crate) enum AdminError {
    Database,
    Forbidden,
    NotFound,
    Conflict,
    /// An administrator tried to disable their own account.
    SelfDeactivation,
    InvalidEmail,
    PasswordTooLong,
    PasswordPolicy(auth::NewPasswordError),
    AccountExists,
    InvalidPage,
}

#[derive(Clone, Debug, Serialize)]
pub(crate) struct AdminUser {
    pub(crate) id: i64,
    pub(crate) email: String,
    pub(crate) active: bool,
    pub(crate) is_admin: bool,
    pub(crate) avatar_url: Option<String>,
}

pub(crate) struct UserPage {
    pub(crate) users: Vec<AdminUser>,
    pub(crate) page: u32,
    pub(crate) per_page: u32,
    pub(crate) has_more: bool,
}

fn decode_user(gravatar_enabled: bool) -> impl Fn(sqlx::sqlite::SqliteRow) -> AdminUser {
    move |row| {
        let id: i64 = row.get("id");
        let use_gravatar = row.get::<i64, _>("use_gravatar") != 0;
        let filename = row.get::<Option<String>, _>("avatar_filename");
        AdminUser {
            id,
            email: row.get("email"),
            active: row.get::<i64, _>("active") != 0,
            is_admin: row.get::<i64, _>("is_admin") != 0,
            avatar_url: crate::media::admin_avatar(
                id,
                filename.as_deref(),
                use_gravatar,
                gravatar_enabled,
            )
            .map(|(url, _)| url),
        }
    }
}

async fn require_admin(pool: &SqlitePool, actor_id: i64) -> Result<(), AdminError> {
    let allowed: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM users WHERE id = ? AND active = 1 AND is_admin = 1)",
    )
    .bind(actor_id)
    .fetch_one(pool)
    .await
    .map_err(|_| AdminError::Database)?;
    if allowed {
        Ok(())
    } else {
        Err(AdminError::Forbidden)
    }
}

pub(crate) async fn require_active_admin(
    pool: &SqlitePool,
    actor_id: i64,
) -> Result<(), AdminError> {
    require_admin(pool, actor_id).await
}

pub(crate) async fn list_users_page(
    pool: &SqlitePool,
    actor_id: i64,
    page: u32,
    gravatar_enabled: bool,
) -> Result<UserPage, AdminError> {
    if !(1..=10_000).contains(&page) {
        return Err(AdminError::InvalidPage);
    }
    require_admin(pool, actor_id).await?;
    let rows = sqlx::query(
        "SELECT users.id, users.email, users.active, users.is_admin, \
         users.use_gravatar, user_avatars.filename AS avatar_filename FROM users \
         LEFT JOIN user_avatars ON user_avatars.user_id = users.id \
         ORDER BY users.email COLLATE NOCASE, users.id LIMIT ? OFFSET ?",
    )
    .bind(PAGE_SIZE + 1)
    .bind((i64::from(page) - 1) * PAGE_SIZE)
    .fetch_all(pool)
    .await
    .map_err(|_| AdminError::Database)?;
    let has_more = rows.len() > PAGE_SIZE as usize;
    let users = rows
        .into_iter()
        .take(PAGE_SIZE as usize)
        .map(decode_user(gravatar_enabled))
        .collect();
    Ok(UserPage {
        users,
        page,
        per_page: PAGE_SIZE as u32,
        has_more,
    })
}

pub(crate) async fn create_user(
    pool: &SqlitePool,
    actor_id: i64,
    email: String,
    password: Zeroizing<String>,
    is_admin: bool,
    gravatar_enabled: bool,
) -> Result<AdminUser, AdminError> {
    require_admin(pool, actor_id).await?;
    let email = email.trim().to_lowercase();
    if !valid_email(&email) {
        return Err(AdminError::InvalidEmail);
    }
    if password.len() > 1024 {
        return Err(AdminError::PasswordTooLong);
    }
    auth::validate_new_password(&password).map_err(AdminError::PasswordPolicy)?;
    let password_hash = auth::hash_password_for_account(password)
        .await
        .map_err(|_| AdminError::Database)?;

    create_user_with_hash(
        pool,
        actor_id,
        &email,
        &password_hash,
        is_admin,
        gravatar_enabled,
    )
    .await
}

async fn create_user_with_hash(
    pool: &SqlitePool,
    actor_id: i64,
    email: &str,
    password_hash: &str,
    is_admin: bool,
    gravatar_enabled: bool,
) -> Result<AdminUser, AdminError> {
    let mut tx = pool.begin().await.map_err(|_| AdminError::Database)?;
    require_admin_tx(&mut tx, actor_id).await?;
    let role = if is_admin {
        db::AccountRole::Admin
    } else {
        db::AccountRole::Member
    };
    let id = match db::insert_prehashed_account(&mut *tx, email, password_hash, role).await {
        Ok(id) => id,
        Err(db::DatabaseError::Account(error)) if is_unique_violation(&error) => {
            return Err(AdminError::AccountExists);
        }
        Err(db::DatabaseError::InvalidAccount) => return Err(AdminError::InvalidEmail),
        Err(_) => return Err(AdminError::Database),
    };
    let user = get_user_tx(&mut tx, id, gravatar_enabled).await?;
    tx.commit().await.map_err(|_| AdminError::Database)?;
    Ok(user)
}

pub(crate) async fn set_user_active(
    pool: &SqlitePool,
    actor_id: i64,
    target_id: i64,
    active: bool,
    gravatar_enabled: bool,
) -> Result<AdminUser, AdminError> {
    let mut tx = pool.begin().await.map_err(|_| AdminError::Database)?;
    require_admin_tx(&mut tx, actor_id).await?;
    let target = sqlx::query("SELECT active, is_admin FROM users WHERE id = ?")
        .bind(target_id)
        .fetch_optional(&mut *tx)
        .await
        .map_err(|_| AdminError::Database)?
        .ok_or(AdminError::NotFound)?;
    let was_active = target.get::<i64, _>("active") != 0;
    let is_admin = target.get::<i64, _>("is_admin") != 0;
    if target_id == actor_id && !active {
        return Err(AdminError::SelfDeactivation);
    }
    if !active && was_active && is_admin {
        let active_admins: i64 =
            sqlx::query_scalar("SELECT count(*) FROM users WHERE active = 1 AND is_admin = 1")
                .fetch_one(&mut *tx)
                .await
                .map_err(|_| AdminError::Database)?;
        if active_admins <= 1 {
            return Err(AdminError::Conflict);
        }
    }
    sqlx::query("UPDATE users SET active = ? WHERE id = ?")
        .bind(active)
        .bind(target_id)
        .execute(&mut *tx)
        .await
        .map_err(|_| AdminError::Database)?;
    if !active {
        revoke_all_tokens(&mut tx, target_id).await?;
    }
    let user = get_user_tx(&mut tx, target_id, gravatar_enabled).await?;
    tx.commit().await.map_err(|_| AdminError::Database)?;
    Ok(user)
}

pub(crate) async fn reset_password(
    pool: &SqlitePool,
    actor_id: i64,
    target_id: i64,
    password: Zeroizing<String>,
) -> Result<(), AdminError> {
    require_admin(pool, actor_id).await?;
    if password.len() > 1024 {
        return Err(AdminError::PasswordTooLong);
    }
    auth::validate_new_password(&password).map_err(AdminError::PasswordPolicy)?;
    let password_hash = auth::hash_password_for_account(password)
        .await
        .map_err(|_| AdminError::Database)?;

    reset_password_with_hash(pool, actor_id, target_id, &password_hash).await
}

async fn reset_password_with_hash(
    pool: &SqlitePool,
    actor_id: i64,
    target_id: i64,
    password_hash: &str,
) -> Result<(), AdminError> {
    let mut tx = pool.begin().await.map_err(|_| AdminError::Database)?;
    require_admin_tx(&mut tx, actor_id).await?;
    let exists: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM users WHERE id = ?)")
        .bind(target_id)
        .fetch_one(&mut *tx)
        .await
        .map_err(|_| AdminError::Database)?;
    if !exists {
        return Err(AdminError::NotFound);
    }
    sqlx::query("UPDATE users SET password_hash = ?, password_enabled = 1 WHERE id = ?")
        .bind(password_hash)
        .bind(target_id)
        .execute(&mut *tx)
        .await
        .map_err(|_| AdminError::Database)?;
    revoke_all_tokens(&mut tx, target_id).await?;
    tx.commit().await.map_err(|_| AdminError::Database)
}

async fn require_admin_tx(
    tx: &mut sqlx::Transaction<'_, sqlx::Sqlite>,
    actor_id: i64,
) -> Result<(), AdminError> {
    let result =
        sqlx::query("UPDATE users SET id = id WHERE id = ? AND active = 1 AND is_admin = 1")
            .bind(actor_id)
            .execute(&mut **tx)
            .await
            .map_err(|_| AdminError::Database)?;
    if result.rows_affected() == 1 {
        Ok(())
    } else {
        Err(AdminError::Forbidden)
    }
}

async fn get_user_tx(
    tx: &mut sqlx::Transaction<'_, sqlx::Sqlite>,
    user_id: i64,
    gravatar_enabled: bool,
) -> Result<AdminUser, AdminError> {
    sqlx::query(
        "SELECT users.id, users.email, users.active, users.is_admin, \
         users.use_gravatar, user_avatars.filename AS avatar_filename FROM users \
         LEFT JOIN user_avatars ON user_avatars.user_id = users.id \
         WHERE users.id = ?",
    )
    .bind(user_id)
    .fetch_optional(&mut **tx)
    .await
    .map_err(|_| AdminError::Database)?
    .map(decode_user(gravatar_enabled))
    .ok_or(AdminError::NotFound)
}

async fn revoke_all_tokens(
    tx: &mut sqlx::Transaction<'_, sqlx::Sqlite>,
    user_id: i64,
) -> Result<(), AdminError> {
    sqlx::query(
        "UPDATE api_tokens SET revoked_at = unixepoch() \
         WHERE user_id = ? AND revoked_at IS NULL",
    )
    .bind(user_id)
    .execute(&mut **tx)
    .await
    .map_err(|_| AdminError::Database)?;
    Ok(())
}

fn valid_email(email: &str) -> bool {
    email.len() <= 320
        && email.contains('@')
        && !email.contains(char::is_whitespace)
        && !email.starts_with('@')
        && !email.ends_with('@')
        && !email.is_empty()
}

fn is_unique_violation(error: &sqlx::Error) -> bool {
    matches!(error, sqlx::Error::Database(database) if database.is_unique_violation())
}

#[cfg(test)]
mod tests {
    use super::{AdminError, create_user_with_hash, reset_password_with_hash};
    use crate::db;

    #[tokio::test]
    async fn stale_admin_cannot_create_after_password_hashing() {
        let pool = db::connect("sqlite::memory:").await.unwrap();
        let actor = db::create_user(&pool, "actor@example.test", "actor-password-long".into())
            .await
            .unwrap();
        sqlx::query("UPDATE users SET is_admin = 1 WHERE id = ?")
            .bind(actor)
            .execute(&pool)
            .await
            .unwrap();
        sqlx::query("UPDATE users SET is_admin = 0 WHERE id = ?")
            .bind(actor)
            .execute(&pool)
            .await
            .unwrap();

        let result = create_user_with_hash(
            &pool,
            actor,
            "target@example.test",
            "prepared-password-hash",
            false,
            true,
        )
        .await;
        assert!(matches!(result, Err(AdminError::Forbidden)));
        let count: i64 = sqlx::query_scalar("SELECT count(*) FROM users WHERE email = ?")
            .bind("target@example.test")
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(count, 0);
    }

    #[tokio::test]
    async fn stale_admin_cannot_reset_after_password_hashing() {
        let pool = db::connect("sqlite::memory:").await.unwrap();
        let actor = db::create_user(&pool, "actor@example.test", "actor-password-long".into())
            .await
            .unwrap();
        let target = db::create_user(&pool, "target@example.test", "target-password-long".into())
            .await
            .unwrap();
        sqlx::query("UPDATE users SET is_admin = 1 WHERE id = ?")
            .bind(actor)
            .execute(&pool)
            .await
            .unwrap();
        let original: String = sqlx::query_scalar("SELECT password_hash FROM users WHERE id = ?")
            .bind(target)
            .fetch_one(&pool)
            .await
            .unwrap();
        let session_id: i64 = sqlx::query_scalar(
            "INSERT INTO api_tokens (user_id, name, token_hash, created_at, purpose) \
             VALUES (?, 'session', 'test-session-hash', unixepoch(), 'browser') RETURNING id",
        )
        .bind(target)
        .fetch_one(&pool)
        .await
        .unwrap();
        sqlx::query("UPDATE users SET is_admin = 0 WHERE id = ?")
            .bind(actor)
            .execute(&pool)
            .await
            .unwrap();

        let result =
            reset_password_with_hash(&pool, actor, target, "prepared-replacement-hash").await;
        assert!(matches!(result, Err(AdminError::Forbidden)));
        let stored: String = sqlx::query_scalar("SELECT password_hash FROM users WHERE id = ?")
            .bind(target)
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(stored, original);
        let revoked: Option<i64> =
            sqlx::query_scalar("SELECT revoked_at FROM api_tokens WHERE id = ?")
                .bind(session_id)
                .fetch_one(&pool)
                .await
                .unwrap();
        assert_eq!(revoked, None);
    }
}
